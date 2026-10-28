/* Audio output coordination prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. */
package comp3011;

import lombok.Getter;
import lombok.NonNull;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Owns audio-device access on one thread and queues copied PCM packets.
 * The queue includes the packet being written until all its bytes are accepted.
 * The monitor protects the queue and lifecycle, device calls happen outside it.
 * Clock pause is checked between writes, an admitted write and buffered audio may continue.
 * Finished input drains accepted packets; stop discards queued packets.
 * A selected or in-progress device drain may continue after stop.
 */
public class AudioOutput {

    private static final long RETRY_MS = 5;
    private final PlaybackClock clock;
    private final int capacity;
    private final Queue<Packet> packets = new ArrayDeque<>();
    // Accessed under the monitor; never join while holding it.
    private Thread thread;
    private volatile boolean closed;
    // Protected by the monitor; prevents submissions after end of input.
    private boolean inputFinished;
    /**
     * -- GETTER --
     *
     * @return true when the device has opened and output remains active
     */
    @Getter
    private volatile boolean ready;
    /**
     * -- GETTER --
     *
     * @return unexpected device failure, or null if none has occurred
     */
    @Getter
    private volatile Throwable failure;

    /**
     * Configures bounded audio output.
     *
     * @param clock    shared playback clock
     * @param capacity maximum accepted packets, including the active packet
     * @throws NullPointerException     if clock is null
     * @throws IllegalArgumentException if capacity is not positive
     */
    public AudioOutput(PlaybackClock clock, int capacity) {
        this.clock = Objects.requireNonNull(clock);
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
    }

    /**
     * Returns the clock shared with the session coordinator.
     *
     * @return playback clock used by this output
     */
    PlaybackClock playbackClock() {
        return clock;
    }

    /**
     * Starts the owner thread and transfers the player on successful startup.
     * Rejected startup leaves ownership with the caller.
     *
     * @param player     unopened player to own
     * @param sampleRate samples per second
     * @param channels   number of interleaved channels
     * @throws NullPointerException     if player is null
     * @throws IllegalArgumentException if the format values are not positive
     * @throws IllegalStateException    if already started or stopped
     */
    public synchronized void start(AudioPlayer player, int sampleRate, int channels) {
        Objects.requireNonNull(player);
        if (sampleRate <= 0 || channels <= 0) throw new IllegalArgumentException("Invalid audio format");
        if (thread != null || closed) throw new IllegalStateException("Audio output cannot be restarted");
        Thread owner = new Thread(() -> runOutput(player, sampleRate, channels), "audio-output");
        owner.start();
        thread = owner;
    }

    /**
     * Waits for queue capacity and transfers ownership of copied PCM bytes.
     * Submit packets in playback order from the decoder, never JavaFX.
     * The caller must not modify accepted arrays.
     *
     * @param timestampUs packet media timestamp in microseconds
     * @param samples     copied bytes containing complete interleaved audio frames
     * @throws NullPointerException       if samples is null
     * @throws RejectedExecutionException if output has not started or no longer accepts input
     * @throws InterruptedException       if interrupted while waiting for capacity
     */
    public synchronized void enqueue(long timestampUs, @NonNull byte[] samples) throws InterruptedException {
        if (thread == null) throw new RejectedExecutionException("Audio output has not started");
        while (!closed && !inputFinished && packets.size() >= capacity) wait();
        if (closed || inputFinished) throw new RejectedExecutionException("Audio output no longer accepts input");
        packets.add(new Packet(timestampUs, samples));
        notifyAll();
    }

