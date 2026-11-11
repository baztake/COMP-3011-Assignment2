package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.dto.TranscriptionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service class responsible for handling speech-to-text transcription and recording usage statistics.
 */
@Service
public class SpeechToTextService {

    private static final Logger log = LoggerFactory.getLogger(SpeechToTextService.class);

    private final TranscriptionService transcriptionService;
    private final GlobalStatsService globalStatsService;

    /**
     * Constructs a new SpeechToTextService with the given transcription and global stats services.
     *
     * @param transcriptionService The service responsible for transcribing audio files.
     * @param globalStatsService The service responsible for recording usage statistics.
     */
    public SpeechToTextService(
        TranscriptionService transcriptionService,
        GlobalStatsService globalStatsService
    ){
        this.transcriptionService = transcriptionService;
        this.globalStatsService = globalStatsService;
    }

    /**
     * Transcribes the given audio file and records the usage statistics.
     *
     * @param audio The audio file to be transcribed.
     * @return The result of the transcription.
     */
    public TranscriptionResult transcribe(MultipartFile audio) {
        log.info("SpeechToTextService.transcribe called; fileSize={} bytes", audio.getSize());
        try {
            // Call the transcription service to transcribe the audio
            TranscriptionResult result = transcriptionService.transcribe(audio);

            // Log and record the usage statistics in the global stats service
            log.debug("Recording usage: inputTokens={}, outputTokens={}", result.inputTokens(), result.outputTokens());
            globalStatsService.recordUsage(
                result.inputTokens(),
                result.outputTokens()
            );

            // Return the transcription result to the controller
            return result;
        } catch (Exception e) {
            log.error("Transcription failed: {}", e.getMessage(), e);
            throw e;
        }
    }
}
