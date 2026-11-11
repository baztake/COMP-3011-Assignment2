package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.dto.UptimeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Service class to calculate the uptime of the application.
 */
@Service
public class UptimeService {

    private static final Logger log = LoggerFactory.getLogger(UptimeService.class);
    private final Instant start = Instant.now();

    /**
     * Returns the uptime of the application in seconds.
     *
     * @return UptimeResult containing the start time, current time, and uptime in seconds.
     */
    public UptimeResult getUptimeSeconds() {
        Instant now = Instant.now();
        long uptimeSeconds = java.time.Duration.between(start, now).getSeconds();
        UptimeResult res = new UptimeResult(start, now, uptimeSeconds);
        log.debug("getUptimeSeconds -> uptimeSeconds={}", uptimeSeconds);
        return res;
    }
}
