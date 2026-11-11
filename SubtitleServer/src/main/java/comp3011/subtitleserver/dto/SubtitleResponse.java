/* Subtitle server prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. */
package comp3011.subtitleserver.dto;

import comp3011.subtitleserver.dto.OpenAiTranscriptionResult.Segment;
import comp3011.subtitleserver.dto.OpenAiTranscriptionResult.Word;

import java.util.List;
import java.util.Objects;

/**
 * Filtered speech timings. The player adds the chunk's offset to these second-based timestamps.
 *
 * @param duration original chunk duration, including suppressed speech and silence
 * @param language detected language
 * @param text     accepted segment text
 * @param words    words belonging to accepted segments
 * @param segments accepted phrases, retaining confidence values
 */
public record SubtitleResponse(
    double duration,
    String language,
    String text,
    List<Word> words,
    List<Segment> segments
) {
    public SubtitleResponse {
        if (!Double.isFinite(duration) || duration <= 0) {
            throw new IllegalArgumentException("Invalid duration");
        }
        Objects.requireNonNull(text, "text");
        words = List.copyOf(words);
        segments = List.copyOf(segments);
    }
}
