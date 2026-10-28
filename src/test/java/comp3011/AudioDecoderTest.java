/*
 * Audio decoding regression test prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex.
 */
package comp3011;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.Test;

import java.nio.Buffer;
import java.nio.ShortBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AudioDecoderTest {

    /**
     * Checks that decoded audio is copied with its timestamp and format.
     *
     * @throws Exception if decoding or cleanup fails
     */
    @Test
    void shouldCopyAudioSamplesBeforeDecoderReuse() throws Exception {
        // Assemble
        FFmpegFrameGrabber grabber = mock(FFmpegFrameGrabber.class);
        Frame frame = mock(Frame.class);
        ShortBuffer source = ShortBuffer.wrap(new short[]{0x1234, -2});
        frame.samples = new Buffer[]{source};
        frame.audioChannels = 1;
        when(grabber.hasAudio()).thenReturn(true);
        when(grabber.getSampleRate()).thenReturn(48_000);
        when(grabber.getAudioChannels()).thenReturn(1);
        when(grabber.getTimestamp()).thenReturn(250_000L);
        when(grabber.grabSamples()).thenReturn(frame).thenReturn(null);

        AudioDecoder.DecodedAudio first;
        AudioDecoder.DecodedAudio end;
        boolean available;
        int sampleRate;
        int channels;

        // Act
        try (AudioDecoder decoder = new AudioDecoder(grabber)) {
            decoder.open(0);
            available = decoder.hasAudio();
            sampleRate = decoder.sampleRate();
            channels = decoder.channels();
            first = decoder.read();
            source.put(0, (short) 0);
            end = decoder.read();
        }

        // Assert
        // Ensure the copied packet survives source-buffer reuse.
        assertThat(available).isTrue();
        assertThat(sampleRate).isEqualTo(48_000);
        assertThat(channels).isEqualTo(1);
        assertThat(first.timestampUs()).isEqualTo(250_000L);
        assertThat(first.samples()).isEqualTo(new byte[]{0x34, 0x12, -2, -1});
        assertThat(source.position()).isEqualTo(0);
        assertThat(end).isNull();
        verify(grabber).setSampleMode(FrameGrabber.SampleMode.SHORT);
        verify(grabber).close();
        verify(frame, never()).close();
    }
}
