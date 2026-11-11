package au.edu.adelaide.assignment1.dto;

import java.time.Instant;

/**
 * DTO for uptime response.
 */
public record UptimeResponse (
    Instant utcServerStart,
    Instant utcNow,
    long serverUptimeSeconds
) {}
