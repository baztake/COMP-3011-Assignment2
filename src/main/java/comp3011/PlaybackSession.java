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
 * Runs a decoding session with bounded parallel frame processing.
 * An optional independent audio path shares the session playback clock.
 * Decoder threads own their native resources and copy frames before submission.
 * Volatile fields publish metadata, pool access and startup status to other threads.
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
    // Registered before submission, even while the first frame is not yet visible.
    private volatile FrameProcessingPool ownedPool;
    private volatile AudioPreparation audioPreparation;

    /**
     * -- GETTER --
     *
     * @return shared audio/video clock, or null for the existing video-only path
     */
    @Getter
    private volatile PlaybackClock playbackClock;

    /**
     * -- GETTER --
     *
     * @return true when the shared clock has been initialized after startup
     */
    @Getter
    private volatile boolean startupReady;
    /**
     * -- GETTER --
     * Returns safely published metadata from the first decoded video frame.
     *
     * @return immutable video metadata, or null before publication
     */
    @Getter
    private volatile InfoVideo videoInfo;
    // Published decoder or startup failure; audio output reports its own failure.
    private volatile Throwable failure;
    /**
     * -- GETTER --
     * Reports decoder exit after EOF, stopping or failure.
     * Does not establish worker termination or consumption of all results.
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
     * Starts independent audio and video preparation with one shared clock.
     * The audio preparation must own an unstarted output and use its shared clock.
     * The clock remains paused until the first video result and audio startup are ready.
     * After argument validation, the session owns the audio preparation even if startup fails.
     * If partial startup fails, accepted audio work is stopped; unaccepted decoders remain caller-owned.
     *
     * @param decoder          unstarted video decoder
     * @param audioDecoder     unstarted audio decoder
     * @param audio            unstarted audio preparation and its output
     * @param startTimestampUs starting media position in microseconds
     * @param initiallyPaused  whether to prepare one preview and remain paused
     * @param workerCount      number of processing threads
     * @param maxQueuedFrames  maximum accepted, unpolled video frames
     * @param generation       playback generation associated with video frames
     * @throws NullPointerException     if a decoder or audio preparation is null
     * @throws IllegalArgumentException if the timestamp or processing limits are invalid
     * @throws IllegalStateException    if this session has already started or stopped
     */
    public synchronized void startWithAudio(
        @NonNull VideoDecoder decoder,
        @NonNull AudioDecoder audioDecoder,
        @NonNull AudioPreparation audio,
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

        PlaybackClock clock = audio.playbackClock();
        clock.setPaused(true, System.nanoTime());
        playbackClock = clock;
        audioPreparation = audio;
        try {
            audio.start(audioDecoder, startTimestampUs);
            start(decoder, startTimestampUs, initiallyPaused, workerCount, maxQueuedFrames, generation);
        } catch (RuntimeException | Error failed) {
            stopRequested = true;
            audio.requestStop();
            throw failed;
        }
    }

    /**
     * Returns a video or audio preparation failure, if one occurred.
     * Worker failures are reported by pollReady or during startup.
     * The caller must check regularly, including while paused or holding a pending frame.
     * On failure, request stop and wait for termination off JavaFX.
     *
     * @return recorded failure, or null when neither path has failed
     */
    public Throwable getFailure() {
        Throwable videoFailure = failure;
        AudioPreparation audio = audioPreparation;
        return videoFailure != null || audio == null ? videoFailure : audio.getFailure();
    }

    /**
     * Starts this session once and transfers decoder ownership on successful startup.
     * Invalid or rejected startup leaves the decoder owned by the caller.
     * All decoder operations run on the new thread, including cleanup.
     *
     * @param decoder          unstarted decoder transferred to this session
     * @param startTimestampUs starting media position in microseconds
     * @param initiallyPaused  whether to admit one preview and then wait for resume
     * @param workerCount      number of processing threads
     * @param maxQueuedFrames  maximum number of accepted, unpolled frames
     * @param generation       playback generation attached to submitted frames
     * @throws NullPointerException     if decoder is null
     * @throws IllegalArgumentException if the timestamp is negative or either limit is not positive
     * @throws IllegalStateException    if this session has already started or been stopped
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
     * @param decoder          decoder owned and closed by this thread
     * @param startTimestampUs starting media position in microseconds
     * @param workerCount      number of processing threads
     * @param maxQueuedFrames  maximum number of accepted, unpolled frames
     * @param generation       playback generation attached to submitted frames
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
                if (decoded == null && !stopRequested && audioPreparation != null) {
                    failure = new IllegalStateException("Media contains no video frame");
                }
                return;
            }

            InfoVideo metadata = decoder.videoInfo();
            processingPool = createPool(workerCount, maxQueuedFrames, metadata);
            synchronized (this) {
                // Stop and termination must see workers before any task can start.
                if (stopRequested) return;
                ownedPool = processingPool;
            }

            AudioPreparation audio = audioPreparation;
            if (audio != null) {
                // Prepare the first video frame before publishing the pool.
                // A preview may then be polled while audio startup continues.
                submitFrame(processingPool, decoded, generation);
                processingPool.awaitFirstResult();
            }

            synchronized (this) {
                // Stop and publication use the same monitor, so a stopped session
                // cannot publish a fresh pool that the stopping caller missed.
                if (stopRequested) {
                    return;
                }
                videoInfo = metadata;
                pool = processingPool;
            }

            if (audio != null) {
                if (!awaitAudioStartup(audio, decoded.info().timestampUs())) {
                    return;
                }
                decoded = readNextFrame(decoder);
            }

            while (decoded != null && !stopRequested) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Video decoding interrupted");
                }

                submitFrame(processingPool, decoded, generation);

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
            AudioPreparation audio = audioPreparation;
            if (audio != null && (stopRequested || failure != null)) {
                audio.requestStop();
            }
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
     * Copies and submits one borrowed video frame.
     * The pool owns accepted copies; the decoder closes a rejected copy.
     *
     * @param processingPool bounded pool receiving the frame
     * @param decoded        borrowed decoded frame and its information
     * @param generation     playback generation associated with the frame
     * @throws InterruptedException       if interrupted while waiting for capacity
     * @throws RejectedExecutionException if the pool is closed
     */
    private void submitFrame(FrameProcessingPool processingPool, VideoDecoder.DecodedFrame decoded, long generation)
        throws InterruptedException {
        Frame copy = decoded.frame().clone();
        boolean accepted = false;
        try {
            processingPool.submit(copy, decoded.info(), generation);
            accepted = true;
        } finally {
            if (!accepted) {
                copy.close();
            }
        }
    }

    /**
     * Waits for audio readiness before initializing the shared clock.
     * A preview may already be available while this method waits.
     *
     * @param audio            audio preparation owned by this session
     * @param videoTimestampUs timestamp of the first prepared video frame
     * @return false if stopping was requested
     * @throws InterruptedException if interrupted while waiting
     */
    private boolean awaitAudioStartup(AudioPreparation audio, long videoTimestampUs) throws InterruptedException {
        while (!stopRequested) {
            Throwable audioFailure = audio.getFailure();
            if (audioFailure != null) {
                throw new IllegalStateException("Audio startup failed", audioFailure);
            }

            boolean hasPacket = audio.isFirstPacketQueued();
            boolean audioReady = audio.isProbeComplete() && (
                !audio.isAudioAvailable()
                    || (hasPacket && audio.isOutputReady())
                    || (!hasPacket && audio.isDecoderFinished()
                    && audio.awaitTermination(0, TimeUnit.NANOSECONDS))
            );
            if (audioReady) {
                long firstTimestampUs = hasPacket ? Math.min(videoTimestampUs, audio.getFirstAudioTimestampUs()) : videoTimestampUs;
                synchronized (this) {
                    if (stopRequested) return false;
                    playbackClock.reset(firstTimestampUs, System.nanoTime(), paused);
                    startupReady = true;
                    return true;
                }
            }

            synchronized (this) {
                if (!stopRequested) wait(5);
            }
        }
        return false;
    }

    /**
     * Waits for admission before reading the next video frame.
     * Already admitted reads may finish after a pause request.
     *
     * @param decoder decoder owned by the current thread
     * @return borrowed decoded frame, or null on stopping or end of video
     * @throws InterruptedException   if interrupted while waiting for admission
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
     * Before startup finishes, only the desired pause state changes.
     * After startup, the shared clock controls audio scheduling and presentation timing.
     * An admitted write and device-buffered audio may continue after pausing.
     *
     * @param paused whether future reads should wait
     */
    public synchronized void setPaused(boolean paused) {
        this.paused = paused;
        if (startupReady && playbackClock != null) {
            playbackClock.setPaused(paused, System.nanoTime());
        }
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
     * @throws ExecutionException   if worker creation or processing failed
     * @throws InterruptedException if result retrieval is interrupted
     */
    public PreparedFrame pollReady() throws ExecutionException, InterruptedException {
        FrameProcessingPool processingPool = pool;
        return processingPool == null ? null : processingPool.pollReady();
    }

    /**
     * Checks whether video results and audio output have finished.
     * A result already polled by the caller is outside this check.
     * The caller must display or discard its pending frame before finishing playback.
     * Handle pollReady failures and inspect getFailure separately.
     * Use awaitTermination to confirm all workers exited.
     *
     * @return true if no unpolled video results or unfinished audio remain, and no stop or failure is recorded
     */
    public boolean isDrained() {
        if (stopRequested || getFailure() != null || !decoderFinished) {
            return false;
        }

        FrameProcessingPool processingPool = ownedPool;
        if (processingPool != null && processingPool.hasPendingResults()) {
            return false;
        }

        AudioPreparation audio = audioPreparation;
        return (audio == null || audio.isFinished()) && getFailure() == null && !stopRequested;
    }

    /**
     * Requests cancellation without waiting for decoder or worker termination.
     * Wakes admission waits, interrupts queue-space waits and cancels accepted pool work.
     * Native decoding may delay interruption; only the decoder thread closes it.
     */
    public synchronized void requestStop() {
        stopRequested = true;
        notifyAll();
        if (audioPreparation != null) {
            audioPreparation.requestStop();
        }
        if (decoderThread != null) {
            decoderThread.interrupt();
        }
        if (ownedPool != null) {
            ownedPool.requestStop();
        }
    }

    /**
     * Waits for video, processing workers and audio owners using one shared timeout budget.
     * Call from a lifecycle thread, never JavaFX or a session worker.
     * Does not request stopping; graceful completion requires polling and resume if paused.
     *
     * @param timeout combined termination waiting time; nonpositive values only check status
     * @param unit    unit of the timeout
     * @return true if all started owners have exited, otherwise false on timeout
     * @throws NullPointerException  if unit is null
     * @throws IllegalStateException if called by the decoder thread
     * @throws InterruptedException  if the waiting thread is interrupted
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNs = Math.max(0L, unit.toNanos(timeout));
        long startedNs = System.nanoTime();
        Thread thread;
        synchronized (this) {
            thread = decoderThread;
        }
        if (thread == null) {
            AudioPreparation audio = audioPreparation;
            long remainingNs = Math.max(0L, timeoutNs - (System.nanoTime() - startedNs));
            return audio == null || audio.awaitTermination(remainingNs, TimeUnit.NANOSECONDS);
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

        FrameProcessingPool processingPool = ownedPool;
        remainingNs = Math.max(0L, timeoutNs - (System.nanoTime() - startedNs));
        if (processingPool != null && !processingPool.awaitTermination(remainingNs, TimeUnit.NANOSECONDS)) {
            return false;
        }

        AudioPreparation audio = audioPreparation;
        remainingNs = Math.max(0L, timeoutNs - (System.nanoTime() - startedNs));
        return audio == null || audio.awaitTermination(remainingNs, TimeUnit.NANOSECONDS);
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