package au.edu.adelaide.assignment1.controller;

import au.edu.adelaide.assignment1.dto.ShutdownResponse;
import au.edu.adelaide.assignment1.dto.UptimeResponse;
import au.edu.adelaide.assignment1.dto.UptimeResult;
import au.edu.adelaide.assignment1.exception.ShutdownInProgressException;
import au.edu.adelaide.assignment1.service.ShutdownService;
import au.edu.adelaide.assignment1.service.UptimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for administrative operations such as shutdown and uptime.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdministratorController {

    private static final Logger log = LoggerFactory.getLogger(AdministratorController.class);
    private final ShutdownService shutdownService;
    private final UptimeService uptimeService;

    /**
     * Constructs an AdministratorController with the specified services.
     *
     * @param shutdownService the service responsible for handling shutdown requests
     * @param uptimeService   the service responsible for providing uptime information
     */
    public AdministratorController(
        ShutdownService shutdownService,
        UptimeService uptimeService
    ) {
        this.shutdownService = shutdownService;
        this.uptimeService = uptimeService;
    }

    /**
     * Endpoint to retrieve the application's uptime information.
     *
     * @return a ResponseEntity containing the uptime response
     */
    @GetMapping("/uptime")
    public ResponseEntity<UptimeResponse> uptime() {
        log.info("GET /api/v1/admin/uptime called");
        // Get the uptime information from the UptimeService
        UptimeResult result = uptimeService.getUptimeSeconds();

        // Return the uptime information in the response
        UptimeResponse resp = new UptimeResponse(
                result.startTime(),
                result.currentTime(),
                result.uptimeSeconds()
            );
        log.debug("Uptime response: uptimeSeconds={}", result.uptimeSeconds());
        return ResponseEntity.ok(resp);
    }

    /**
     * Endpoint to request a graceful shutdown of the application.
     *
     * @return a ResponseEntity indicating that the shutdown request has been accepted
     */
    @PostMapping("/shutdown")
    public ResponseEntity<ShutdownResponse> shutdown() {
        log.info("POST /api/v1/admin/shutdown called");
        try {
            // Request a graceful shutdown using the ShutdownService, which throws a ShutdownInProgressException
            // if a shutdown is already in progress
            shutdownService.requestShutdown();

            // Return a response indicating that the shutdown request has been accepted
            ShutdownResponse response = new ShutdownResponse("Graceful shutdown requested.");
            log.info("Shutdown request accepted");
            return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(response);
        } catch (ShutdownInProgressException e) {
            log.warn("Shutdown request rejected: already in progress");
            throw e; // handled by GlobalExceptionHandler
        }
    }
}
