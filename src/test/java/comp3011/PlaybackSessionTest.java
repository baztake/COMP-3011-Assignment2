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
                session.start(decoder, 0, 1, 1, 3);

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
            session.start(decoder, 0, 1, 1, 3);

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

}