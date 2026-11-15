/* Subtitle formatting tests prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubtitleFormatterTest {

    @Test
    void shouldWrapAPhraseIntoTwoShortLines() {
        // Assemble
        String phrase = "This is a simple subtitle phrase that needs a second line.";
        SubtitleTranscript transcript = new SubtitleTranscript(4.0, List.of(),
            List.of(new SubtitleTranscript.Segment(phrase, 0.0, 4.0)));

        // Act
        SubtitleCue cue = new SubtitleFormatter().format(transcript, 0).findCue(0);

        // Assert
        assertThat(cue.text().replace('\n', ' ')).isEqualTo(phrase);
        assertThat(cue.text().split("\n")).hasSize(2)
            .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(42));
    }

    @Test
    void shouldKeepPhraseTextWhenAWordTimingIsMissing() {
        // Assemble
        SubtitleTranscript transcript = new SubtitleTranscript(3.0, List.of(
            new SubtitleTranscript.Word("Keep", 0.0, 0.5),
            new SubtitleTranscript.Word("word.", 2.0, 2.5)),
            List.of(new SubtitleTranscript.Segment("Keep missing word.", 0.0, 3.0)));

        // Act
        SubtitleTrack track = new SubtitleFormatter().format(transcript, 0);

        // Assert
        assertThat(track.findCue(0).text()).isEqualTo("Keep missing word.");
    }

    @Test
    void shouldApplyTheChunkOffsetAndKeepPauses() {
        // Assemble
        SubtitleTranscript transcript = new SubtitleTranscript(6.0, List.of(
            new SubtitleTranscript.Word("Hello", 0.5, 0.8),
            new SubtitleTranscript.Word("there.", 0.8, 1.5),
            new SubtitleTranscript.Word("Later.", 4.0, 4.2)),
            List.of(new SubtitleTranscript.Segment("Hello there. Later.", 0.0, 6.0)));

        // Act
        SubtitleTrack track = new SubtitleFormatter().format(transcript, 5_000_000);

        // Assert
        assertThat(track.findCue(5_499_999)).isNull();
        assertThat(track.findCue(5_500_000)).isEqualTo(new SubtitleCue(5_500_000, 6_500_000, "Hello there."));
        assertThat(track.findCue(8_000_000)).isNull();
        assertThat(track.findCue(9_000_000)).isEqualTo(new SubtitleCue(9_000_000, 10_000_000, "Later."));
    }

    @Test
    void shouldReturnAnEmptyTrackForSilence() {
        // Assemble
        SubtitleTranscript silence = new SubtitleTranscript(1.0, List.of(), List.of());

        // Act
        SubtitleTrack track = new SubtitleFormatter().format(silence, 0);

        // Assert
        assertThat(track.isEmpty()).isTrue();
    }
}
