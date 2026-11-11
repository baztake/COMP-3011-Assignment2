package au.edu.adelaide.assignment1.controller;

import au.edu.adelaide.assignment1.dto.TranscriptionResult;
import au.edu.adelaide.assignment1.service.SpeechToTextService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link TranscriptionController}.
 * <p>
 * These tests focus on the controller's behavior, mocking out the underlying service layer.
 */
@WebMvcTest(TranscriptionController.class)
class TranscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpeechToTextService speechToTextService;

    private record FakeResult(String text, long inputTokens, long outputTokens) implements TranscriptionResult {
    }

    /**
     * Test that a valid audio file upload returns a 200 OK response with the expected transcribed text in the JSON response body.
     */
    @Test
    void transcribe_validAudioUpload_returns200WithTranscribedText() throws Exception {
        // Create a mock audio file to simulate an upload
        MockMultipartFile file = new MockMultipartFile("file", "clip.webm", "audio/webm", "audio-bytes".getBytes());
        when(speechToTextService.transcribe(any())).thenReturn(new FakeResult("hello world", 10, 5));

        // Perform the multipart file upload request and assert the expected response
        mockMvc.perform(multipart("/api/v1/transcribe").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transcription").value("hello world"));
    }

    /**
     * Test that an empty audio file upload is still forwarded to the service and returns a 200 OK response with an empty transcription.
     */
    @Test
    void transcribe_emptyAudioFile_isStillForwardedToServiceAndReturns200() throws Exception {
        // Create a mock empty audio file to simulate an upload
        MockMultipartFile emptyFile = new MockMultipartFile("file", "clip.webm", "audio/webm", new byte[0]);
        when(speechToTextService.transcribe(any())).thenReturn(new FakeResult("", 0, 0));

        // Perform the multipart file upload request and assert the expected response
        mockMvc.perform(multipart("/api/v1/transcribe").file(emptyFile))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transcription").value(""));
    }

    /**
     * Test that a request without the required "file" part returns a 400 Bad Request response.
     */
    @Test
    void transcribe_missingFilePart_returns400BadRequest() throws Exception {
        // Test that a request without the required "file" part returns a 400 Bad Request response.
        mockMvc.perform(multipart("/api/v1/transcribe"))
            .andExpect(status().isBadRequest());
    }

    /**
     * Test that a request with the wrong part name returns a 400 Bad Request response.
     */
    @Test
    void transcribe_wrongPartName_returns400BadRequest() throws Exception {
        // Same root cause as the missing-file-part test above.
        MockMultipartFile wrongPart = new MockMultipartFile("audio", "clip.webm", "audio/webm", "bytes".getBytes());

        // Perform the multipart file upload request with the wrong part name and assert the expected response
        mockMvc.perform(multipart("/api/v1/transcribe").file(wrongPart))
            .andExpect(status().isBadRequest());
    }

    /**
     * Test that when the downstream service throws an exception, the controller returns a 500 Internal Server Error response with a structured error body.
     */
    @Test
    void transcribe_downstreamServiceThrows_returns500WithStructuredErrorBody() throws Exception {
        // Create a mock audio file to simulate an upload
        MockMultipartFile file = new MockMultipartFile("file", "clip.webm", "audio/webm", "audio-bytes".getBytes());
        when(speechToTextService.transcribe(any())).thenThrow(new RuntimeException("STT provider unavailable"));

        // Perform the multipart file upload request and assert the expected 500 Internal Server Error response with a structured error body
        mockMvc.perform(multipart("/api/v1/transcribe").file(file))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.error").value("Internal Server Error"))
            .andExpect(jsonPath("$.path").value("/api/v1/transcribe"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
    }
}