/* Subtitle server tests prepared with assistance from OpenAI Codex. */
package comp3011.subtitleserver;

import comp3011.subtitleserver.dto.OpenAiTranscriptionResult;
import comp3011.subtitleserver.dto.SubtitleResponse;
import comp3011.subtitleserver.service.OpenAiTranscriptionService;
import comp3011.subtitleserver.service.SubtitleService;
import comp3011.subtitleserver.service.SubtitleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubtitleStoreTest {
    @Test
    void shouldRetainCompletedWorkUntilStorageRecovers(@TempDir Path directory) throws Exception {
        // Assemble: block replacement with a nonempty directory at the intended file location.
        Path file = directory.resolve("state.json");
        SubtitleStore store = new SubtitleStore(file.toString(), 8);
        Files.createDirectory(file);
        Path blocker = Files.writeString(file.resolve("blocker"), "test");
        SubtitleResponse result = new SubtitleResponse(2.0, "english", "", List.of(), List.of());
        String key = "c".repeat(64);

        // Act
        assertThatThrownBy(() -> store.record(key, result, 2.0)).hasMessageContaining("Cannot save");
        assertThatThrownBy(store::getUsage).hasMessageContaining("Cannot save");
        Files.delete(blocker);
        Files.delete(file);

        // Assert: retrying persistence does not add usage again or lose the completed transcription.
        assertThat(store.find(key)).isEqualTo(result);
        assertThat(store.getUsage()).isEqualTo(2.0);
        SubtitleStore restarted = new SubtitleStore(file.toString(), 8);
        assertThat(restarted.find(key)).isEqualTo(result);
        assertThat(restarted.getUsage()).isEqualTo(2.0);
    }

    @Test
    void shouldRetainUsageWhenEvictingAndReloadCachedTimings(@TempDir Path directory) {
        // Assemble
        Path file = directory.resolve("state.json");
        SubtitleStore store = new SubtitleStore(file.toString(), 1);
        SubtitleResponse result = new SubtitleResponse(1.25, "english", "Hello.",
            List.of(new OpenAiTranscriptionResult.Word("Hello.", 0.0, 1.0)),
            List.of(new OpenAiTranscriptionResult.Segment(0.0, 1.0, "Hello.", 0.1, -0.1)));

        // Act
        store.record("a".repeat(64), result, 1.25);
        store.record("b".repeat(64), result, 2.5);
        SubtitleStore restarted = new SubtitleStore(file.toString(), 1);

        // Assert
        assertThat(restarted.find("a".repeat(64))).isNull();
        assertThat(restarted.find("b".repeat(64))).isEqualTo(result);
        assertThat(restarted.getUsage()).isEqualTo(3.75);
    }

    @Test
    void shouldServePersistedAudioWithoutCredentialsAndRejectUncachedAudio(@TempDir Path directory) throws Exception {
        // Assemble: seed the store using the exact content/settings hash used by the service.
        byte[] audio = "cached audio".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update("whisper-1|verbose_json|word,segment|silence-v1|".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String key = java.util.HexFormat.of().formatHex(digest.digest(audio));
        Path file = directory.resolve("state.json");
        SubtitleResponse silence = new SubtitleResponse(2.0, "english", "", List.of(), List.of());
        SubtitleStore store = new SubtitleStore(file.toString(), 8);
        store.record(key, silence, 2.0);
        OpenAiTranscriptionService offlineProvider = new OpenAiTranscriptionService(RestClient.create(), "");
        SubtitleService restarted = new SubtitleService(offlineProvider, new SubtitleStore(file.toString(), 8));

        // Act and Assert
        assertThat(restarted.transcribe(new MockMultipartFile("file", "renamed.wav", "audio/wav", audio))).isEqualTo(silence);
        assertThatThrownBy(() -> restarted.transcribe(new MockMultipartFile("file", "new.wav", "audio/wav", new byte[]{1})))
            .hasMessageContaining("OPENAI_API_KEY");
        assertThat(store.getUsage()).isEqualTo(2.0);
    }

    @Test
    void shouldFailRatherThanSilentlyResetCorruptUsage(@TempDir Path directory) throws Exception {
        // Assemble
        Path file = directory.resolve("state.json");
        Files.writeString(file, "{broken state");

        // Act and Assert
        assertThatThrownBy(() -> new SubtitleStore(file.toString(), 8))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cannot load subtitle store");
        assertThat(Files.readString(file)).isEqualTo("{broken state");
    }
}
