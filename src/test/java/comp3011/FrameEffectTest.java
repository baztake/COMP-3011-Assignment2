/*
 * Unit tests for the FrameEffect class.
 * Created by Bailey Staykov, a1886621, with assistance from GPT-6.1 Sol.
 */
package comp3011;

import org.junit.jupiter.api.Test;

import static comp3011.FrameEffect.*;
import static org.assertj.core.api.Assertions.assertThat;

public class FrameEffectTest {

    @Test
    void parseArgs_withIndividualOptions_shouldReturnCorrectEffect() {
        String[] shortOptions = {
                "-n", "-s", "-f", "-w", "-y", "-v",
                "-d", "-j", "-m", "-b", "-p"
        };

        String[] longOptions = {
                "--number-frames",
                "--scratch-frames",
                "--flicker-frames",
                "--black-and-white",
                "--yellow-frames",
                "--vignette-frames",
                "--dust-frames",
                "--jitter-frames",
                "--mottle-frames",
                "--bleed-frames",
                "--pepper-frames"
        };

        FrameEffect[] expected = {
                NUMBER, SCRATCH, FLICKER, BLACK_AND_WHITE,
                YELLOW, VIGNETTE, DUST, JITTER,
                MOTTLE, BLEED, PEPPER
        };

        for (int i = 0; i < expected.length; i++) {
            assertThat(FrameEffect.parseArgs(shortOptions[i]))
                    .as("Short option %s", shortOptions[i])
                    .containsExactly(expected[i]);

            assertThat(FrameEffect.parseArgs(longOptions[i]))
                    .as("Long option %s", longOptions[i])
                    .containsExactly(expected[i]);
        }
    }

    @Test
    void parseArgs_withStackedOptions_shouldPreserveOrderAndDuplicates() {
        assertThat(FrameEffect.parseArgs("-jfddn"))
                .containsExactly(JITTER, FLICKER, DUST, DUST, NUMBER);
    }

    @Test
    void parseArgs_withAssignmentExample_shouldPreserveEntireSequence() {
        assertThat(FrameEffect.parseArgs("-nssnfwyvdjmbp"))
                .containsExactly(
                        NUMBER, SCRATCH, SCRATCH, NUMBER,
                        FLICKER, BLACK_AND_WHITE, YELLOW,
                        VIGNETTE, DUST, JITTER, MOTTLE,
                        BLEED, PEPPER
                );
    }

    @Test
    void parseArgs_withUnknownLetter_shouldRejectEntireStack() {
        String[] options = {
                "-q", "-jq", "-qj", "-jfqd", "-jfddnq"
        };

        for (String option : options) {
            assertThat(FrameEffect.parseArgs(option))
                    .as("Invalid stack %s", option)
                    .isEmpty();
        }
    }

    @Test
    void parseArgs_withNonEffectInput_shouldReturnEmptyList() {
        String[] inputs = {
                null,
                "",
                "-",
                "--",
                "video.mp4",
                "jfddn",
                "--unknown",
                "--number",
                "--number-frames-extra",
                "--NUMBER-FRAMES",
                "-N",
                "-a",
                "--audio",
                "-x",
                "--maximise",
                "-h",
                "--help",
                "-1",
                "--monitor-1",
                "-2",
                "--monitor-2"
        };

        for (String input : inputs) {
            assertThat(FrameEffect.parseArgs(input))
                    .as("Non-effect input %s", input)
                    .isEmpty();
        }
    }

    @Test
    void parseArgs_withSeparateCalls_shouldKeepResultsIndependent() {
        var first = FrameEffect.parseArgs("-jf");
        var second = FrameEffect.parseArgs("-dd");

        assertThat(first).containsExactly(JITTER, FLICKER);
        assertThat(second).containsExactly(DUST, DUST);
    }
}