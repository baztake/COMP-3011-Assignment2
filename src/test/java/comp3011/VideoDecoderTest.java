/*
 * Decoder regression test prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.Test;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

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

    /**
     * Checks that mixed decoding returns audio before the first video frame.
     *
     * @throws Exception if decoding or cleanup fails
     */
    @Test
    void shouldReadAudioAndVideoFrames() throws Exception {
        // Assemble
        FFmpegFrameGrabber grabber = mock(FFmpegFrameGrabber.class);
        Frame audio = mock(Frame.class);
        audio.samples = new Buffer[]{ShortBuffer.wrap(new short[]{1})};
        Frame video = mock(Frame.class);
        video.image = new Buffer[]{ByteBuffer.allocate(3)};
        video.imageWidth = 1;
        video.imageHeight = 1;
        video.imageDepth = Frame.DEPTH_UBYTE;
        video.imageChannels = 3;
        video.imageStride = 3;

        // Mocking an audio frame, a video frame, then end of media.
        when(grabber.grab()).thenReturn(audio, video).thenReturn(null);
        when(grabber.getTimestamp()).thenReturn(100L, 200L);
        when(grabber.getFrameNumber()).thenReturn(7);
        when(grabber.getFrameRate()).thenReturn(25.0);
        when(grabber.getLengthInVideoFrames()).thenReturn(1);
        when(grabber.getPixelFormat()).thenReturn(AV_PIX_FMT_BGR24);

        VideoDecoder.DecodedMediaFrame first;
        VideoDecoder.DecodedMediaFrame second;
        VideoDecoder.DecodedMediaFrame end;
        InfoVideo metadata;

        // Act
        try (VideoDecoder decoder = new VideoDecoder(grabber, "test")) {
            decoder.open(0);
            first = decoder.readMedia();
            second = decoder.readMedia();
            metadata = decoder.videoInfo();
            end = decoder.readMedia();
        }

        // Assert
        // Ensure audio is retained and the first video frame supplies metadata.
        assertThat(first.frame()).isSameAs(audio);
        assertThat(first.timestampUs()).isEqualTo(100L);
        assertThat(first.videoInfo()).isNull();
        assertThat(second.frame()).isSameAs(video);
        assertThat(second.timestampUs()).isEqualTo(200L);
        assertThat(second.videoInfo()).isEqualTo(new InfoFrame(7, 200L));
        assertThat(metadata.imageWidth()).isEqualTo(1);
        assertThat(end).isNull();
        verify(grabber).close();
        verify(audio, never()).close();
        verify(video, never()).close();
    }

}
