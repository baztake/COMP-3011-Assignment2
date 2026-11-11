package au.edu.adelaide.assignment1.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents the result from the OpenAI transcription API.
 */
public record OpenAiTranscriptionResult(
        String text,
        Usage usage
) implements TranscriptionResult {

    /**
     * Returns the number of input tokens used in the transcription.
     *
     * @return the number of input tokens
     */
    @Override
    public long inputTokens() {
        return usage != null ? usage.inputTokens() : 0L;
    }

    /**
     * Returns the number of output tokens used in the transcription.
     *
     * @return the number of output tokens
     */
    @Override
    public long outputTokens() {
        return usage != null ? usage.outputTokens() : 0L;
    }

    /**
     * Represents the usage information from the OpenAI transcription API.
     */
    public record Usage(
        @JsonProperty("input_tokens")
        long inputTokens,

        @JsonProperty("output_tokens")
        long outputTokens
    ) {}
}
