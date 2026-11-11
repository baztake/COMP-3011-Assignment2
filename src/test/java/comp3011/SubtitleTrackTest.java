/* Subtitle tests prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex. */
package comp3011;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SubtitleTrackTest {
    @Test
    void shouldFindCuesAcrossBoundariesAndBackwardsSeeks() {
        // Assemble
        SubtitleCue first = new SubtitleCue(1_000_000, 3_000_000, "First phrase.");
        SubtitleCue second = new SubtitleCue(3_000_000, 5_000_000, "Second phrase.");
        ArrayList<SubtitleCue> cues = new ArrayList<>(List.of(second, first));
        SubtitleTrack track = new SubtitleTrack(cues);

        // Act
        cues.clear();

        // Assert
        assertThat(track.findCue(999_999)).isNull();
        assertThat(track.findCue(1_000_000)).isEqualTo(first);
        assertThat(track.findCue(2_999_999)).isEqualTo(first);
        assertThat(track.findCue(3_000_000)).isEqualTo(second);
        assertThat(track.findCue(5_000_000)).isNull();
        assertThat(track.findCue(1_500_000)).isEqualTo(first);
        assertThat(new SubtitleTrack(List.of()).findCue(0)).isNull();
    }

    @Test
    void shouldRejectInvalidFormattingDurationsAndOverlappingCues() {
        // Assemble
        SubtitleCue first = new SubtitleCue(0, 2_000_000, "First.");
        SubtitleCue overlapping = new SubtitleCue(1_000_000, 3_000_000, "Second.");

        // Act and Assert
        assertThatThrownBy(() -> new SubtitleTrack(List.of(first, overlapping)))
            .isInstanceOf(IllegalArgumentException.class);
        for (String invalid : List.of("", " ", "a\nb\nc", "x".repeat(43), "one\n")) {
            assertThatThrownBy(() -> new SubtitleCue(0, 2_000_000, invalid))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new SubtitleCue(-1, 2_000_000, "Text"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubtitleCue(0, 999_999, "Text"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubtitleCue(0, 7_000_001, "Text"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SubtitleCue(0, 7_000_000, "x".repeat(42))).isNotNull();
    }
}
