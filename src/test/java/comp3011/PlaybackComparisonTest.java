/*
 * Playback comparison tests prepared by Bailey Staykov, a1886621,
 * with assistance from OpenAI Codex.
 */
package comp3011;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4;

class PlaybackComparisonTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int FRAME_COUNT = 120;
    private static final int FRAME_RATE = 60;

    /**
     * Compares the selectable single-threaded and multithreaded models
     * under an HD effect load.
     * <p>
     * Both models receive the same video and effect sequence. The test
     * compares complete output frames and reports playback measurements.
     * <p>
     * The test does not require a fixed speedup because the result depends
     * on the machine running it.
     *
     * @param directory directory for the generated video
     * @throws Exception if video creation, playback or comparison fails
     */
    @Test
    @Timeout(240)
    void shouldCompareSingleAndMultithreadedPlayback(@TempDir Path directory)
        throws Exception {
        // Assemble
        startJavaFx();

        File video = directory.resolve("hd-comparison.mp4").toFile();
        createVideo(video, FRAME_COUNT, false);

        // Both models receive the same sequence, including repeated effects.
        List<FrameEffect> effects = List.of(
            FrameEffect.SCRATCH,
            FrameEffect.FLICKER,
            FrameEffect.BLACK_AND_WHITE,
            FrameEffect.YELLOW,
            FrameEffect.VIGNETTE,
            FrameEffect.DUST,
            FrameEffect.JITTER,
            FrameEffect.MOTTLE,
            FrameEffect.BLEED,
            FrameEffect.PEPPER,

            FrameEffect.SCRATCH,
            FrameEffect.FLICKER,
            FrameEffect.BLACK_AND_WHITE,
            FrameEffect.YELLOW,
            FrameEffect.VIGNETTE,
            FrameEffect.DUST,
            FrameEffect.JITTER,
            FrameEffect.MOTTLE,
            FrameEffect.BLEED,
            FrameEffect.PEPPER
        );

        // Act
        PlaybackResult single = runPlayback(video, effects, false);
        PlaybackResult multi = runPlayback(video, effects, true);

        // Report measurements before assertions so failures still have context.
        printMeasurements("Single-threaded", single);
        printMeasurements("Multithreaded", multi);

        if (single.elapsedNs() > 0 && multi.elapsedNs() > 0) {
            double elapsedRatio =
                (double) single.elapsedNs() / multi.elapsedNs();

            System.out.printf(
                "Single elapsed time / multi elapsed time: %.2fx%n",
                elapsedRatio
            );
        }

        // Assert
        assertThat(single.error())
            .as("Single-threaded playback should not report an error")
            .isNull();

        assertThat(multi.error())
            .as("Multithreaded playback should not report an error")
            .isNull();

        assertThat(single.completed())
            .as("Single-threaded playback should complete")
            .isTrue();

        assertThat(multi.completed())
            .as("Multithreaded playback should complete")
            .isTrue();

        assertThat(single.frames())
            .as("Single-threaded playback should present every frame")
            .hasSize(FRAME_COUNT);

        assertThat(multi.frames())
            .as("Multithreaded playback should present every frame")
            .hasSize(FRAME_COUNT);

        // Compare every frame in presentation order.
        assertFrameDigestsEqual(single.frames(), multi.frames());
    }

    /**
     * Checks that requesting a new video prevents frames from the previous
     * playback from appearing after the handoff.
     * <p>
     * The test waits for the replacement video to finish before cleaning up.
     * This allows the decoder to close its file before JUnit removes the
     * temporary directory, which is required on Windows.
     *
     * @param directory directory for the generated videos
     * @throws Exception if video creation or playback coordination fails
     */
    @Test
    @Timeout(40)
    void shouldNotPresentOldFramesAfterPlaybackHandoff(@TempDir Path directory)
        throws Exception {
        // Assemble
        startJavaFx();

        File darkVideo = directory.resolve("dark.mp4").toFile();
        File brightVideo = directory.resolve("bright.mp4").toFile();

        createVideo(darkVideo, FRAME_COUNT, false);
        createVideo(brightVideo, FRAME_COUNT, true);

        CountDownLatch firstOldFrame = new CountDownLatch(1);
        CountDownLatch firstNewFrame = new CountDownLatch(1);
        CountDownLatch playbackFinished = new CountDownLatch(1);

        AtomicBoolean replacementRequested = new AtomicBoolean();
        AtomicBoolean oldFrameAfterReplacement = new AtomicBoolean();
        AtomicReference<String> error = new AtomicReference<>();

        Consumer<Image> imageHandler = image -> {
            if (image == null) {
                return;
            }

            int argb = image.getPixelReader().getArgb(0, 0);
            int red = (argb >>> 16) & 0xff;
            int green = (argb >>> 8) & 0xff;
            int blue = argb & 0xff;
            int brightness = (red + green + blue) / 3;

            if (!replacementRequested.get()) {
                firstOldFrame.countDown();
                return;
            }

            // The generated dark and bright videos are on opposite sides
            // of this threshold, even after video encoding and decoding.
            if (brightness < 128) {
                oldFrameAfterReplacement.set(true);
            } else {
                firstNewFrame.countDown();
            }
        };

        Consumer<String> statusHandler = status -> {
            if (status.startsWith("Error:")) {
                error.set(status);
                playbackFinished.countDown();
            } else if (status.equals("Playback finished")) {
                playbackFinished.countDown();
            }
        };

        PlaybackModel model = new MultithreadedVideoPlayerModel(
            false,
            List.of(FrameEffect.BLACK_AND_WHITE),
            (width, height) -> {
            },
            imageHandler,
            statusHandler,
            (open, paused) -> {
            },
            enabled -> {
            }
        );

        try {
            // Act
            runOnJavaFx(() -> model.play(darkVideo));

            boolean displayedOldFrame = firstOldFrame.await(
                8,
                TimeUnit.SECONDS
            );

            assertThat(displayedOldFrame)
                .as("The first video should start playing")
                .isTrue();

            // Set the flag and request the replacement in one JavaFX action.
            // Any subsequent image callback is checked against the new video.
            runOnJavaFx(() -> {
                replacementRequested.set(true);
                model.play(brightVideo);
            });

            boolean displayedNewFrame = firstNewFrame.await(
                8,
                TimeUnit.SECONDS
            );

            boolean finished = playbackFinished.await(
                10,
                TimeUnit.SECONDS
            );

            // Assert
            assertThat(displayedNewFrame)
                .as("The replacement video should display a frame")
                .isTrue();

            assertThat(finished)
                .as("The replacement playback should finish")
                .isTrue();

            assertThat(error.get())
                .as("Playback should not report an error")
                .isNull();

            assertThat(oldFrameAfterReplacement.get())
                .as("No frame from the old video should appear after replacement")
                .isFalse();
        } finally {
            runOnJavaFx(model::shutdown);
        }
    }

    /**
     * Checks that worker count does not change processed frame order or pixels.
     *
     * @param directory directory for the generated video
     * @throws Exception if video creation, decoding or processing fails
     */
    @Test
    @Timeout(90)
    void shouldProduceSameFramesWithDifferentWorkerCounts(@TempDir Path directory)
        throws Exception {
        // Assemble
        startJavaFx();

        File video = directory.resolve("workers.mp4").toFile();
        createVideo(video, FRAME_COUNT, false);

        List<FrameEffect> effects = List.of(
            FrameEffect.FLICKER,
            FrameEffect.BLACK_AND_WHITE,
            FrameEffect.FLICKER,
            FrameEffect.DUST
        );

        // Act
        SessionResult oneWorker = runSession(video, effects, 1);
        SessionResult twoWorkers = runSession(video, effects, 2);
        SessionResult fourWorkers = runSession(video, effects, 4);

        // Assert
        assertThat(oneWorker.frameNumbers()).hasSize(FRAME_COUNT);
        assertThat(twoWorkers.frameNumbers()).containsExactlyElementsOf(
            oneWorker.frameNumbers()
        );
        assertThat(fourWorkers.frameNumbers()).containsExactlyElementsOf(
            oneWorker.frameNumbers()
        );

        assertFrameDigestsEqual(oneWorker.frames(), twoWorkers.frames());
        assertFrameDigestsEqual(oneWorker.frames(), fourWorkers.frames());
    }

    /**
     * Creates a short video with deterministic pixels.
     *
     * @param video      destination video
     * @param frameCount number of frames to record
     * @param bright     whether to create a bright video for handoff testing
     * @throws Exception if recording fails
     */
    private void createVideo(File video, int frameCount, boolean bright)
        throws Exception {
        try (FFmpegFrameRecorder recorder =
                 new FFmpegFrameRecorder(video, WIDTH, HEIGHT);
             Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3)) {

            recorder.setFormat("mp4");
            recorder.setVideoCodec(AV_CODEC_ID_MPEG4);
            recorder.setFrameRate(FRAME_RATE);
            recorder.start();

            ByteBuffer pixels = (ByteBuffer) frame.image[0];

            for (int index = 0; index < frameCount; index++) {
                int value = bright ? 220 : 20 + index % 40;

                for (int position = 0; position < pixels.capacity(); position++) {
                    pixels.put(position, (byte) value);
                }

                recorder.record(frame);
            }

            recorder.stop();
        }
    }

    /**
     * Runs one selectable model and captures its presented images and timing.
     * <p>
     * Presentation lateness is measured relative to the first presented frame.
     * This excludes startup time from the lateness calculation. Total elapsed
     * time still includes startup.
     *
     * @param video         input video
     * @param effects       effects in command-line order
     * @param multithreaded whether to run the multithreaded model
     * @return playback output and measurements
     * @throws Exception if playback coordination fails
     */
    private PlaybackResult runPlayback(
        File video,
        List<FrameEffect> effects,
        boolean multithreaded
    ) throws Exception {
        List<byte[]> frames = new CopyOnWriteArrayList<>();
        List<Long> presentationTimes = new CopyOnWriteArrayList<>();

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();

        Consumer<Image> imageHandler = image -> {
            if (image != null) {
                // Capture the callback time before hashing the frame.
                presentationTimes.add(System.nanoTime());
                frames.add(digestImage(image));
            }
        };

        Consumer<String> statusHandler = status -> {
            if (status.startsWith("Error:")) {
                error.set(status);
                finished.countDown();
            } else if (status.equals("Playback finished")) {
                finished.countDown();
            }
        };

        PlaybackModel model = multithreaded
            ? new MultithreadedVideoPlayerModel(
            false,
            effects,
            (width, height) -> {
            },
            imageHandler,
            statusHandler,
            (open, paused) -> {
            },
            enabled -> {
            }
        )
            : new VideoPlayerModel(
            false,
            effects,
            (width, height) -> {
            },
            imageHandler,
            statusHandler,
            (open, paused) -> {
            },
            enabled -> {
            }
        );

        long startedNs = System.nanoTime();

        try {
            runOnJavaFx(() -> model.play(video));

            boolean signalled = finished.await(90, TimeUnit.SECONDS);
            long elapsedNs = System.nanoTime() - startedNs;

            boolean completed = signalled && error.get() == null;

            return new PlaybackResult(
                List.copyOf(frames),
                List.copyOf(presentationTimes),
                elapsedNs,
                completed,
                error.get()
            );
        } finally {
            runOnJavaFx(model::shutdown);
        }
    }

    /**
     * Runs a video-only session with a specified worker count.
     *
     * @param video       input video
     * @param effects     effects in command-line order
     * @param workerCount number of processing workers
     * @return frame numbers and complete-image digests
     * @throws Exception if decoding, processing or termination fails
     */
    private SessionResult runSession(
        File video,
        List<FrameEffect> effects,
        int workerCount
    ) throws Exception {
        PlaybackSession session = new PlaybackSession(effects);

        VideoDecoder decoder = new VideoDecoder(
            new FFmpegFrameGrabber(video),
            "workers"
        );

        List<Integer> frameNumbers = new ArrayList<>();
        List<byte[]> frames = new ArrayList<>();

        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);

        try {
            session.start(
                decoder,
                0,
                false,
                workerCount,
                Math.max(2, workerCount * 2),
                1
            );

            while (System.nanoTime() < deadlineNs) {
                Throwable failure = session.getFailure();

                if (failure != null) {
                    throw new AssertionError("Session failed", failure);
                }

                PreparedFrame frame = session.pollReady();

                if (frame != null) {
                    frameNumbers.add(frame.info().frameNumber());
                    frames.add(digestImage(frame.image()));
                    continue;
                }

                if (session.isDrained()) {
                    break;
                }

                Thread.sleep(2);
            }

            assertThat(session.isDrained())
                .as("Session should finish before the deadline")
                .isTrue();

            assertThat(session.getFailure()).isNull();

            return new SessionResult(
                List.copyOf(frameNumbers),
                List.copyOf(frames)
            );
        } finally {
            session.requestStop();

            assertThat(session.awaitTermination(5, TimeUnit.SECONDS))
                .as("Session should terminate")
                .isTrue();
        }
    }

    /**
     * Computes a digest using the dimensions and every ARGB pixel.
     *
     * @param image image to inspect
     * @return SHA-256 digest of the image contents
     */
    private byte[] digestImage(Image image) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            int width = (int) image.getWidth();
            int height = (int) image.getHeight();

            updateDigest(digest, width);
            updateDigest(digest, height);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    updateDigest(
                        digest,
                        image.getPixelReader().getArgb(x, y)
                    );
                }
            }

            return digest.digest();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not digest image", failure);
        }
    }

    /**
     * Adds an integer to a digest in a consistent byte order.
     *
     * @param digest digest being updated
     * @param value  integer to add
     */
    private void updateDigest(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    /**
     * Checks that two ordered frame sequences contain the same image digests.
     *
     * @param expected expected frame digests
     * @param actual   actual frame digests
     */
    private void assertFrameDigestsEqual(
        List<byte[]> expected,
        List<byte[]> actual
    ) {
        assertThat(actual).hasSameSizeAs(expected);

        for (int index = 0; index < expected.size(); index++) {
            assertThat(Arrays.equals(expected.get(index), actual.get(index)))
                .as("Complete frame at index %d should match", index)
                .isTrue();
        }
    }

    /**
     * Reports playback time and presentation lateness.
     * <p>
     * A frame is counted as late when its callback occurs more than one
     * frame duration after its expected presentation time. Expected times
     * are calculated relative to the first presented frame.
     *
     * @param label  playback mode
     * @param result captured playback result
     */
    private void printMeasurements(String label, PlaybackResult result) {
        double elapsedSeconds = result.elapsedNs() / 1_000_000_000.0;

        double overallFps = elapsedSeconds > 0
            ? result.frames().size() / elapsedSeconds
            : 0;

        List<Long> times = result.presentationTimes();

        double presentationFps = 0;
        int lateFrames = 0;
        long maxLatenessNs = 0;

        if (times.size() > 1) {
            long firstNs = times.getFirst();
            long lastNs = times.getLast();
            long presentationDurationNs = lastNs - firstNs;

            if (presentationDurationNs > 0) {
                presentationFps = (times.size() - 1)
                    / (presentationDurationNs / 1_000_000_000.0);
            }

            long frameDurationNs = 1_000_000_000L / FRAME_RATE;

            for (int index = 0; index < times.size(); index++) {
                long expectedNs = firstNs + index * frameDurationNs;
                long latenessNs = Math.max(0, times.get(index) - expectedNs);

                if (latenessNs > frameDurationNs) {
                    lateFrames++;
                }

                maxLatenessNs = Math.max(maxLatenessNs, latenessNs);
            }
        }

        System.out.printf(
            "%s: %d frames, %.3f s total, %.2f overall fps, "
                + "%.2f presentation fps, %d late callbacks, "
                + "%.2f ms maximum lateness%n",
            label,
            result.frames().size(),
            elapsedSeconds,
            overallFps,
            presentationFps,
            lateFrames,
            maxLatenessNs / 1_000_000.0
        );
    }

    /**
     * Starts the JavaFX toolkit if another test has not already started it.
     *
     * @throws Exception if the toolkit fails to start
     */
    private void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);

        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }

        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX did not start");
        }

        Platform.setImplicitExit(false);
    }

    /**
     * Runs an action on JavaFX and waits from the test thread.
     *
     * @param action action to execute
     * @throws Exception if execution or waiting fails
     */
    private void runOnJavaFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(5, TimeUnit.SECONDS);
    }

    /**
     * Captured output from one playback model.
     *
     * @param frames            ordered frame digests
     * @param presentationTimes callback times for presented frames
     * @param elapsedNs         total elapsed playback time, including startup
     * @param completed         whether playback completed successfully
     * @param error             reported error, or null
     */
    private record PlaybackResult(
        List<byte[]> frames,
        List<Long> presentationTimes,
        long elapsedNs,
        boolean completed,
        String error
    ) {
    }

    /**
     * Captured output from one processing session.
     *
     * @param frameNumbers frame numbers in result order
     * @param frames       ordered frame digests
     */
    private record SessionResult(
        List<Integer> frameNumbers,
        List<byte[]> frames
    ) {
    }
}