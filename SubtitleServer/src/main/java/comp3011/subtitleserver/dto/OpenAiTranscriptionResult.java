/* Subtitle server prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. Adapted from Assignment 1. */
package comp3011.subtitleserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Whisper verbose JSON. Boxed numbers reject absent fields instead of silently using zero.
 *
 * @param duration audio duration in seconds
 * @param language detected language
 * @param text     complete unfiltered transcript
 * @param words    word timestamps relative to the uploaded chunk
 * @param segments segment timestamps and confidence values
 * @param usage    provider-reported usage, absent in some verbose responses
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiTranscriptionResult(
    Double duration,
    String language,
    String text,
    List<Word> words,
    List<Segment> segments,
    Usage usage
) {
    public OpenAiTranscriptionResult {
        requireFinite(duration);
        if (duration <= 0) {
            throw new IllegalArgumentException("Audio duration must be positive");
        }
        Objects.requireNonNull(text, "text");

        // Missing arrays are valid for silence. Speech must include both requested timing arrays.
        words = words == null ? List.of() : List.copyOf(words);
        segments = segments == null ? List.of() : List.copyOf(segments);

        if (!text.isBlank() && (words.isEmpty() || segments.isEmpty())) {
            throw new IllegalArgumentException("Speech response is missing requested timestamps");
        }
    }

    /**
     * @return provider usage seconds, falling back to the transcribed audio duration
     */
    public double usageSeconds() {
        return usage == null ? duration : usage.seconds();
    }

    /**
     * @param word  recognised word, including punctuation
     * @param start inclusive start in seconds
     * @param end   end in seconds, zero-duration words are permitted by Whisper
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Word(String word, Double start, Double end) {
        public Word {
            Objects.requireNonNull(word, "word");
            requireInterval(start, end);
        }
    }

    /**
     * @param start        start in seconds
     * @param end          end in seconds
     * @param text         recognised phrase
     * @param noSpeechProb estimated probability of silence
     * @param avgLogProb   average token log probability
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(
        Double start,
        Double end,
        String text,
        @JsonProperty("no_speech_prob") Double noSpeechProb,
        @JsonProperty("avg_logprob") Double avgLogProb
    ) {
        public Segment {
            requireInterval(start, end);
            Objects.requireNonNull(text, "text");
            requireFinite(noSpeechProb);
            requireFinite(avgLogProb);
            if (noSpeechProb < 0 || noSpeechProb > 1) {
                throw new IllegalArgumentException("Invalid silence probability");
            }
        }

        /**
         * @return whether the segment meets the assignment's combined silence thresholds
         */
        @JsonIgnore
        public boolean isSuppressed() {
            // The assignment requires both conditions, not either one on its own.
            return noSpeechProb > 0.6 && avgLogProb < -1.0;
        }
    }

    /**
     * @param type    duration-based usage type
     * @param seconds input audio seconds reported by the provider
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(String type, Double seconds) {
        public Usage {
            requireFinite(seconds);
            if (!"duration".equals(type) || seconds < 0) {
                throw new IllegalArgumentException("Invalid audio usage");
            }
        }
    }

    private static void requireInterval(Double start, Double end) {
        requireFinite(start);
        requireFinite(end);
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid timestamp interval");
        }
    }

    private static void requireFinite(Double value) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException("Missing or invalid number");
        }
    }
}
