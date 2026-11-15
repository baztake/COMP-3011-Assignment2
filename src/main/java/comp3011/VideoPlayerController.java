/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Bailey Staykov, a1886621, in collaboration with GPT-6.1 Sol
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static comp3011.SubtitleAudioExtractor.CHUNK_DURATION_US;

/**
 * The controller part of the video player model-view-controller architecture.
 *
 * <p>
 * Wires up events from the {@link VideoPlayerView} to the {@link VideoPlayerModel}.
 * This includes button actions, keyboard shortcuts and display resizing.
 * </p>
 */
public class VideoPlayerController {

    private final PlaybackModel model;
    private final VideoPlayerView view;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    // JavaFX owns this state. Captions stay separate from decoded frames so toggles do not restart playback.
    private final Map<Long, SubtitleTrack> subtitleChunks = new HashMap<>();
    private final Set<Long> failedSubtitleChunks = new HashSet<>();
    private SubtitleTrack renderedTrack;
    private FrameSubtitler subtitler;
    private final SubtitleLoader subtitleLoader = new SubtitleLoader();
    private File subtitleFile;
    private long loadingChunkUs = -1;
    private long subtitleEndUs = Long.MAX_VALUE;
    private boolean subtitlesEnabled;
    private Image displayedFrame;
    private long displayedTimestampUs;

    public VideoPlayerController(
        boolean audioEnabled,
        List<FrameEffect> frameEffects,
        BiConsumer<Integer, Integer> videoSizeChangedHandler
    ) {
        this.view = new VideoPlayerView();
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        subtitlesEnabled = frameEffects.contains(FrameEffect.SUBTITLE);
        // Captions use a separate display layer above the existing effects.
        // Applying effects after subtitles in CLI order still needs to be integrated.
        List<FrameEffect> playbackEffects = frameEffects.stream()
            .filter(effect -> effect != FrameEffect.SUBTITLE).toList();
        String mode = System.getProperty("comp3011.playback", System.getenv().getOrDefault("COMP3011_PLAYBACK", "multi"));
        if (!mode.equalsIgnoreCase("single") && !mode.equalsIgnoreCase("multi")) {
            throw new IllegalArgumentException("comp3011.playback must be single or multi");
        }

        if (mode.equalsIgnoreCase("single")) {
            model = new VideoPlayerModel(
                audioEnabled,
                playbackEffects,
                this::onVideoSizeChanged,
                this::onFrameReady,
                this::onStatusChanged,
                this::onPlaybackStateChanged,
                this::onAudioOutputStateChanged
            );
        } else {
            model = new MultithreadedVideoPlayerModel(
                audioEnabled, playbackEffects, this::onVideoSizeChanged, this::onFrameReady,
                this::onStatusChanged, this::onPlaybackStateChanged, this::onAudioOutputStateChanged
            );
        }

        view.getStartOverButton().setOnAction(_ -> startOver());
        view.getBackFiveButton().setOnAction(_ -> backFiveSeconds());
        view.getPausePlayButton().setOnAction(_ -> togglePause());
        view.getForwardFiveButton().setOnAction(_ -> forwardFiveSeconds());
        view.getStopButton().setOnAction(_ -> stopAndExit());
        view.getAudioButton().setOnAction(_ -> toggleAudioOutput());
        view.showAudioEnabled(model.isAudioOutputEnabled());
        view.getSubtitleButton().setOnAction(_ -> toggleSubtitles());
        view.showSubtitlesEnabled(subtitlesEnabled);
    }

