package au.edu.adelaide.assignment1.controller;

import au.edu.adelaide.assignment1.dto.TranscriptionResponse;
import au.edu.adelaide.assignment1.dto.TranscriptionResult;
import au.edu.adelaide.assignment1.service.SpeechToTextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Controller for handling transcription requests.
 */
@RestController
@RequestMapping("/api/v1/transcribe")
public class TranscriptionController {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionController.class);
    private final SpeechToTextService service;

    /**
     * Constructor for TranscriptionController.
     *
     * @param service the SpeechToTextService to handle transcription
     */
    public TranscriptionController(SpeechToTextService service) {
        this.service = service;
    }

    /**
     * Endpoint to transcribe an audio file.
     *
     * @param file the audio file to be transcribed
     * @return ResponseEntity containing the transcription result
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TranscriptionResponse> transcribe(@RequestPart("file") MultipartFile file) {
        log.info("POST /api/v1/transcribe called; fileName={}, size={}", file.getOriginalFilename(), file.getSize());
        // Call the service to transcribe the audio file
        TranscriptionResult result = service.transcribe(file);

        // Create and return a response object with the transcription result
        TranscriptionResponse response = new TranscriptionResponse(result.text());
        log.debug(
            "Transcription response prepared; textLength={}",
            response.transcription() == null ? 0 : response.transcription().length()
        );
        return ResponseEntity.ok(response);
    }

}
