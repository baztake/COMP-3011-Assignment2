/*
 * Audio preparation written by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import lombok.Getter;
import lombok.NonNull;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Decodes audio on its own thread and submits copied packets to AudioOutput.
 * Separate audio and video readers avoid blocking one stream behind the other's full queue.
 * Startup, stopping and output publication use the same monitor.
 * The playback clock and presentation remain the session coordinator's responsibility.
 * The coordinator must keep the shared clock paused until startup is ready.
 */
public class AudioPreparation {
    private final AudioOutput output;

    // Accessed under the monitor; never wait for termination while holding it.
    private Thread decoderThread;
    private volatile boolean stopRequested;
    private volatile Throwable failure;

    /**
     * -- GETTER --
     *
     * @return true when the audio stream has been inspected
     */
    @Getter
    private volatile boolean probeComplete;

    /**
     * -- GETTER --
     *
     * @return true if audio exists; check probeComplete before interpreting false
     */
    @Getter
    private volatile boolean audioAvailable;

    /**
     * -- GETTER --
     *
     * @return timestamp of the first queued packet in microseconds, valid after firstPacketQueued
     */
    @Getter
    private volatile long firstAudioTimestampUs;

    /**
     * -- GETTER --
     *
     * @return true when the first nonempty audio packet has been accepted
     */
    @Getter
    private volatile boolean firstPacketQueued;

    /**
     * -- GETTER --
     *
     * @return true after decoder cleanup and end-of-input handling
     */
    @Getter
    private volatile boolean decoderFinished;

    /**
     * Takes ownership of an unstarted audio output.
     *
     * @param output bounded audio output using the session's shared clock
     * @throws NullPointerException if output is null
     */
    public AudioPreparation(@NonNull AudioOutput output) {
        this.output = output;
    }

    /**
     * Returns the clock shared with audio output.
     *
     * @return playback clock used by the output
     */
    PlaybackClock playbackClock() {
        return output.playbackClock();
    }

    /**
     * Starts audio decoding once and transfers decoder ownership on success.
     * Rejected startup leaves decoder ownership with the caller.
     *
     * @param decoder unstarted audio decoder transferred to this preparation
     * @param startTimestampUs starting media position in microseconds
     * @throws NullPointerException if decoder is null
     * @throws IllegalArgumentException if startTimestampUs is negative
     * @throws IllegalStateException if already started or stopped
     */
    public synchronized void start(@NonNull AudioDecoder decoder, long startTimestampUs) {
        if (startTimestampUs < 0) {
            throw new IllegalArgumentException("Starting timestamp must not be negative");
        }
        if (decoderThread != null || stopRequested) {
            throw new IllegalStateException("Audio preparation cannot be started again");
        }

        Thread thread = new Thread(() -> prepareAudio(decoder, startTimestampUs), "audio-decoder");
        thread.start();
        decoderThread = thread;
    }

    /**
     * Opens the audio stream, submits copied samples and finishes input at EOF.
     *
     * @param decoder decoder owned by the current thread
     * @param startTimestampUs starting media position in microseconds
     */
    private void prepareAudio(AudioDecoder decoder, long startTimestampUs) {
        boolean outputStarted = false;
        try (decoder) {
            if (stopRequested) return;
            decoder.open(startTimestampUs);
            if (stopRequested) return;

            audioAvailable = decoder.hasAudio();
            // Publish availability before completion so a no-audio session can proceed.
            probeComplete = true;
            if (!audioAvailable) return;

            int sampleRate = decoder.sampleRate();
            int channels = decoder.channels();
            synchronized (this) {
                // A stop must not miss an output started after cancellation.
                if (stopRequested) return;
                output.start(new AudioPlayer(), sampleRate, channels);
                outputStarted = true;
            }

            AudioDecoder.DecodedAudio decoded;
            while (!stopRequested && (decoded = decoder.read()) != null) {
                // Empty copies represent absent or unsupported samples.
                if (decoded.samples().length > 0) {
                    output.enqueue(decoded.timestampUs(), decoded.samples());
                    if (!firstPacketQueued) {
                        // Publish the timestamp before the readiness flag.
                        firstAudioTimestampUs = decoded.timestampUs();
                        firstPacketQueued = true;
                    }
                }
            }

            if (!stopRequested) {
                output.finishInput();
            }
        } catch (InterruptedException | RejectedExecutionException cancelled) {
            if (!stopRequested || cancelled.getSuppressed().length > 0) {
                Throwable outputFailure = output.getFailure();
                failure = outputFailure == null || cancelled.getSuppressed().length > 0
                    ? cancelled : outputFailure;
            }
            if (cancelled instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception | LinkageError failed) {
            failure = failed;
        } finally {
            if (outputStarted && (stopRequested || failure != null)) {
                output.requestStop();
            }
            decoderFinished = true;
        }
    }

    /**
     * Reports whether the audio device has opened and output remains active.
     * Device readiness alone does not establish that an audio packet is available.
     *
     * @return true when AudioOutput is ready
     */
    public boolean isOutputReady() {
        return output.isReady();
    }

    /**
     * Returns a decoder or audio-output failure, if one occurred.
     *
     * @return recorded failure, or null when neither component has failed
     */
    public Throwable getFailure() {
        Throwable decoderFailure = failure;
        return decoderFailure == null ? output.getFailure() : decoderFailure;
    }

    /**
     * Requests cancellation of audio decoding and output without waiting.
     * Native calls may delay termination; each owner closes its own resources.
     */
    public synchronized void requestStop() {
        stopRequested = true;
        if (decoderThread != null) {
            decoderThread.interrupt();
        }
        output.requestStop();
    }

    /**
     * Waits for both audio owners using one shared timeout budget.
     * Call from a lifecycle thread, never JavaFX or either audio owner.
     * Paused playback must resume to finish queued audio gracefully.
     *
     * @param timeout maximum combined waiting time
     * @param unit unit of the timeout
     * @return true if both owners exited, otherwise false on timeout
     * @throws NullPointerException if unit is null
     * @throws IllegalStateException if called by the audio decoder thread
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNs = Math.max(0L, unit.toNanos(timeout));
        long startedNs = System.nanoTime();
        Thread thread;
        synchronized (this) {
            thread = decoderThread;
        }
        if (thread == null) return true;
        if (thread == Thread.currentThread()) {
            throw new IllegalStateException("Audio decoder cannot wait for itself");
        }

        long remainingNs = timeoutNs - (System.nanoTime() - startedNs);
        if (thread.isAlive()) {
            if (remainingNs <= 0) return false;
            TimeUnit.NANOSECONDS.timedJoin(thread, remainingNs);
            if (thread.isAlive()) return false;
        }

        remainingNs = Math.max(0L, timeoutNs - (System.nanoTime() - startedNs));
        return output.awaitTermination(remainingNs, TimeUnit.NANOSECONDS);
    }
}
