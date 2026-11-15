/* Subtitle response prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

/**
 * Server timings in seconds relative to an uploaded audio chunk.
 *
 * @param duration chunk duration in seconds
 * @param words recognised words and punctuation
 * @param segments accepted speech segments
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubtitleTranscript(Double duration, List<Word> words, List<Segment> segments) {
    public SubtitleTranscript {
        if (duration == null || !Double.isFinite(duration) || duration <= 0 || duration > 31) {
            throw new IllegalArgumentException("Invalid audio chunk duration");
        }

        // Records do not make their lists immutable automatically; snapshot these before sharing the result.
        words = List.copyOf(words);
        segments = List.copyOf(segments);
    }

    /**
     * @param word recognised text
     * @param start start in seconds
     * @param end end in seconds
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Word(String word, Double start, Double end) {
        public Word {
            Objects.requireNonNull(word, "word");
            interval(start, end);
        }
    }

    /**
     * @param text phrase text
     * @param start start in seconds
     * @param end end in seconds
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(String text, Double start, Double end) {
        public Segment {
            Objects.requireNonNull(text, "text");
            interval(start, end);
        }
    }

    private static void interval(Double start, Double end) {
        if (start == null || end == null || !Double.isFinite(start) || !Double.isFinite(end)
            || start < 0 || end < start || end > 31) {
            throw new IllegalArgumentException("Invalid subtitle timestamps");
        }
    }
}
