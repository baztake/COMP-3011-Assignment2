/* Subtitle track tests prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubtitleTrackTest {

    @Test
    void shouldSupportSeekingBackwards() {
        // Assemble
        SubtitleCue first = new SubtitleCue(0, 2_000_000, "First.");
        SubtitleCue second = new SubtitleCue(3_000_000, 5_000_000, "Second.");
        SubtitleTrack track = new SubtitleTrack(List.of(first, second));

        // Act
        SubtitleCue later = track.findCue(4_000_000);
        SubtitleCue earlier = track.findCue(1_000_000);
        SubtitleCue afterEnd = track.findCue(5_000_000);

        // Assert
        assertThat(later).isEqualTo(second);
        assertThat(earlier).isEqualTo(first);
        assertThat(afterEnd).isNull();
    }

    @Test
    void shouldReturnNoCueFromAnEmptyTrack() {
        // Assemble
        SubtitleTrack track = new SubtitleTrack(List.of());

        // Act
        SubtitleCue cue = track.findCue(0);

        // Assert
        assertThat(cue).isNull();
    }
}
