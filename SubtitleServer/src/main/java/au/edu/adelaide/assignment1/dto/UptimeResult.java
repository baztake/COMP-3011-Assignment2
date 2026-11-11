package au.edu.adelaide.assignment1.dto;

import java.time.Instant;

/**
 * DTO for uptime result.
 */
public record UptimeResult (
    Instant startTime,
    Instant currentTime,
    long uptimeSeconds
) {}
