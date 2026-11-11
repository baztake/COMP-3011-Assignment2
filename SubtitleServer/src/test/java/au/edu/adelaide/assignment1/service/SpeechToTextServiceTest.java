package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.dto.TranscriptionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpeechToTextServiceTest {

    private SpeechToTextService service;
    private TranscriptionService transcriptionService;
    private GlobalStatsService globalStatsService;

    /**
     * A fake implementation of TranscriptionResult for testing purposes.
     */
    private record FakeResult(String text, long inputTokens, long outputTokens) implements TranscriptionResult {}

    /**
     * Sets up the SpeechToTextService instance before each test, injecting mocked dependencies.
     */
    @BeforeEach
    void setup() {
        transcriptionService = mock(TranscriptionService.class);
        globalStatsService = mock(GlobalStatsService.class);
        service = new SpeechToTextService(transcriptionService, globalStatsService);
    }

    /**
     * Creates a sample MultipartFile representing an audio clip for testing.
     */
    private MultipartFile sampleAudio() {
        return new MockMultipartFile("file", "clip.webm", "audio/webm", "fake-audio-bytes".getBytes());
    }

    /**
     * Tests that the transcribe method calls the TranscriptionService and returns its result correctly.
     */
    @Test
    void transcribe_callsTranscriptionServiceAndReturnsItsResult() {
        // Create a sample audio file and a fake transcription result
        MultipartFile audio = sampleAudio();
        FakeResult expected = new FakeResult("hello world", 100, 50);
        when(transcriptionService.transcribe(audio)).thenReturn(expected);

        // Call the transcribe method and capture the result
        TranscriptionResult actual = service.transcribe(audio);

        // Verify that the actual result is the same as the expected result and that the transcription service was called once
        assertThat(actual).isSameAs(expected);
        verify(transcriptionService, times(1)).transcribe(audio);
    }

    /**
     * Tests that the transcribe method records usage with the correct token counts from the result.
     */
    @Test
    void transcribe_recordsUsageWithTokensFromResult() {
        // Create a sample audio file and a fake transcription result with specific token counts
        MultipartFile audio = sampleAudio();
        FakeResult result = new FakeResult("hi", 42, 17);
        when(transcriptionService.transcribe(audio)).thenReturn(result);

        // Call the transcribe method
        service.transcribe(audio);

        // Verify that the global stats service recorded the usage with the correct token counts
        verify(globalStatsService, times(1)).recordUsage(42L, 17L);
    }

    /**
     * Tests that the transcribe method still records usage even when the result has zero tokens.
     */
    @Test
    void transcribe_withZeroTokenResult_stillRecordsZeroUsage() {
        // Create a sample audio file and a fake transcription result with zero tokens
        MultipartFile audio = sampleAudio();
        FakeResult result = new FakeResult("", 0, 0);
        when(transcriptionService.transcribe(audio)).thenReturn(result);

        // Call the transcribe method
        service.transcribe(audio);

        // Verify that the global stats service recorded the usage with zero token counts
        verify(globalStatsService).recordUsage(0L, 0L);
    }

    /**
     * Tests that if the TranscriptionService throws an exception, the transcribe method propagates it and does not record usage.
     */
    @Test
    void transcribe_whenTranscriptionServiceThrows_propagatesAndSkipsUsageRecording() {
        // Create a sample audio file and configure the transcription service to throw an exception
        MultipartFile audio = sampleAudio();
        when(transcriptionService.transcribe(audio)).thenThrow(new IllegalStateException("upstream failure"));

        // Assert that calling transcribe throws the expected exception
        assertThrows(IllegalStateException.class, () -> service.transcribe(audio));

        // Verify that the global stats service did not record any usage
        verify(globalStatsService, never()).recordUsage(any(), any());
    }
}
