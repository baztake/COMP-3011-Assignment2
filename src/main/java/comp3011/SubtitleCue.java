/* Subtitle support prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. */
package comp3011;

import java.util.Objects;

/**
 * One already formatted subtitle on the media timeline.
 *
 * @param startUs inclusive start in microseconds from the beginning of the media
 * @param endUs exclusive end in microseconds from the beginning of the media
 * @param text one or two lines, each containing at most 42 Unicode code points
 */
public record SubtitleCue(long startUs, long endUs, String text) {
    public SubtitleCue {
        Objects.requireNonNull(text, "text");
        // Cues arrive already formatted; reject invalid data rather than silently dropping text here.
        if (startUs < 0 || endUs <= startUs || endUs - startUs < 1_000_000L
            || endUs - startUs > 7_000_000L) {
            throw new IllegalArgumentException("Subtitle duration must be between one and seven seconds");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length > 2) {
            throw new IllegalArgumentException("Subtitles may have at most two lines");
        }
        for (String line : lines) {
            if (line.isBlank() || line.contains("\r") || line.codePointCount(0, line.length()) > 42) {
                throw new IllegalArgumentException("Subtitle lines must contain between 1 and 42 characters");
            }
        }
    }
}
