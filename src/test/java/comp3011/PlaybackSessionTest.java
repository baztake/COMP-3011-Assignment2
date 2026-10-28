/*
 * Session composition regression test prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;
import static org.mockito.Mockito.*;

class PlaybackSessionTest {

    /**
     * Checks that the decoder thread processes a copied frame and preserves its metadata.
     *
     * @throws Exception if processing, result retrieval or cleanup fails
     */
    @Test
    @Timeout(10)
    void shouldDecodeAndProcessFrame() throws Exception {
        // Assemble
        PlaybackSession session = new PlaybackSession(List.of(FrameEffect.BLACK_AND_WHITE));
        InfoFrame info = new InfoFrame(7, 250_000);
        VideoDecoder decoder = mock(VideoDecoder.class);

        try (Frame input = new Frame(1, 1, Frame.DEPTH_UBYTE, 3)) {
            // JavaCV supplies decoded image pixels in BGR order.
            ByteBuffer pixels = (ByteBuffer) input.image[0];
            pixels.put(0, (byte) 10);
            pixels.put(1, (byte) 20);
            pixels.put(2, (byte) 30);

            InfoVideo videoInfo = new InfoVideo(
                "test", 1, 1, 1,
                input.imageDepth, input.imageChannels, input.imageStride,
                24.0, 24, 41_667, AV_PIX_FMT_BGR24
            );
            // Mocking the decoder output: one borrowed frame, followed by end of video.
            when(decoder.read()).thenReturn(new VideoDecoder.DecodedFrame(input, info)).thenReturn(null);
            when(decoder.videoInfo()).thenReturn(videoInfo);

            try {
                // Act
                session.start(decoder, 0, false, 1, 1, 3);

                // Wait for the result. The test timeout prevents an endless wait.
                PreparedFrame result;
                while ((result = session.pollReady()) == null) {
                    Thread.sleep(1);
                }

                // EOF allows accepted work to finish without discarding results.
                boolean terminated = session.awaitTermination(3, TimeUnit.SECONDS);

                // Assert
                // Ensure the selected effect ran and the frame identity was preserved.
                assertThat(result.image().getPixelReader().getArgb(0, 0))
                    .isEqualTo(0xFF151515);
                assertThat(result.info()).isEqualTo(info);
                assertThat(result.generation()).isEqualTo(3);
                assertThat(session.getVideoInfo()).isEqualTo(videoInfo);
                assertThat(session.getFailure()).isNull();
                assertThat(terminated).isTrue();
                verify(decoder).open(0);
                verify(decoder).close();
            } finally {
                // Stop all session threads even when the test fails or times out.
                session.requestStop();
                session.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Checks that stopping releases accepted and pending copies when the queue is full.
     *
     * @throws Exception if processing or test coordination fails
     */
    @Test
    @Timeout(10)
    void shouldStopWithFullQueueAndReleaseCopies() throws Exception {
        // Assemble
        PlaybackSession session = new PlaybackSession(List.of());
        VideoDecoder decoder = mock(VideoDecoder.class);
        Frame firstInput = mock(Frame.class);
        Frame secondInput = mock(Frame.class);
        // Valid image copies can be processed; spies let us check each close.
        Frame firstCopy = spy(new Frame(1, 1, Frame.DEPTH_UBYTE, 3));
        Frame secondCopy = spy(new Frame(1, 1, Frame.DEPTH_UBYTE, 3));
        CountDownLatch secondCopied = new CountDownLatch(1);

        // Mocking two borrowed frames and their independent copies.
        when(decoder.read()).thenReturn(
            new VideoDecoder.DecodedFrame(firstInput, new InfoFrame(1, 0)),
            new VideoDecoder.DecodedFrame(secondInput, new InfoFrame(2, 40_000))
        ).thenReturn(null);
        when(decoder.videoInfo()).thenReturn(mock(InfoVideo.class));
        when(firstInput.clone()).thenReturn(firstCopy);
        when(secondInput.clone()).thenAnswer(_ -> {
            secondCopied.countDown();
            return secondCopy;
        });

        try {
            // Act
            session.start(decoder, 0, false, 1, 1, 3);

            // No polling frees space, so the second copy cannot enter the full queue.
            boolean reachedSecondCopy = secondCopied.await(3, TimeUnit.SECONDS);
            session.requestStop();
            boolean terminated = session.awaitTermination(3, TimeUnit.SECONDS);

            // Assert
            // Ensure cancellation releases both accepted and unaccepted copies.
            assertThat(reachedSecondCopy).isTrue();
            assertThat(terminated).isTrue();
            assertThat(session.getFailure()).isNull();
            verify(firstCopy).close();
            verify(secondCopy).close();
            verify(firstInput, never()).close();
            verify(secondInput, never()).close();
            verify(decoder).close();
        } finally {
            // Stop all session threads even when the test fails or times out.
            session.requestStop();
            session.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    /**
     * Checks that an initially paused session admits one preview and resumes further reads.
     *
     * @throws Exception if processing or test coordination fails
     */
    @Test
    @Timeout(10)
    void shouldPrepareOnePausedPreviewThenResume() throws Exception {
        // Assemble
        PlaybackSession session = new PlaybackSession(List.of());
        VideoDecoder decoder = mock(VideoDecoder.class);
        AtomicReference<Thread> decoderThread = new AtomicReference<>();
        CountDownLatch secondRead = new CountDownLatch(1);
        InfoFrame firstInfo = new InfoFrame(1, 0);
        InfoFrame secondInfo = new InfoFrame(2, 40_000);

        try (Frame input = new Frame(1, 1, Frame.DEPTH_UBYTE, 3)) {
            // Observe the real owner thread without exposing it through the session API.
            doAnswer(call -> {
                decoderThread.set(Thread.currentThread());
                return null;
            }).when(decoder).open(0);

            // Mocking two video frames, followed by end of video.
            when(decoder.read())
                .thenReturn(new VideoDecoder.DecodedFrame(input, firstInfo))
                .thenAnswer(call -> {
                    secondRead.countDown();
                    return new VideoDecoder.DecodedFrame(input, secondInfo);
                }).thenReturn(null);
            when(decoder.videoInfo()).thenReturn(mock(InfoVideo.class));

            try {
                // Act
                // Two queue slots ensure the observed wait is not a full-queue wait.
                session.start(decoder, 0, true, 1, 2, 3);
                PreparedFrame preview = awaitResult(session);

                Thread owner = decoderThread.get();
                Thread.State pausedState;
                do {
                    Thread.sleep(1);
                    pausedState = owner.getState();
                } while (pausedState != Thread.State.WAITING && owner.isAlive());
                boolean secondReadWhilePaused = secondRead.getCount() == 0;

                session.setPaused(false);
                PreparedFrame next = awaitResult(session);
                boolean terminated = session.awaitTermination(3, TimeUnit.SECONDS);

                // Assert
                // Ensure only the preview was read before resume woke the decoder.
                assertThat(pausedState).isEqualTo(Thread.State.WAITING);
                assertThat(secondReadWhilePaused).isFalse();
                assertThat(preview.info()).isEqualTo(firstInfo);
                assertThat(next.info()).isEqualTo(secondInfo);
                assertThat(terminated).isTrue();
                assertThat(session.getFailure()).isNull();
                verify(decoder).close();
            } finally {
                // Stop also releases a decoder still waiting at the admission gate.
                session.requestStop();
                session.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Checks that the session starts audio preparation and prepares the first video frame.
     * The shared clock uses the first audio timestamp when it comes before video.
     *
     * @throws Exception if processing or test coordination fails
     */
    @Test
    @Timeout(10)
    void shouldPrepareAudioAndVideo() throws Exception {
        // Assemble
        PlaybackSession session = new PlaybackSession(List.of());
        VideoDecoder video = mock(VideoDecoder.class);
        AudioDecoder audioDecoder = mock(AudioDecoder.class);
        AudioOutput output = mock(AudioOutput.class);
        PlaybackClock clock = new PlaybackClock(0, System.nanoTime());
        AudioPreparation audio = new AudioPreparation(output);
        byte[] samples = {1, 2};

        // Mocking one audio packet and a ready audio output.
        when(output.playbackClock()).thenReturn(clock);
        when(output.isReady()).thenReturn(true);
        when(output.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(audioDecoder.hasAudio()).thenReturn(true);
        when(audioDecoder.sampleRate()).thenReturn(48_000);
        when(audioDecoder.channels()).thenReturn(1);
        when(audioDecoder.read()).thenReturn(new AudioDecoder.DecodedAudio(100, samples)).thenReturn(null);
        when(video.videoInfo()).thenReturn(mock(InfoVideo.class));

        try (Frame input = new Frame(1, 1, Frame.DEPTH_UBYTE, 3)) {
            // Mocking one video frame, followed by end of video.
            when(video.read()).thenReturn(new VideoDecoder.DecodedFrame(input, new InfoFrame(1, 200)))
                .thenReturn(null);

            try {
                // Act
                session.startWithAudio(video, audioDecoder, audio, 0, false, 1, 2, 3);
                PreparedFrame first = awaitResult(session);
                boolean terminated = session.awaitTermination(3, TimeUnit.SECONDS);

                // Assert
                // Ensure both streams were prepared and the shared clock kept their offset.
                assertThat(first.info().timestampUs()).isEqualTo(200);
                assertThat(session.isStartupReady()).isTrue();
                assertThat(clock.relativeTimestampUs(100)).isZero();
                assertThat(clock.relativeTimestampUs(200)).isEqualTo(100);
                assertThat(terminated).isTrue();
                assertThat(session.getFailure()).isNull();
                verify(output).enqueue(100, samples);
                verify(output).finishInput();
                verify(audioDecoder).close();
                verify(video).close();
            } finally {
                // Stop all session threads even when the test fails or times out.
                session.requestStop();
                session.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Checks that a paused preview is available while audio startup is still pending.
     * An early resume must not start the shared clock before audio is ready.
     *
     * @throws Exception if processing or test coordination fails
     */
    @Test
    @Timeout(10)
    void shouldWaitForAudioAfterPausedPreview() throws Exception {
        // Assemble
        PlaybackSession session = new PlaybackSession(List.of());
        VideoDecoder video = mock(VideoDecoder.class);
        AudioDecoder audioDecoder = mock(AudioDecoder.class);
        AudioPreparation audio = mock(AudioPreparation.class);
        PlaybackClock clock = new PlaybackClock(0, System.nanoTime());
        when(audio.playbackClock()).thenReturn(clock);
        when(audio.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(video.videoInfo()).thenReturn(mock(InfoVideo.class));

        try (Frame input = new Frame(1, 1, Frame.DEPTH_UBYTE, 3)) {
            // Audio probing stays pending while the first video frame is prepared.
            when(video.read()).thenReturn(new VideoDecoder.DecodedFrame(input, new InfoFrame(1, 200)));

            try {
                // Act
                session.startWithAudio(video, audioDecoder, audio, 0, true, 1, 2, 3);
                PreparedFrame preview = awaitResult(session);
                session.setPaused(false);
                boolean ready = session.isStartupReady();
                boolean due = clock.isDue(0, System.nanoTime());
                session.requestStop();
                boolean terminated = session.awaitTermination(3, TimeUnit.SECONDS);

                // Assert
                // Ensure resume did not start playback while audio was still pending.
                assertThat(preview.info().timestampUs()).isEqualTo(200);
                assertThat(ready).isFalse();
                assertThat(due).isFalse();
                assertThat(terminated).isTrue();
                verify(audio, atLeastOnce()).requestStop();
                verify(video).close();
            } finally {
                // Stop all session threads even when the test fails or times out.
                session.requestStop();
                session.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Waits for the next prepared result within the enclosing test's timeout.
     *
     * @param session session supplying processed frames
     * @return next processed result
     * @throws Exception if polling or waiting fails
     */
    private PreparedFrame awaitResult(PlaybackSession session) throws Exception {
        PreparedFrame result;
        while ((result = session.pollReady()) == null) {
            Thread.sleep(1);
        }
        return result;
    }

}