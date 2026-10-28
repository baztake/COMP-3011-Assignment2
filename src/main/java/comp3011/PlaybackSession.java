/*
 * Session composition prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import lombok.Getter;
import lombok.NonNull;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Runs one video-only decoding session with bounded parallel frame processing.
 * The decoder thread owns native decoding and copies frames before submission.
 * Volatile fields publish metadata, pool access and decoder status to other threads.
 * Synchronised startup and stopping prevent publication of a pool after cancellation.
 * Admission waits release the monitor; resume and stop wake the decoder.
 */
public class PlaybackSession {
    private final List<FrameEffect> frameEffects;

    // Accessed under the session monitor; never join while holding that monitor.
    private Thread decoderThread;

    // Protected by the session monitor; one initial preview can bypass a paused gate.
    private boolean paused;
    private boolean previewPending;

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
     * @param initiallyPaused whether to admit one preview and then wait for resume
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
        boolean initiallyPaused,
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

        paused = initiallyPaused;
        previewPending = initiallyPaused;

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

            VideoDecoder.DecodedFrame decoded = readNextFrame(decoder);
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
                decoded = readNextFrame(decoder);
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
     * Waits for admission before reading the next video frame.
     * Already admitted reads may finish after a pause request.
     *
     * @param decoder decoder owned by the current thread
     * @return borrowed decoded frame, or null on stopping or end of video
     * @throws InterruptedException if interrupted while waiting for admission
     * @throws FrameGrabber.Exception if decoding fails
     */
    private VideoDecoder.DecodedFrame readNextFrame(VideoDecoder decoder)
        throws InterruptedException, FrameGrabber.Exception {
        synchronized (this) {
            while (paused && !previewPending && !stopRequested) {
                wait();
            }
            if (stopRequested) {
                return null;
            }

            // Consume the initial preview permission before releasing the monitor.
            previewPending = false;
        }

        // Native decoding must not hold the monitor needed by pause, resume or stop.
        return decoder.read();
    }

    /**
     * Changes admission of future decoder reads without waiting for in-flight work.
     * Ordinary pausing grants no preview; resuming wakes the decoder.
     * Presentation timing and audio must be controlled separately.
     *
     * @param paused whether future reads should wait
     */
    public synchronized void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) {
            // An early resume must not leave a preview permission for a later pause.
            previewPending = false;
            notifyAll();
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
     * Wakes admission waits, interrupts queue-space waits and cancels accepted pool work.
     * Native decoding may delay interruption; only the decoder thread closes it.
     */
    public synchronized void requestStop() {
        stopRequested = true;
        notifyAll();
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
     * Does not request stopping; graceful completion requires polling and resume if paused.
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