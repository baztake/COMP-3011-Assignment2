/* Audio extraction tests prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import static org.assertj.core.api.Assertions.assertThat;

class SubtitleAudioExtractorTest {

    @Test
    void shouldBoundAndResampleAudioWithoutChangingTheSource(@TempDir Path directory) throws Exception {
        // Assemble
        File video = SubtitleTestMedia.video(directory, 31, true);
        long sourceSize = Files.size(video.toPath());

        // Act
        SubtitleAudioExtractor.AudioChunk chunk = new SubtitleAudioExtractor().extract(video);

        // Assert
        assertThat(chunk.offsetUs()).isZero();
        assertThat(chunk.durationUs()).isEqualTo(30_000_000);
        assertThat(chunk.wav().length).isLessThan(961_000);
        assertThat(Files.size(video.toPath())).isEqualTo(sourceSize);
        try (AudioInputStream wav = AudioSystem.getAudioInputStream(new ByteArrayInputStream(chunk.wav()))) {
            assertThat(wav.getFormat().getSampleRate()).isEqualTo(16_000);
            assertThat(wav.getFormat().getChannels()).isEqualTo(1);
            assertThat(wav.getFrameLength()).isEqualTo(480_000);
            assertThat(wav.readNBytes(16_000)).isNotEqualTo(new byte[16_000]);
        }
    }

    @Test
    void shouldSkipVideoWithoutAudio(@TempDir Path directory) throws Exception {
        // Assemble
        File video = SubtitleTestMedia.video(directory, 1, false);

        // Act
        SubtitleAudioExtractor.AudioChunk chunk = new SubtitleAudioExtractor().extract(video);

        // Assert
        assertThat(chunk).isNull();
    }
}
