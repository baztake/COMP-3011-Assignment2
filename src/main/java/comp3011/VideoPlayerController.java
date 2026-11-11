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
import java.util.List;
import java.util.function.BiConsumer;

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
    private final FrameSubtitler subtitler = new FrameSubtitler(SubtitleTrack.sample());
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
        // For this local milestone, captions use a separate display layer above the existing effects.
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
        onFrameReady(null);
        view.setPlaybackControlsDisabled(false);
        model.play(file);
    }

    public void shutdown() {
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
        subtitlesEnabled = !subtitlesEnabled;
        view.showSubtitlesEnabled(subtitlesEnabled);
        updateSubtitles();
    }

    private void updateSubtitles() {
        Image caption = null;
        if (subtitlesEnabled && displayedFrame != null) {
            caption = subtitler.captionImage((int) displayedFrame.getWidth(), (int) displayedFrame.getHeight(), displayedTimestampUs);
        }
        view.setSubtitleImage(caption);
    }

    private void stopAndExit() {
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
