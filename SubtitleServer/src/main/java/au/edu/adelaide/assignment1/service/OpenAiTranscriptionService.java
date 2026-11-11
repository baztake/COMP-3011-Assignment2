package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.dto.OpenAiTranscriptionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestClient;

/**
 * Service for transcribing audio using OpenAI's transcription API.
 */
@Service
@ConditionalOnProperty(name = "transcription.service.provider", havingValue = "openai")
public class OpenAiTranscriptionService implements TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTranscriptionService.class);
    private final RestClient restClient;

    /**
     * Constructs a new OpenAiTranscriptionService with the given RestClient.
     *
     * @param restClient the RestClient to use for making API requests
     */
    public OpenAiTranscriptionService(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Transcribes the given audio file using OpenAI's transcription API.
     *
     * @param audio the audio file to transcribe
     * @return the transcription response from OpenAI
     */
    @Override
    public OpenAiTranscriptionResult transcribe(MultipartFile audio) {
        log.info("OpenAiTranscriptionService.transcribe called; file size={} bytes, contentType={}", audio.getSize(), audio.getContentType());

        // Build the multipart request body
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", audio.getResource())
            .filename("recording.webm");
        body.part("model", "gpt-4o-mini-transcribe");

        try {
            // Make the API request and retrieve the response
            OpenAiTranscriptionResult response = restClient.post()
                .uri("/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build())
                .retrieve()
                .body(OpenAiTranscriptionResult.class);

            if (response == null) {
                IllegalStateException ise = new IllegalStateException("OpenAI returned an empty response");
                log.error("Transcription failed: {}", ise.getMessage());
                throw ise;
            }

            log.debug("Transcription successful: textLength={}", response.text() == null ? 0 : response.text().length());
            return response;
        } catch (Exception e) {
            log.error("Error while calling OpenAI transcription API: {}", e.getMessage(), e);
            throw e;
        }
    }

}