    public void installKeyboardShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stopAndExit();
                event.consume();
            } else if (event.getCode() == KeyCode.SPACE) {
                togglePause();
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT) {
                backFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT) {
                forwardFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.HOME) {
                startOver();
                event.consume();
            } else if (event.getCode() == KeyCode.A) {
                toggleAudioOutput();
                event.consume();
            } else if (event.getCode() == KeyCode.S) {
                toggleSubtitles();
                event.consume();
            }
        });
    }

    public VideoPlayerView getView() {
        return view;
    }

    public void play(File file) {
        subtitleLoader.cancel();
        subtitleFile = file;
        subtitleChunks.clear();
        failedSubtitleChunks.clear();
        renderedTrack = null;
        subtitler = null;
        loadingChunkUs = -1;
        subtitleEndUs = Long.MAX_VALUE;
        displayedTimestampUs = 0;
        view.setSubtitleStatus("");
        onFrameReady(null);
        view.setPlaybackControlsDisabled(false);
        model.play(file);
        requestSubtitles();
    }

    public void shutdown() {
        subtitleLoader.close();
        model.shutdown();
    }

    private void startOver() {
        model.startOver();
    }

    private void backFiveSeconds() {
        model.backFiveSeconds();
    }

    private void togglePause() {
        model.togglePause();
    }

    private void forwardFiveSeconds() {
        model.forwardFiveSeconds();
    }

    private void toggleAudioOutput() {
        model.toggleAudioOutput();
    }

    /** Shows or hides the caption layer without changing the displayed video frame. */
    private void toggleSubtitles() {
        // Let an already requested transcription finish when hidden, so enabling again can reuse paid work.
        subtitlesEnabled = !subtitlesEnabled;
        view.showSubtitlesEnabled(subtitlesEnabled);
        if (subtitlesEnabled) {
            failedSubtitleChunks.clear();
        }

        requestSubtitles();
        updateSubtitles();
    }

    /**
     * Loads the displayed chunk first, then keeps one chunk ahead ready when possible.
     * A seek replaces unrelated work, cached chunks stay available for backward seeking.
     */
    private void requestSubtitles() {
        if (!subtitlesEnabled || subtitleFile == null) {
            return;
        }

        long current = displayedTimestampUs / CHUNK_DURATION_US * CHUNK_DURATION_US;
        long target = subtitleChunks.containsKey(current) ? current + CHUNK_DURATION_US : current;
        if (loadingChunkUs == target) {
            return;
        }

        // Don't let an old prefetch hold up the chunk needed after a seek.
        if (loadingChunkUs != -1) {
            subtitleLoader.cancel();
            loadingChunkUs = -1;
        }

        if (target >= subtitleEndUs || subtitleChunks.containsKey(target)) {
            view.setSubtitleStatus("Subtitles ready");
            return;
        }

        if (failedSubtitleChunks.contains(target)) {
            view.setSubtitleStatus("Subtitles unavailable, toggle off and on to retry");
            return;
        }

        loadingChunkUs = target;
        view.setSubtitleStatus(target == current ? "Loading subtitles..." : "Loading next subtitles...");

        subtitleLoader.load(subtitleFile, target, chunk -> {
            loadingChunkUs = -1;
            subtitleChunks.put(target, chunk.track());
            if (chunk.last()) {
                subtitleEndUs = Math.min(subtitleEndUs, target + CHUNK_DURATION_US);
            }

            view.setSubtitleStatus("Subtitles ready");
            updateSubtitles();
            requestSubtitles();
        }, message -> {
            loadingChunkUs = -1;
            // Frames keep arriving after an error, wait for an explicit toggle before retrying this chunk.
            failedSubtitleChunks.add(target);
            view.setSubtitleStatus(message);
        });
    }

    /** Selects captions using the displayed frame's time, keeping only one rendered track in memory. */
    private void updateSubtitles() {
        Image caption = null;
        if (subtitlesEnabled && displayedFrame != null) {
            long current = displayedTimestampUs / CHUNK_DURATION_US * CHUNK_DURATION_US;
            SubtitleTrack track = subtitleChunks.get(current);
            if ((track == null || track.findCue(displayedTimestampUs) == null) && current >= CHUNK_DURATION_US) {
                // A final cue may extend slightly past its chunk to meet the minimum display duration.
                track = subtitleChunks.get(current - CHUNK_DURATION_US);
            }

            if (track != renderedTrack) {
                // Retain caption text for seeking, but keep rendered image memory limited to one track.
                renderedTrack = track;
                subtitler = track == null ? null : new FrameSubtitler(track);
            }

            if (subtitler != null) {
                caption = subtitler.captionImage((int) displayedFrame.getWidth(), (int) displayedFrame.getHeight(), displayedTimestampUs);
            }
        }

        view.setSubtitleImage(caption);
    }

    private void stopAndExit() {
        subtitleLoader.close();
        model.stopPlayback();
        javafx.application.Platform.exit();
    }

    private void onVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void onFrameReady(Image image) {
        displayedFrame = image;
        view.setImage(image);
        if (image != null) {
            // Save the time with the image: a seek can change the model's position before its next frame arrives.
            displayedTimestampUs = model.getCurrentTimestampUs();
            requestSubtitles();
        }

        updateSubtitles();
    }

    private void onStatusChanged(String status) {
        view.setStatus(status);
    }

    private void onPlaybackStateChanged(boolean playing, boolean paused) {
        if (playing && !paused) {
            view.showPauseAction();
        } else {
            view.showPlayAction();
        }
    }

    private void onAudioOutputStateChanged(boolean audioOutputEnabled) {
        view.showAudioEnabled(audioOutputEnabled);
    }
}
