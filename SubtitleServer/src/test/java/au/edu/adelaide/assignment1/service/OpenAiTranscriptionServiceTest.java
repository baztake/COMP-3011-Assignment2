package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.dto.OpenAiTranscriptionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link OpenAiTranscriptionService}.
 */
class OpenAiTranscriptionServiceTest {

    private static final String STT_URL = "https://fake-stt.example.com/audio/transcriptions";

    private MockRestServiceServer mockServer;
    private OpenAiTranscriptionService service;

    /**
     * Sets up the test environment before each test case.
     * Initializes a mock server and the OpenAiTranscriptionService instance.
     */
    @BeforeEach
    void setUp() {
        // Create a RestClient with a base URL for the mock server and bind it to the MockRestServiceServer for testing.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://fake-stt.example.com");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        // Initialize the OpenAiTranscriptionService with the mock RestClient.
        service = new OpenAiTranscriptionService(restClient);
    }

    /**
     * Creates a sample audio file for testing purposes.
     *
     * @return a MockMultipartFile representing a sample audio file
     */
    private MultipartFile sampleAudio() {
        return new MockMultipartFile("file", "clip.webm", "audio/webm", "fake-audio-bytes".getBytes());
    }

    /**
     * Tests the successful transcription of audio and verifies that the text and usage information are correctly parsed from the response.
     */
    @Test
    void transcribe_success_parsesTextAndUsageFromResponse() {
        // Define a mock response body that simulates a successful transcription response from the server.
        String body = """
            {
              "text": "hello world",
              "usage": { "input_tokens": 120, "output_tokens": 34 }
            }
            """;

        // Set up the mock server to expect a POST request to the STT_URL and respond with the defined body and a 200 OK status.
        mockServer.expect(requestTo(STT_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // Call the transcribe method of the service with the sample audio and capture the result.
        OpenAiTranscriptionResult result = service.transcribe(sampleAudio());

        // Verify that the result contains the expected text and usage information.
        assertThat(result.text()).isEqualTo("hello world");
        assertThat(result.inputTokens()).isEqualTo(120L);
        assertThat(result.outputTokens()).isEqualTo(34L);
        mockServer.verify();
    }

    /**
     * Tests the behavior of the transcribe method when the response is missing usage information.
     * Verifies that the input and output tokens default to zero in this case.
     */
    @Test
    void transcribe_responseMissingUsage_tokensDefaultToZero() {
        // Define a mock response body that simulates a transcription response without usage information.
        String body = """
            { "text": "no usage supplied" }
            """;

        // Set up the mock server to expect a POST request to the STT_URL and respond with the defined body and a 200 OK status.
        mockServer.expect(requestTo(STT_URL))
                .andExpect(method(POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // Call the transcribe method of the service with the sample audio and capture the result.
        OpenAiTranscriptionResult result = service.transcribe(sampleAudio());

        // Verify that the result contains the expected text and that the input and output tokens default to zero.
        assertThat(result.text()).isEqualTo("no usage supplied");
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
    }

    /**
     * Tests the behavior of the transcribe method when the upstream server returns an error response.
     * Verifies that an HttpServerErrorException is thrown in this case.
     */
    @Test
    void transcribe_upstreamServerError_throwsException() {
        // Set up the mock server to expect a POST request to the STT_URL and respond with a server error (500 Internal Server Error).
        mockServer.expect(requestTo(STT_URL))
            .andExpect(method(POST))
            .andRespond(withServerError());

        // Verify that calling the transcribe method of the service with the sample audio throws an HttpServerErrorException.
        assertThrows(HttpServerErrorException.class, () -> service.transcribe(sampleAudio()));
        mockServer.verify();
    }

    /**
     * Tests the behavior of the transcribe method when the response body is empty (null).
     * Verifies that an IllegalStateException is thrown in this case.
     */
    @Test
    void transcribe_emptyResponseBody_throwsIllegalStateException() {
        // Set up the mock server to expect a POST request to the STT_URL and respond with an empty response body (null).
        mockServer.expect(requestTo(STT_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        // Verify that calling the transcribe method of the service with the sample audio throws an IllegalStateException
        // with a message containing "OpenAI returned an empty response".
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.transcribe(sampleAudio()));
        assertThat(ex.getMessage()).contains("OpenAI returned an empty response");
    }
}