    /**
     * Runs device operations and releases the device on the owning thread.
     *
     * @param player     owned device
     * @param sampleRate samples per second
     * @param channels   number of channels
     */
    private void runOutput(AudioPlayer player, int sampleRate, int channels) {
        try (player) {
            if (closed) return;
            player.open(sampleRate, channels);
            synchronized (this) {
                if (closed) return;
                ready = true;
            }
            Packet packet;
            while ((packet = awaitPacket()) != null) {
                writePacket(player, packet);
                synchronized (this) {
                    // Stop may have cleared the queue during a device write.
                    if (!closed) {
                        packets.remove();
                        notifyAll();
                    }
                }
            }

            // Queue empty means bytes were accepted, not necessarily played.
            // Drain on the owner before close flushes the device.
            boolean drain;
            synchronized (this) {
                drain = inputFinished && !closed;
            }
            if (drain) {
                player.drain();
            }
        } catch (InterruptedException interrupted) {
            if (!closed || interrupted.getSuppressed().length > 0) failure = interrupted;
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError failed) {
            failure = failed;
        } finally {
            synchronized (this) {
                closed = true;
                ready = false;
                packets.clear();
                notifyAll();
            }
        }
    }

    /**
     * Waits for the head without releasing its capacity.
     *
     * @return oldest packet, or null when stopped or input has finished and the queue is empty
     * @throws InterruptedException if interrupted while waiting
     */
    private synchronized Packet awaitPacket() throws InterruptedException {
        while (packets.isEmpty() && !closed && !inputFinished) wait();
        return closed ? null : packets.peek();
    }

    /**
     * Waits for a packet's deadline and handles partial writes.
     *
     * @param player owner-thread device
     * @param packet packet retained at the queue head
     * @throws InterruptedException if interrupted while waiting
     */
    private void writePacket(AudioPlayer player, Packet packet) throws InterruptedException {
        int offset = 0;
        while (!closed && offset < packet.samples().length) {
            if (!clock.isDue(packet.timestampUs(), System.nanoTime())) {
                waitBeforeRetry();
                continue;
            }
            int written = player.write(packet.samples(), offset, packet.samples().length - offset);
            if (written <= 0) waitBeforeRetry();
            else offset += written;
        }
    }

    /**
     * Yields between clock or device-capacity checks.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    private synchronized void waitBeforeRetry() throws InterruptedException {
        if (!closed) wait(RETRY_MS);
    }

    /**
     * Rejects new input and lets accepted packets finish in order.
     * The owner drains queued device audio before closing.
     * Does not wait for completion; device draining may delay stopping.
     * A stop request discards packets that have not been written.
     *
     * @throws IllegalStateException if output has not started
     */
    public synchronized void finishInput() {
        if (thread == null) throw new IllegalStateException("Audio output has not started");
        inputFinished = true;
        notifyAll();
    }

    /**
     * Rejects input, discards queued packets and interrupts the owner.
     * Device closure remains the owner thread's responsibility.
     * A selected or in-progress device drain may delay closure.
     */
    public synchronized void requestStop() {
        closed = true;
        ready = false;
        packets.clear();
        notifyAll();
        if (thread != null) thread.interrupt();
    }

    /**
     * Waits for owner exit without holding the monitor.
     * Call from a lifecycle thread after stopping or finishing input, never JavaFX.
     * Device drain may block and may not respond promptly to interruption.
     *
     * @param timeout maximum wait; nonpositive values only check status
     * @param unit    timeout unit
     * @return true if the owner exited, otherwise false
     * @throws NullPointerException  if unit is null
     * @throws IllegalStateException if called by the audio owner thread
     * @throws InterruptedException  if the caller is interrupted
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = Math.max(0L, unit.toNanos(timeout));
        Thread owner;
        synchronized (this) {
            owner = thread;
        }
        if (owner == null) return true;
        if (owner == Thread.currentThread()) throw new IllegalStateException("Owner cannot wait for itself");
        if (owner.isAlive() && nanos > 0) TimeUnit.NANOSECONDS.timedJoin(owner, nanos);
        return !owner.isAlive();
    }

    /**
     * Associates an owned PCM array with its media timestamp.
     *
     * @param timestampUs media timestamp in microseconds
     * @param samples     copied PCM bytes
     */
    private record Packet(long timestampUs, byte[] samples) {
    }
}
