/*
 * Multithreaded playback integration prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;
import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Coordinates background playback sessions and presents their results on JavaFX.
 * A single lifecycle thread serialises session replacement and resource termination.
 * Generation checks prevent obsolete sessions publishing frames or state after a seek or stop.
 */
public class MultithreadedVideoPlayerModel implements PlaybackModel {
    private static final long NO_POSITION = -1;
    private static final long NO_HEADROOM = Long.MIN_VALUE;
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final int AUDIO_QUEUE_PACKETS = 64;
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final List<FrameEffect> effects;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("playback-lifecycle").factory());
    private final AtomicLong generation = new AtomicLong();
    private final List<Long> deferredSeekOffsets = new ArrayList<>();

    // Only the lifecycle thread accesses this reference and waits for its termination.
    private PlaybackSession ownedSession;
    // JavaFX owns these fields and never waits for background work.
    private PlaybackSession session;
    private PreparedFrame preparedFrame;
    private PlaybackClock videoOnlyClock;
    private File videoFile;
    private boolean playbackOpen;
    private boolean pauseRequested;
    private boolean previewRequested;
    private boolean audioOutputEnabled;
    private boolean metadataPublished;
    private volatile boolean shutdown;
    private long currentTimestampUs;
    private long logicalPlaybackBaseUs;
    private long relativeSeekBaseUs = NO_POSITION;
    private long deferredSeekBaseUs = NO_POSITION;
    private long firstVideoTimestampUs;
    private boolean firstVideoTimestampSet;
    private long videoDurationUs = NO_POSITION;
    private int videoFrameDurationUs;
    private long preparedHeadroomUs = NO_HEADROOM;

    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    /**
     * Creates the model and stores JavaFX callbacks.
     *
     * @param audioEnabled                   initial audio output state
     * @param frameEffects                   selected effects in command-line order
     * @param videoSizeChangedHandler        video size callback
     * @param frameReadyHandler              processed image callback
     * @param statusChangedHandler           status callback
     * @param playbackStateChangedHandler    playback state callback
     * @param audioOutputStateChangedHandler audio state callback
     */
    public MultithreadedVideoPlayerModel(
        boolean audioEnabled,
        List<FrameEffect> frameEffects,
        BiConsumer<Integer, Integer> videoSizeChangedHandler,
        Consumer<Image> frameReadyHandler,
        Consumer<String> statusChangedHandler,
        BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
        Consumer<Boolean> audioOutputStateChangedHandler
    ) {
        audioOutputEnabled = audioEnabled;
        effects = List.copyOf(frameEffects);
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
    }

    /**
     * @return whether audio output is enabled
     */
    @Override
    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    /**
     * @param file media file to open
     */
    @Override
    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_POSITION;
        videoFrameDurationUs = 0;
        deferredSeekBaseUs = NO_POSITION;
        deferredSeekOffsets.clear();
        startPlayback(0, false, NO_POSITION);
    }

    /**
     * Seeks to the beginning of the selected file.
     */
    @Override
    public void startOver() {
        if (videoFile != null) seekTo(0);
    }

    /**
     * Seeks five seconds backwards.
     */
    @Override
    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    /**
     * Seeks five seconds forwards.
     */
    @Override
    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    /**
     * Pauses or resumes the current session.
     */
    @Override
    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null)
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_POSITION;
        if (session != null) session.setPaused(pauseRequested || deferredSeekBaseUs != NO_POSITION);
        if (videoOnlyClock != null) videoOnlyClock.setPaused(pauseRequested, System.nanoTime());
        playbackStateChangedHandler.accept(true, pauseRequested);
    }

    /**
     * Restarts the session at the current logical position with the new audio state.
     */
    @Override
    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
        if (videoFile != null && playbackOpen) {
            long position = relativeSeekBaseUs != NO_POSITION ? relativeSeekBaseUs : currentTimestampUs;
            if (waitingForMetadata()) {
                if (deferredSeekBaseUs == NO_POSITION) deferredSeekBaseUs = position;
                if (session != null) session.setPaused(true);
                return;
            }
            startPlayback(displayableSeekTimestamp(position), pauseRequested, position);
        }
    }

    /**
     * Stops playback and clears the display.
     */
    @Override
    public void stopPlayback() {
        stopSession();
        currentTimestampUs = 0;
        pauseRequested = false;
        statusChangedHandler.accept("Stopped");
        frameReadyHandler.accept(null);
        playbackStateChangedHandler.accept(false, false);
    }

    /**
     * Requests shutdown without waiting on JavaFX.
     */
    @Override
    public void shutdown() {
        if (shutdown) return;
        stopSession();
        shutdown = true;
        lifecycle.shutdown();
    }

    /**
     * Seeks relative to the last requested or displayed position.
     *
     * @param offsetUs relative offset in microseconds
     */
    private void seekRelative(long offsetUs) {
        if (videoFile == null) return;
        long base = relativeSeekBaseUs != NO_POSITION ? relativeSeekBaseUs : currentTimestampUs;
        if (waitingForMetadata()) {
            if (deferredSeekBaseUs == NO_POSITION) deferredSeekBaseUs = base;
            deferredSeekOffsets.add(offsetUs);
            if (session != null) session.setPaused(true);
            return;
        }
        seekTo(base + offsetUs);
    }

    /**
     * Starts a new generation for a logical seek position.
     *
     * @param timestampUs requested logical position in microseconds
     */
    private void seekTo(long timestampUs) {
        if (waitingForMetadata()) {
            deferredSeekBaseUs = Math.max(0, timestampUs);
            deferredSeekOffsets.clear();
            if (session != null) session.setPaused(true);
            return;
        }
        long logical = clampSeekTimestamp(timestampUs);
        startPlayback(displayableSeekTimestamp(logical), pauseRequested, logical);
    }

    /**
     * Requests session replacement on the lifecycle thread.
     *
     * @param startTimestampUs  decoder seek position in microseconds
     * @param initiallyPaused   desired initial pause state
     * @param logicalPositionUs logical position, or NO_POSITION for a fresh start
     */
    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long logicalPositionUs) {
        if (shutdown) return;
        long id = generation.incrementAndGet();
        File file = videoFile;
        boolean audioEnabled = audioOutputEnabled;
        session = null;
        preparedFrame = null;
        videoOnlyClock = null;
        metadataPublished = false;
        firstVideoTimestampSet = false;
        preparedHeadroomUs = NO_HEADROOM;
        pauseRequested = initiallyPaused;
        previewRequested = initiallyPaused;
        currentTimestampUs = logicalPositionUs != NO_POSITION ? logicalPositionUs : startTimestampUs;
        logicalPlaybackBaseUs = currentTimestampUs;
        relativeSeekBaseUs = logicalPositionUs;
        playbackOpen = true;
        statusChangedHandler.accept(file.getName());
        playbackStateChangedHandler.accept(true, pauseRequested);
        playbackTimer.start();

        lifecycle.execute(() -> replaceSession(id, file, startTimestampUs, audioEnabled));
    }

    /**
     * Stops the previous session before starting a replacement.
     * Obsolete requests never publish a session to JavaFX.
     *
     * @param id           requested generation
     * @param file         media file to decode
     * @param timestampUs  decoder seek position in microseconds
     * @param audioEnabled whether this generation should play audio
     */
    private void replaceSession(long id, File file, long timestampUs, boolean audioEnabled) {
        PlaybackSession next = null;
        VideoDecoder video = null;
        AudioDecoder audioDecoder = null;
        AudioPreparation audioPreparation = null;
        try {
            // Skip obsolete seeks before waiting for an earlier session to stop.
            if (generation.get() != id || shutdown) return;
            if (!stopOwnedSession()) {
                reportFailure(id, new IllegalStateException("Previous playback did not terminate"));
                return;
            }
            if (generation.get() != id || shutdown) return;

            next = new PlaybackSession(effects);
            ownedSession = next;
            video = new VideoDecoder(new FFmpegFrameGrabber(file), mediaName(file));
            int workers = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
            int queueSize = Math.max(2, workers * 2);

            // Start paused so the UI can apply the latest pause state before playback begins.
            if (audioEnabled) {
                PlaybackClock clock = new PlaybackClock(0, System.nanoTime());
                AudioOutput output = new AudioOutput(clock, AUDIO_QUEUE_PACKETS);
                audioPreparation = new AudioPreparation(output);
                audioDecoder = new AudioDecoder(new FFmpegFrameGrabber(file));
                next.startWithAudio(video, audioDecoder, audioPreparation, timestampUs, true, workers, queueSize, id);
            } else {
                next.start(video, timestampUs, true, workers, queueSize, id);
            }

            if (generation.get() != id || shutdown) {
                stopOwnedSession();
                return;
            }
            PlaybackSession startedSession = next;
            Platform.runLater(() -> {
                if (generation.get() == id && !shutdown) {
                    session = startedSession;
                    startedSession.setPaused(pauseRequested || deferredSeekBaseUs != NO_POSITION);
                }
            });
        } catch (Exception | LinkageError failure) {
            if (next != null) next.requestStop();
            // A failed start leaves any unaccepted decoder owned by this caller.
            if (video != null && (next == null || !next.hasStarted())) closeUnaccepted(video, failure);
            if (audioDecoder != null && (audioPreparation == null || !audioPreparation.hasStarted())) {
                closeUnaccepted(audioDecoder, failure);
            }
            reportFailure(id, failure);
        }
    }

    /**
     * Closes a decoder that was not accepted by its owner thread.
     *
     * @param decoder unaccepted decoder
     * @param failure startup failure that should retain any close failure
     */
    private void closeUnaccepted(AutoCloseable decoder, Throwable failure) {
        try {
            decoder.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * Cancels and waits for the lifecycle-owned session.
     *
     * @return true when no previous session remains active
     * @throws InterruptedException if the lifecycle thread is interrupted
     */
    private boolean stopOwnedSession() throws InterruptedException {
        if (ownedSession == null) return true;
        ownedSession.requestStop();
        if (!ownedSession.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return false;
        ownedSession = null;
        return true;
    }

    /**
     * Invalidates callbacks and schedules session teardown.
     */
    private void stopSession() {
        generation.incrementAndGet();
        playbackTimer.stop();
        playbackOpen = false;
        session = null;
        preparedFrame = null;
        videoOnlyClock = null;
        deferredSeekBaseUs = NO_POSITION;
        deferredSeekOffsets.clear();
        if (!shutdown) {
            lifecycle.execute(() -> {
                try {
                    if (!stopOwnedSession()) {
                        System.err.println("Playback did not terminate within the timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    /**
     * Polls one result and presents it when due.
     *
     * @param now monotonic JavaFX pulse time in nanoseconds
     */
    private void pumpPlayback(long now) {
        PlaybackSession current = session;
        if (!playbackOpen || current == null) return;

        Throwable failure = current.getFailure();
        if (failure != null) {
            handleFailure(failure);
            return;
        }

        try {
            InfoVideo info = current.getVideoInfo();
            if (!metadataPublished && info != null) {
                metadataPublished = true;
                videoFrameDurationUs = info.frameDurationUs();
                long duration = current.getDurationUs();
                videoDurationUs = duration > 0 ? duration : NO_POSITION;
                videoSizeChangedHandler.accept(info.imageWidth(), info.imageHeight());
                if (deferredSeekBaseUs != NO_POSITION) {
                    long target = clampSeekTimestamp(deferredSeekBaseUs);
                    for (long offset : deferredSeekOffsets) target = clampSeekTimestamp(target + offset);
                    deferredSeekBaseUs = NO_POSITION;
                    deferredSeekOffsets.clear();
                    seekTo(target);
                    return;
                }
            }

            // Poll only when the one presentation slot is empty.
            if (preparedFrame == null) {
                PreparedFrame next = current.pollReady();
                if (next != null && next.generation() == generation.get()) {
                    preparedFrame = next;
                    if (!firstVideoTimestampSet) {
                        firstVideoTimestampUs = next.info().timestampUs();
                        firstVideoTimestampSet = true;
                    }
                    if (current.getPlaybackClock() == null && videoOnlyClock == null) {
                        videoOnlyClock = new PlaybackClock(next.info().timestampUs(), now);
                        videoOnlyClock.setPaused(pauseRequested, now);
                    }
                    PlaybackClock clock = activeClock(current);
                    preparedHeadroomUs = NO_HEADROOM;
                    if (clock != null) {
                        preparedHeadroomUs = (clock.targetTimeNs(next.info().timestampUs()) - next.preparedAtNs()) / 1_000L;
                    }
                }
            }

            PlaybackClock clock = activeClock(current);
            if (preparedFrame != null && (previewRequested || (clock != null && clock.isDue(preparedFrame.info().timestampUs(), now)))) {
                if (preparedHeadroomUs == NO_HEADROOM && clock != null) {
                    preparedHeadroomUs = (clock.targetTimeNs(preparedFrame.info().timestampUs()) - preparedFrame.preparedAtNs()) / 1_000L;
                }
                displayPreparedFrame(info);
            }

            // A polled frame is outside isDrained and must be presented first.
            if (preparedFrame == null && current.isDrained()) {
                failure = current.getFailure();
                if (failure != null) handleFailure(failure);
                else finishPlayback();
            }
        } catch (Exception failureDuringPlayback) {
            handleFailure(failureDuringPlayback);
        }
    }

    /**
     * Gets the initialised timing source for the active session.
     *
     * @param current active session
     * @return clock, or null while audio/video startup is incomplete
     */
    private PlaybackClock activeClock(PlaybackSession current) {
        if (current.getPlaybackClock() == null) return videoOnlyClock;
        return current.isStartupReady() ? current.getPlaybackClock() : null;
    }

    /**
     * Presents the pending frame and updates its logical seek position.
     *
     * @param videoInfo video metadata, or null before publication
     */
    private void displayPreparedFrame(InfoVideo videoInfo) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        previewRequested = false;
        currentTimestampUs = logicalPlaybackBaseUs + Math.max(0, frame.info().timestampUs() - firstVideoTimestampUs);
        relativeSeekBaseUs = NO_POSITION;
        if (preparedHeadroomUs != NO_HEADROOM && videoInfo != null && videoInfo.intFrameRate() > 0 && frame.info().frameNumber() % videoInfo.intFrameRate() == 0) {
            System.out.printf("Frame headroom is %dus out of %dus spare.%n", preparedHeadroomUs, videoFrameDurationUs);
        }
        frameReadyHandler.accept(frame.image());
    }

    /**
     * Finishes playback after video and audio have drained.
     */
    private void finishPlayback() {
        long end = videoDurationUs != NO_POSITION ? videoDurationUs : currentTimestampUs;
        stopSession();
        pauseRequested = true;
        currentTimestampUs = end;
        relativeSeekBaseUs = end;
        statusChangedHandler.accept("Playback finished");
        playbackStateChangedHandler.accept(false, true);
    }

    /**
     * Reports an error only if its generation is still current.
     *
     * @param id      generation associated with the failure
     * @param failure failure to report
     */
    private void reportFailure(long id, Throwable failure) {
        Platform.runLater(() -> {
            if (generation.get() == id && !shutdown) handleFailure(failure);
        });
    }

    /**
     * Stops playback and publishes an error.
     *
     * @param failure failure to report
     */
    private void handleFailure(Throwable failure) {
        stopSession();
        pauseRequested = false;
        statusChangedHandler.accept("Error: " + failure.getMessage());
        playbackStateChangedHandler.accept(false, false);
    }

    /**
     * Checks whether initial media metadata is still needed to clamp seek requests.
     *
     * @return true while playback is opening without a known duration
     */
    private boolean waitingForMetadata() {
        return playbackOpen && !metadataPublished && videoDurationUs == NO_POSITION;
    }

    /**
     * @param timestampUs requested position in microseconds
     * @return position clamped to the media duration
     */
    private long clampSeekTimestamp(long timestampUs) {
        long clamped = Math.max(0, timestampUs);
        return videoDurationUs == NO_POSITION ? clamped : Math.min(clamped, videoDurationUs);
    }

    /**
     * @param logicalTimestampUs logical position in microseconds
     * @return a position at which a frame can be decoded
     */
    private long displayableSeekTimestamp(long logicalTimestampUs) {
        if (videoDurationUs == NO_POSITION || logicalTimestampUs < videoDurationUs) return logicalTimestampUs;
        if (videoFrameDurationUs <= 0) return Math.max(0, videoDurationUs - 1);
        return Math.max(0, videoDurationUs - videoFrameDurationUs);
    }

    /**
     * @param file media file
     * @return filename without its extension
     */
    private String mediaName(File file) {
        String name = file.getName();
        int extension = name.lastIndexOf('.');
        return extension <= 0 ? name : name.substring(0, extension);
    }
}
