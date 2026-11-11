package au.edu.adelaide.assignment1.dto;

/**
 * GlobalStatsResponse is a record that represents the response for global statistics.
 *
 * @param inputTokens  the total number of input tokens
 * @param outputTokens the total number of output tokens
 */
public record GlobalStatsResponse(
    long inputTokens,
    long outputTokens
) {}
