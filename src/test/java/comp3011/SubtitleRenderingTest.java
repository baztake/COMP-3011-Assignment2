/* Subtitle tests prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. */
package comp3011;

import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SubtitleRenderingTest {
    @Test
    void shouldDrawNativeFramesOnlyDuringTheCue() {
        // Assemble
        FrameSubtitler renderer = new FrameSubtitler(new SubtitleTrack(List.of(
            new SubtitleCue(1_000_000, 3_000_000, "Native subtitle processor.")
        )));
        try (Frame frame = new Frame(320, 180, Frame.DEPTH_UBYTE, 3)) {
            java.nio.ByteBuffer pixels = (java.nio.ByteBuffer) frame.image[0];
            for (int index = 0; index < pixels.capacity(); index++) pixels.put(index, (byte) 64);

            // Act and Assert
            renderer.process(frame, new InfoFrame(0, 0));
            for (int index = 0; index < pixels.capacity(); index++) assertThat(pixels.get(index)).isEqualTo((byte) 64);
            renderer.process(frame, new InfoFrame(24, 1_000_000));
            int changed = 0;
            for (int index = 0; index < pixels.capacity(); index++) {
                if (pixels.get(index) != (byte) 64) changed++;
            }
            assertThat(changed).isGreaterThan(0);
            assertThat(pixels.get(0)).isEqualTo((byte) 64);
        }
    }

    @Test
    @Timeout(15)
    void shouldReuseTransparentCaptionsUntilTheCueOrSizeChanges() throws Exception {
        // Assemble
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Platform.setImplicitExit(false);
        FutureTask<Void> test = new FutureTask<>(() -> {
            FrameSubtitler renderer = new FrameSubtitler(new SubtitleTrack(List.of(
                new SubtitleCue(0, 2_000_000, "White text with a black outline."),
                new SubtitleCue(3_000_000, 5_000_000, "A different cue.")
            )));

            // Act
            Image caption = renderer.captionImage(640, 360, 0);

            // Assert
            assertThat(caption.getPixelReader().getArgb(0, 0)).isZero();
            int whitePixels = 0;
            int blackPixels = 0;
            for (int y = 0; y < caption.getHeight(); y++) {
                for (int x = 0; x < caption.getWidth(); x++) {
                    int pixel = caption.getPixelReader().getArgb(x, y);
                    if (pixel == 0xffffffff) whitePixels++;
                    if (pixel == 0xff000000) blackPixels++;
                }
            }
            assertThat(whitePixels).isGreaterThan(0);
            assertThat(blackPixels).isGreaterThan(0);
            assertThat(renderer.captionImage(640, 360, 500_000)).isSameAs(caption);
            assertThat(renderer.captionImage(640, 360, 2_000_000)).isNull();
            assertThat(renderer.captionImage(640, 360, 1_000_000)).isSameAs(caption);
            assertThat(renderer.captionImage(640, 360, 3_000_000)).isNotSameAs(caption);
            Image resized = renderer.captionImage(320, 180, 3_000_000);
            assertThat(resized.getWidth()).isEqualTo(320);
            assertThat(resized.getHeight()).isEqualTo(180);
            return null;
        });
        Platform.runLater(test);
        test.get(5, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(30)
    void shouldToggleThePausedDisplayedFrameInBothModes(@TempDir Path directory) throws Exception {
        // Assemble
        Path video = directory.resolve("subtitles.mp4");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(video.toFile(), 320, 180);
             Frame frame = new Frame(320, 180, Frame.DEPTH_UBYTE, 3)) {
            recorder.setFormat("mp4");
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(24);
            recorder.start();
            java.nio.ByteBuffer pixels = (java.nio.ByteBuffer) frame.image[0];
            for (int index = 0; index < pixels.capacity(); index++) pixels.put(index, (byte) 64);
            for (int index = 0; index < 96; index++) recorder.record(frame);
            recorder.stop();
        }
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Platform.setImplicitExit(false);
        String previousMode = System.getProperty("comp3011.playback");
        try {
            for (String mode : List.of("single", "multi")) {
                System.setProperty("comp3011.playback", mode);
                CountDownLatch checked = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicReference<VideoPlayerController> active = new AtomicReference<>();
                AtomicReference<AnimationTimer> monitor = new AtomicReference<>();
                FutureTask<Void> start = new FutureTask<>(() -> {
                    VideoPlayerController controller = new VideoPlayerController(false,
                        List.of(FrameEffect.SUBTITLE), (width, height) -> {});
                    active.set(controller);
                    StackPane root = (StackPane) controller.getView().getRoot();
                    BorderPane content = (BorderPane) root.getChildren().getFirst();
                    StackPane videoPane = (StackPane) content.getCenter();
                    ImageView display = (ImageView) videoPane.getChildren().getFirst();
                    ImageView captions = (ImageView) videoPane.getChildren().get(1);
                    Scene scene = new Scene(root);
                    controller.installKeyboardShortcuts(scene);
                    AnimationTimer timer = new AnimationTimer() {
                        @Override
                        public void handle(long now) {
                            if (display.getImage() == null) return;
                            stop();
                            try {
                                // Act: pause, remove using the button, restore using the hotkey.
                                controller.getView().getPausePlayButton().fire();
                                Image frame = display.getImage();
                                Image caption = captions.getImage();
                                assertThat(caption).isNotNull();
                                controller.getView().getSubtitleButton().fire();
                                assertThat(captions.getImage()).isNull();
                                assertThat(display.getImage()).isSameAs(frame);
                                javafx.event.Event.fireEvent(scene, new KeyEvent(KeyEvent.KEY_PRESSED,
                                    "s", "s", KeyCode.S, false, false, false, false));

                                // Assert: toggling only restores the caption layer, even while paused.
                                assertThat(captions.getImage()).isSameAs(caption);
                                assertThat(display.getImage()).isSameAs(frame);
                                // Match the video's fitted bounds after resizing, including letterboxing.
                                root.resize(800, 600);
                                root.applyCss();
                                root.layout();
                                assertThat(captions.getBoundsInParent()).isEqualTo(display.getBoundsInParent());
                                assertThat(controller.getView().getSubtitleButton().getAccessibleText())
                                    .isEqualTo("Subtitles on");
                            } catch (Throwable error) {
                                failure.set(error);
                            } finally {
                                controller.shutdown();
                                checked.countDown();
                            }
                        }
                    };
                    monitor.set(timer);
                    timer.start();
                    controller.play(video.toFile());
                    return null;
                });
                Platform.runLater(start);
                start.get(5, TimeUnit.SECONDS);
                try {
                    assertThat(checked.await(8, TimeUnit.SECONDS)).as("First frame in %s mode", mode).isTrue();
                    assertThat(failure.get()).isNull();
                } finally {
                    FutureTask<Void> cleanup = new FutureTask<>(() -> {
                        monitor.get().stop();
                        active.get().shutdown();
                        return null;
                    });
                    Platform.runLater(cleanup);
                    cleanup.get(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            if (previousMode == null) System.clearProperty("comp3011.playback");
            else System.setProperty("comp3011.playback", previousMode);
            // Multi-mode shutdown is asynchronous. Wait off JavaFX for Windows to release the media handle.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (java.nio.file.Files.exists(video)) {
                try {
                    java.nio.file.Files.delete(video);
                } catch (java.io.IOException stillClosing) {
                    if (System.nanoTime() >= deadline) throw stillClosing;
                    Thread.sleep(20);
                }
            }
        }
    }

}
