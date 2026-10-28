/*
 * Playback mode regression test prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import javafx.application.Platform;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4;

class PlaybackModesTest {

    /**
     * Checks both modes can display, pause, preview after a seek, resume and reach EOF.
     *
     * @param directory directory for the temporary video
     * @throws Exception if video creation or playback coordination fails
     */
    @Test
    @Timeout(30)
    void shouldPreservePlaybackControlsInBothModes(@TempDir Path directory) throws Exception {
        // Assemble
        File video = directory.resolve("test.mp4").toFile();
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(video, 64, 48);
             Frame frame = new Frame(64, 48, Frame.DEPTH_UBYTE, 3)) {
            recorder.setFormat("mp4");
            recorder.setVideoCodec(AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(24);
            recorder.start();
            ByteBuffer pixels = (ByteBuffer) frame.image[0];
            for (int index = 0; index < 48; index++) {
                for (int position = 0; position < pixels.capacity(); position++) {
                    pixels.put(position, (byte) (index * 5));
                }
                recorder.record(frame);
            }
            recorder.stop();
        }

        CountDownLatch toolkit = new CountDownLatch(1);
        try {
            Platform.startup(toolkit::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(toolkit::countDown);
        }
        if (!toolkit.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX did not start");
        }
        Platform.setImplicitExit(false);

        for (boolean multithreaded : new boolean[]{false, true}) {
            CountDownLatch firstFrame = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            CountDownLatch replayFrame = new CountDownLatch(1);
            AtomicInteger frames = new AtomicInteger();
            AtomicInteger firstPixel = new AtomicInteger(Integer.MIN_VALUE);
            AtomicInteger replayPixel = new AtomicInteger();
            AtomicBoolean captureReplay = new AtomicBoolean();
            AtomicReference<String> error = new AtomicReference<>();
            AtomicBoolean wrongThread = new AtomicBoolean();
            BiConsumer<Integer, Integer> size = (width, height) -> {
                if (!Platform.isFxApplicationThread()) wrongThread.set(true);
            };
            Consumer<javafx.scene.image.Image> image = result -> {
                if (!Platform.isFxApplicationThread()) wrongThread.set(true);
                if (result != null) {
                    int pixel = result.getPixelReader().getArgb(0, 0);
                    firstPixel.compareAndSet(Integer.MIN_VALUE, pixel);
                    if (captureReplay.compareAndSet(true, false)) {
                        replayPixel.set(pixel);
                        replayFrame.countDown();
                    }
                    frames.incrementAndGet();
                    firstFrame.countDown();
                }
            };
            Consumer<String> status = value -> {
                if (!Platform.isFxApplicationThread()) wrongThread.set(true);
                if (value.startsWith("Error:")) error.set(value);
                if (value.equals("Playback finished")) finished.countDown();
            };
            BiConsumer<Boolean, Boolean> state = (open, paused) -> {
                if (!Platform.isFxApplicationThread()) wrongThread.set(true);
            };
            Consumer<Boolean> audio = enabled -> {
                if (!Platform.isFxApplicationThread()) wrongThread.set(true);
            };
            PlaybackModel model = multithreaded
                ? new MultithreadedVideoPlayerModel(false, List.of(FrameEffect.BLACK_AND_WHITE), size, image, status, state, audio)
                : new VideoPlayerModel(false, List.of(FrameEffect.BLACK_AND_WHITE), size, image, status, state, audio);

            try {
                // Act
                runOnJavaFx(() -> model.play(video));
                boolean displayed = firstFrame.await(5, TimeUnit.SECONDS);
                runOnJavaFx(model::togglePause);
                int pausedFrames = frames.get();
                Thread.sleep(150);
                int framesWhilePaused = frames.get();
                // Rapid seeks must leave the latest requested paused preview visible.
                runOnJavaFx(() -> {
                    model.forwardFiveSeconds();
                    model.backFiveSeconds();
                    model.startOver();
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (frames.get() == pausedFrames && System.nanoTime() < deadline) Thread.sleep(5);
                boolean preview = frames.get() > pausedFrames;
                Thread.sleep(150);
                int pausedPreviewFrames = frames.get();

                // Toggle audio on a video without an audio track while paused.
                runOnJavaFx(model::toggleAudioOutput);
                boolean audioEnabled = model.isAudioOutputEnabled();
                runOnJavaFx(model::togglePause);
                boolean completed = finished.await(6, TimeUnit.SECONDS);

                // Request several seeks immediately after reopening the short video.
                captureReplay.set(true);
                runOnJavaFx(() -> {
                    model.play(video);
                    model.forwardFiveSeconds();
                    model.forwardFiveSeconds();
                    model.backFiveSeconds();
                });
                boolean replayed = replayFrame.await(5, TimeUnit.SECONDS);
                runOnJavaFx(model::stopPlayback);
                int stoppedFrames = frames.get();
                Thread.sleep(150);
                int framesAfterStop = frames.get();

                // Assert
                assertThat(displayed).isTrue();
                assertThat(framesWhilePaused).isEqualTo(pausedFrames);
                assertThat(preview).isTrue();
                assertThat(pausedPreviewFrames).isEqualTo(pausedFrames + 1);
                assertThat(audioEnabled).isTrue();
                assertThat(completed).isTrue();
                assertThat(replayed).isTrue();
                assertThat(replayPixel.get()).isEqualTo(firstPixel.get());
                assertThat(framesAfterStop).isEqualTo(stoppedFrames);
                assertThat(error.get()).isNull();
                assertThat(wrongThread).isFalse();
            } finally {
                runOnJavaFx(model::shutdown);
            }
        }
    }

    /**
     * Compares every pixel of the frames presented by both modes using the same video and effects.
     *
     * @param directory directory for the temporary video
     * @throws Exception if video creation or playback coordination fails
     */
    @Test
    @Timeout(30)
    void shouldPresentTheSameFramesInBothModes(@TempDir Path directory) throws Exception {
        // Assemble
        File video = directory.resolve("comparison.mp4").toFile();

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(video, 64, 48);
             Frame frame = new Frame(64, 48, Frame.DEPTH_UBYTE, 3)) {
            recorder.setFormat("mp4");
            recorder.setVideoCodec(AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(24);
            recorder.start();

            ByteBuffer pixels = (ByteBuffer) frame.image[0];

            for (int index = 0; index < 48; index++) {
                for (int position = 0; position < pixels.capacity(); position++) {
                    pixels.put(position, (byte) (index * 5));
                }
                recorder.record(frame);
            }

            recorder.stop();
        }

        CountDownLatch toolkit = new CountDownLatch(1);

        try {
            Platform.startup(toolkit::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(toolkit::countDown);
        }

        if (!toolkit.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX did not start");
        }

        Platform.setImplicitExit(false);

        List<List<List<Integer>>> outputs = new ArrayList<>();
        List<Boolean> completions = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Repeated flicker checks that separate occurrences produce matching output.
        List<FrameEffect> effects = List.of(
            FrameEffect.FLICKER,
            FrameEffect.BLACK_AND_WHITE,
            FrameEffect.FLICKER
        );

        // Act
        for (boolean multithreaded : new boolean[]{false, true}) {
            List<List<Integer>> frames = new CopyOnWriteArrayList<>();
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<String> error = new AtomicReference<>();

            Consumer<javafx.scene.image.Image> image = result -> {
                if (result == null) {
                    return;
                }

                // Copy every pixel while the image is available on the JavaFX thread.
                List<Integer> framePixels = new ArrayList<>();

                for (int y = 0; y < (int) result.getHeight(); y++) {
                    for (int x = 0; x < (int) result.getWidth(); x++) {
                        framePixels.add(result.getPixelReader().getArgb(x, y));
                    }
                }

                frames.add(framePixels);
            };

            Consumer<String> status = value -> {
                if (value.startsWith("Error:")) {
                    error.set(value);
                }
                if (value.equals("Playback finished")) {
                    finished.countDown();
                }
            };

            PlaybackModel model = multithreaded
                ? new MultithreadedVideoPlayerModel(
                false,
                effects,
                (width, height) -> {},
                image,
                status,
                (open, paused) -> {},
                enabled -> {}
            )
                : new VideoPlayerModel(
                false,
                effects,
                (width, height) -> {},
                image,
                status,
                (open, paused) -> {},
                enabled -> {}
            );

            try {
                runOnJavaFx(() -> model.play(video));

                boolean completed = finished.await(6, TimeUnit.SECONDS);
                completions.add(completed);
                errors.add(error.get());
                outputs.add(List.copyOf(frames));
            } finally {
                runOnJavaFx(model::shutdown);
            }
        }

        // Assert
        assertThat(completions).containsExactly(true, true);
        assertThat(errors).containsOnlyNulls();
        assertThat(outputs.getFirst()).hasSize(48);
        assertThat(outputs.getLast()).containsExactlyElementsOf(outputs.getFirst());
    }

    /**
     * Runs a short action on JavaFX and waits from the test thread.
     *
     * @param action action to execute
     * @throws Exception if execution or waiting fails
     */
    private void runOnJavaFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(5, TimeUnit.SECONDS);
    }
}
