/*
 * Session composition prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.NonFinal;
import org.bytedeco.javacv.Frame;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Runs one video-only decoding session with bounded parallel frame processing.
 * The decoder thread owns native decoding and copies frames before submission.
 * Volatile fields publish metadata, pool access and decoder status to other threads.
 * Synchronised startup and stopping prevent publication of a pool after cancellation.
 * Audio, playback timing and model integration are not connected yet.
 */
public class PlaybackSession {
    private final List<FrameEffect> frameEffects;

    // Accessed under the session monitor; never join while holding that monitor.
    private Thread decoderThread;

    private volatile boolean stopRequested;
    private volatile FrameProcessingPool pool;
    /**
     * -- GETTER --
     *  Returns safely published metadata from the first decoded video frame.
     *
     * @return immutable video metadata, or null before publication
     */
    @Getter
    private volatile InfoVideo videoInfo;
    /**
     * -- GETTER --
     *  Returns an unexpected decoder, startup or close failure.
     *  Worker failures are reported separately by pollReady.
     *
     * @return decoder failure, or null if no unexpected failure has been recorded
     */
    @Getter
    private volatile Throwable failure;
    /**
     * -- GETTER --
     *  Reports decoder exit after EOF, stopping or failure.
     *  Does not establish worker termination or consumption of all results.
     *
     * @return true once decoder cleanup and pool shutdown have been attempted
     */
    @Getter
    private volatile boolean decoderFinished;

    /**
     * Stores an immutable snapshot of the selected effects.
     *
     * @param frameEffects effects in command-line order, including repetitions
     * @throws NullPointerException if the list or an effect is null
     */
    public PlaybackSession(List<FrameEffect> frameEffects) {
        this.frameEffects = List.copyOf(frameEffects);
    }

    /**
     * Starts this session once and transfers decoder ownership on successful startup.
     * Invalid or rejected startup leaves the decoder owned by the caller.
     * All decoder operations run on the new thread, including cleanup.
     *
     * @param decoder unstarted decoder transferred to this session
     * @param startTimestampUs starting media position in microseconds
     * @param workerCount number of processing threads
     * @param maxQueuedFrames maximum number of accepted, unpolled frames
     * @param generation playback generation attached to submitted frames
     * @throws NullPointerException if decoder is null
     * @throws IllegalArgumentException if the timestamp is negative or either limit is not positive
     * @throws IllegalStateException if this session has already started or been stopped
     */
    public synchronized void start(
        @NonNull VideoDecoder decoder,
        long startTimestampUs,
        int workerCount,
        int maxQueuedFrames,
        long generation
    ) {
        if (startTimestampUs < 0 || workerCount <= 0 || maxQueuedFrames <= 0) {
            throw new IllegalArgumentException("Invalid session timestamp or processing limits");
        }
        if (decoderThread != null || stopRequested) {
            throw new IllegalStateException("Playback session cannot be started again");
        }

        Thread thread = new Thread(
            () -> decodeVideo(decoder, startTimestampUs, workerCount, maxQueuedFrames, generation),
            "video-decoder-" + generation
        );
        // Publish only after successful startup; the monitor prevents stop from missing it.
        thread.start();
        decoderThread = thread;
    }

