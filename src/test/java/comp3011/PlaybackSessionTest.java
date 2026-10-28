/*
 * Session composition regression test prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

class PlaybackSessionTest {

    /**
     * Checks that the session creates an initialised worker using selected effects.
     *
     * @throws Exception if processor initialisation or processing fails
     */
    @Test
    void shouldCreateInitialisedWorkerWithSelectedEffect() throws Exception {
        // Assemble
        PlaybackSession session = new PlaybackSession(List.of(FrameEffect.BLACK_AND_WHITE));

        try (Frame input = new Frame(1, 1, Frame.DEPTH_UBYTE, 3)) {
            // JavaCV supplies decoded image pixels in BGR order.
            ByteBuffer pixels = (ByteBuffer) input.image[0];
            pixels.put(0, (byte) 10);
            pixels.put(1, (byte) 20);
            pixels.put(2, (byte) 30);

            InfoVideo videoInfo = new InfoVideo(
                "test", 1, 1, 1,
                input.imageDepth,
                input.imageChannels,
                input.imageStride,
                24.0, 24, 41_667,
                AV_PIX_FMT_BGR24
            );

            // Act
            FrameProcessingWorker worker = session.createWorker(videoInfo);
            PreparedFrame result = worker.process(input, new InfoFrame(1, 0), 0);
            int pixel = result.image().getPixelReader().getArgb(0, 0);

            // Assert
            // The selected effect converts B=10, G=20, R=30 to grey=21.
            assertThat(pixel).isEqualTo(0xFF151515);
        }
    }
}