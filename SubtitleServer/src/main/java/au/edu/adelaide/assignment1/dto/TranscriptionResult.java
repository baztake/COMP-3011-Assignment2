package au.edu.adelaide.assignment1.dto;

/**
 * Represents the result of a transcription operation.
 */
public interface TranscriptionResult {
    String text();
    long inputTokens();
    long outputTokens();
}
