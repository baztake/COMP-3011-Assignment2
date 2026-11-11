package au.edu.adelaide.assignment1.controller;

import au.edu.adelaide.assignment1.dto.GlobalStatsResponse;
import au.edu.adelaide.assignment1.dto.GlobalStatsResult;
import au.edu.adelaide.assignment1.service.GlobalStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for handling global statistics requests.
 */
@RestController
@RequestMapping("/api/v1/global")
public class GlobalStatsController {

    private static final Logger log = LoggerFactory.getLogger(GlobalStatsController.class);
    private final GlobalStatsService statsService;

    /**
     * Constructor for GlobalStatsController.
     *
     * @param statsService the service to retrieve global statistics
     */
    public GlobalStatsController(GlobalStatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * Endpoint to retrieve global statistics.
     *
     * @return ResponseEntity containing the global statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<GlobalStatsResponse> getGlobalStats() {
        log.info("GET /api/v1/global/stats called");
        // Retrieve the global statistics from the service
        GlobalStatsResult result = statsService.getStats();

        // Create a response object to send back to the client
        GlobalStatsResponse response = new GlobalStatsResponse(
            result.inputTokens(),
            result.outputTokens()
        );
        log.debug("Global stats returned: inputTokens={}, outputTokens={}", result.inputTokens(), result.outputTokens());
        return ResponseEntity.ok(response);
    }
}