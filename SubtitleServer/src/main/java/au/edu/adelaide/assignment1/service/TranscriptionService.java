package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.dto.TranscriptionResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service interface for transcribing audio files.
 */
public interface TranscriptionService {

    /**
     * Transcribes the given audio file and returns the transcription result.
     *
     * @param audio The audio file to be transcribed.
     * @return The transcription result containing the transcribed text and any relevant metadata.
     */
    TranscriptionResult transcribe(MultipartFile audio);
}
