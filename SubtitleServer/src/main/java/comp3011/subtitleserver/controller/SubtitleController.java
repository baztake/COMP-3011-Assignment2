/* Adapted from Assignment 1 server with assistance from OpenAI Codex. */
package comp3011.subtitleserver.controller;

import comp3011.subtitleserver.dto.SubtitleResponse;
import comp3011.subtitleserver.service.SubtitleService;
import comp3011.subtitleserver.service.SubtitleStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Exposes only the two Assignment 3 API endpoints.
 */
@RestController
public class SubtitleController {

    private final SubtitleService service;
    private final SubtitleStore store;

    /**
     * @param service transcription orchestration
     * @param store   cumulative usage storage
     */
    public SubtitleController(SubtitleService service, SubtitleStore store) {
        this.service = service;
        this.store = store;
    }

    /**
     * @param file original media audio chunk
     * @return text, words and segments with second-based chunk-relative timestamps
     */
    @PostMapping(
        value = "/api/v1/subtitles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public SubtitleResponse subtitles(@RequestPart("file") MultipartFile file) {
        return service.transcribe(file);
    }

    /**
     * @return cumulative transcription service usage in seconds
     */
    @GetMapping(value = "/api/v1/global/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public UsageResponse usage() {
        return new UsageResponse(store.getUsage());
    }

    /**
     * @param usage cumulative audio seconds
     */
    public record UsageResponse(double usage) {
    }
}
