/*
 * Session composition regression test prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

class PlaybackSessionTest {

    /**
     * Checks that a session-created pool applies selected effects and preserves frame metadata.
     *
     * @throws Exception if processing, result retrieval or cleanup fails
     */
    @Test
    @Timeout(10)
    void shouldProcessFrameThroughSessionPool() throws Exception {
        // Assemble
        PlaybackSession session = new PlaybackSession(List.of(FrameEffect.BLACK_AND_WHITE));
        InfoFrame info = new InfoFrame(7, 250_000);

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
            FrameProcessingPool pool = session.createPool(1, 1, videoInfo);

            try {
                // Act
                // Submit a copy because the pool owns accepted input frames.
                pool.submit(input.clone(), info, 3);

                // Wait for the result. The test timeout prevents an endless wait.
                PreparedFrame result;
                while ((result = pool.pollReady()) == null) {
                    Thread.sleep(1);
                }

                // Assert
                // Ensure the selected effect ran and the frame identity was preserved.
                assertThat(result.image().getPixelReader().getArgb(0, 0))
                    .isEqualTo(0xFF151515);
                assertThat(result.info()).isEqualTo(info);
                assertThat(result.generation()).isEqualTo(3);
            } finally {
                // Stop workers even when the test fails or times out.
                pool.requestStop();
                pool.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
    }
}