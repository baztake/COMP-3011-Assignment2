/*
 * Decoder regression test prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;
import static org.mockito.Mockito.*;

class VideoDecoderTest {

    /**
     * Checks opening, first-frame metadata, end of video and grabber cleanup.
     *
     * @throws Exception if decoder setup, reading or closing fails
     */
    @Test
    void shouldReturnFirstFrameWithMetadataAndCloseGrabber() throws Exception {
        // Assemble
        FFmpegFrameGrabber grabber = mock(FFmpegFrameGrabber.class);
        Frame frame = mock(Frame.class);
        frame.imageWidth = 640;
        frame.imageHeight = 480;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = 3;
        frame.imageStride = 1_920;

        // Mocking the decoder output: one video frame, followed by end of video.
        when(grabber.grabImage()).thenReturn(frame).thenReturn(null);
        when(grabber.getFrameNumber()).thenReturn(7);
        when(grabber.getTimestamp()).thenReturn(1_250_000L);
        when(grabber.getFrameRate()).thenReturn(25.0);
        when(grabber.getLengthInVideoFrames()).thenReturn(50);
        when(grabber.getPixelFormat()).thenReturn(AV_PIX_FMT_BGR24);

        InfoVideo expectedInfo = new InfoVideo(
            "test", 50, 640, 480, Frame.DEPTH_UBYTE,
            3, 1_920, 25.0, 25, 40_000, AV_PIX_FMT_BGR24
        );
        VideoDecoder.DecodedFrame first;
        VideoDecoder.DecodedFrame end;
        InfoVideo actualInfo;

        // Act
        try (VideoDecoder decoder = new VideoDecoder(grabber, "test")) {
            decoder.open(1_000_000);
            first = decoder.read();
            actualInfo = decoder.videoInfo();
            end = decoder.read();
        }

        // Assert
        // Ensure the first frame was returned, rather than consumed for metadata.
        assertThat(first.frame()).isSameAs(frame);
        assertThat(first.info()).isEqualTo(new InfoFrame(7, 1_250_000));
        assertThat(actualInfo).isEqualTo(expectedInfo);
        assertThat(end).isNull();

        // Ensure configuration, seeking and cleanup reach the owned grabber.
        verify(grabber).setImageMode(FrameGrabber.ImageMode.COLOR);
        verify(grabber).setSampleMode(FrameGrabber.SampleMode.SHORT);
        verify(grabber).start();
        verify(grabber).setTimestamp(1_000_000);
        verify(grabber).close();
    }
}
