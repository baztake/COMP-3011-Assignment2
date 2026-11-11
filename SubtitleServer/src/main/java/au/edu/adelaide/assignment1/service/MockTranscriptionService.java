package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.dto.OpenAiTranscriptionResult;
import au.edu.adelaide.assignment1.dto.TranscriptionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Mock implementation of the TranscriptionService for testing purposes.
 * This service does not perform actual transcription and returns a fixed response.
 */
@Service
@ConditionalOnProperty(name = "transcription.service.provider", havingValue = "mock")
public class MockTranscriptionService implements TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(MockTranscriptionService.class);

    /**
     * Constructs a new MockTranscriptionService.
     * This service is intended for testing purposes and does not perform actual transcription.
     */
    public MockTranscriptionService() {
        log.warn("MockTranscriptionService is being used; this is intended for testing purposes only.");
    }

    /**
     * Transcribes the given audio file.
     * This mock implementation returns a fixed transcription result for testing purposes.
     *
     * @param audio the audio file to transcribe
     * @return a mock transcription result
     */
    @Override
    public TranscriptionResult transcribe(MultipartFile audio) {
        // Log the call to the mock service
        log.info("MockTranscriptionService.transcribe called; file size={} bytes, contentType={}", audio.getSize(), audio.getContentType());

        // Return a fixed transcription result for testing purposes
        return new OpenAiTranscriptionResult(
            "This is a mock transcription result for testing purposes.",
            new OpenAiTranscriptionResult.Usage(10L, 20L)
        );
    }
}