    /**
     * Opens the decoder, publishes its pool and submits copied video frames.
     * EOF closes the pool gracefully; stopping or failure cancels outstanding work.
     *
     * @param decoder decoder owned and closed by this thread
     * @param startTimestampUs starting media position in microseconds
     * @param workerCount number of processing threads
     * @param maxQueuedFrames maximum number of accepted, unpolled frames
     * @param generation playback generation attached to submitted frames
     */
    private void decodeVideo(
        VideoDecoder decoder,
        long startTimestampUs,
        int workerCount,
        int maxQueuedFrames,
        long generation
    ) {
        FrameProcessingPool processingPool = null;

        try (decoder) {
            if (stopRequested) {
                return;
            }
            decoder.open(startTimestampUs);
            if (stopRequested) {
                return;
            }

            VideoDecoder.DecodedFrame decoded = decoder.read();
            if (decoded == null || stopRequested) {
                return;
            }

            InfoVideo metadata = decoder.videoInfo();
            processingPool = createPool(workerCount, maxQueuedFrames, metadata);

            synchronized (this) {
                // Stop and publication use the same monitor, so a stopped session
                // cannot publish a fresh pool that the stopping caller missed.
                if (stopRequested) {
                    return;
                }
                videoInfo = metadata;
                pool = processingPool;
            }

            while (decoded != null && !stopRequested) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Video decoding interrupted");
                }

                // The next read may reuse decoder memory, so submit an independent copy.
                Frame copy = decoded.frame().clone();
                boolean accepted = false;
                try {
                    processingPool.submit(copy, decoded.info(), generation);
                    accepted = true;
                } finally {
                    // Rejected or interrupted submission leaves ownership here.
                    // Accepted inputs are closed exclusively by the pool.
                    if (!accepted) {
                        copy.close();
                    }
                }

                if (stopRequested) {
                    break;
                }
                decoded = decoder.read();
            }
        } catch (InterruptedException | RejectedExecutionException cancelled) {
            // Preserve any decoder-close failure suppressed onto cancellation.
            if (!stopRequested || cancelled.getSuppressed().length > 0) {
                failure = cancelled;
            }
            if (cancelled instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception | LinkageError failed) {
            failure = failed;
        } finally {
            if (processingPool != null) {
                if (stopRequested || failure != null) {
                    processingPool.requestStop();
                } else {
                    // Keep accepted results available after the decoder reaches EOF.
                    processingPool.close();
                }
            }
            decoderFinished = true;
        }
    }

    /**
     * Retrieves the oldest processed result without waiting for processing.
     * Poll only when the caller's single pending presentation slot is empty.
     *
     * @return oldest ready result, or null before startup or while no head is ready
     * @throws ExecutionException if worker creation or processing failed
     * @throws InterruptedException if result retrieval is interrupted
     */
    public PreparedFrame pollReady() throws ExecutionException, InterruptedException {
        FrameProcessingPool processingPool = pool;
        return processingPool == null ? null : processingPool.pollReady();
    }

    /**
     * Requests cancellation without waiting for decoder or worker termination.
     * Interrupts a decoder waiting for queue space and cancels accepted pool work.
     * Native decoding may delay interruption; only the decoder thread closes it.
     */
    public synchronized void requestStop() {
        stopRequested = true;
        if (decoderThread != null) {
            decoderThread.interrupt();
        }
        if (pool != null) {
            pool.requestStop();
        }
    }

    /**
     * Waits for decoder and worker exit using one shared timeout budget.
     * Call from a lifecycle thread, never JavaFX or a session worker.
     * Does not request stopping; graceful completion still requires result polling.
     *
     * @param timeout combined termination waiting time; nonpositive values only check status
     * @param unit unit of the timeout
     * @return true if decoder and workers have exited, otherwise false on timeout
     * @throws NullPointerException if unit is null
     * @throws IllegalStateException if called by the decoder thread
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNs = Math.max(0L, unit.toNanos(timeout));
        long startedNs = System.nanoTime();
        Thread thread;
        synchronized (this) {
            thread = decoderThread;
        }
        if (thread == null) {
            return true;
        }
        if (thread == Thread.currentThread()) {
            throw new IllegalStateException("Decoder cannot wait for its own termination");
        }

        long remainingNs = timeoutNs - (System.nanoTime() - startedNs);
        if (thread.isAlive()) {
            // A zero timeout must not become an unbounded Thread.join(0).
            if (remainingNs <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedJoin(thread, remainingNs);
            if (thread.isAlive()) {
                return false;
            }
        }

        FrameProcessingPool processingPool = pool;
        remainingNs = Math.max(0L, timeoutNs - (System.nanoTime() - startedNs));
        return processingPool == null
            || processingPool.awaitTermination(remainingNs, TimeUnit.NANOSECONDS);
    }

    /**
     * Creates a bounded pool using this session's selected effects.
     * Each processing thread creates and initialises its own worker on first use.
     * The caller owns pool shutdown and termination waiting.
     *
     * @param workerCount     number of processing threads
     * @param maxQueuedFrames maximum number of accepted, unpolled frames
     * @param videoInfo       metadata obtained from the decoded video
     * @return pool configured to create private, initialised workers
     * @throws IllegalArgumentException if workerCount or maxQueuedFrames is not positive
     */
    FrameProcessingPool createPool(int workerCount, int maxQueuedFrames, InfoVideo videoInfo) {
        return new FrameProcessingPool(workerCount, maxQueuedFrames, () -> {
            try {
                return createWorker(videoInfo);
            } catch (Exception failure) {
                // Supplier cannot throw checked exceptions.
                // The pool reports this failure through the submitted frame's future.
                throw new IllegalStateException("Could not initialise frame processing worker", failure);
            }
        });
    }

    /**
     * Creates a worker with a fresh, initialised processor chain.
     * Local occurrence counts preserve equivalent seeds across workers.
     *
     * @param videoInfo metadata obtained from the decoded video
     * @return worker owning the newly created processors
     * @throws Exception if processor initialisation fails
     */
    private FrameProcessingWorker createWorker(InfoVideo videoInfo) throws Exception {
        Map<FrameEffect, Integer> occurrenceCounts = new EnumMap<>(FrameEffect.class);
        List<FrameProcessor> processors = new ArrayList<>();

        for (FrameEffect effect : frameEffects) {
            int occurrenceIndex = occurrenceCounts.getOrDefault(effect, 0);
            FrameProcessor processor = effect.createProcessor(occurrenceIndex);

            // Initialise before publishing the processor to its worker.
            processor.initialise(videoInfo);
            processors.add(processor);
            occurrenceCounts.put(effect, occurrenceIndex + 1);
        }

        return new FrameProcessingWorker(processors);
    }
}