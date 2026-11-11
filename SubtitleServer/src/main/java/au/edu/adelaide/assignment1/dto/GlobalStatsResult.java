package au.edu.adelaide.assignment1.dto;

/**
 * Record to represent global statistics for input and output tokens.
 */
public record GlobalStatsResult(
    long inputTokens,
    long outputTokens
) {}
