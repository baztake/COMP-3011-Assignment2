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
    void fromOption_withIndividualOptions_shouldReturnCorrectEffect() {
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
            assertThat(FrameEffect.fromOption(shortOptions[i]))
                    .as("Short option %s", shortOptions[i])
                    .isEqualTo(expected[i]);

            assertThat(FrameEffect.fromOption(longOptions[i]))
                    .as("Long option %s", longOptions[i])
                    .isEqualTo(expected[i]);
        }
    }

    @Test
    void fromOption_withStackedOptions_shouldReturnNull() {
        String[] options = {
                "-jf",
                "-dd",
                "-jfddn",
                "-nssnfwyvdjmbp",
                "-ajf",
                "-jq"
        };

        for (String option : options) {
            assertThat(FrameEffect.fromOption(option))
                    .as("Stacked option %s must be split by the controller", option)
                    .isNull();
        }
    }

    @Test
    void fromOption_withNonEffectInput_shouldReturnNull() {
        String[] inputs = {
                null,
                "",
                "-",
                "--",
                "video.mp4",
                "n",
                "number-frames",
                "--unknown",
                "-q",
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
            assertThat(FrameEffect.fromOption(input))
                    .as("Non-effect input %s", input)
                    .isNull();
        }
    }

    @Test
    void createProcessor_shouldCreateCorrectProcessorType() {
        FrameEffect[] effects = {
                NUMBER, SCRATCH, FLICKER, BLACK_AND_WHITE,
                YELLOW, VIGNETTE, DUST, JITTER,
                MOTTLE, BLEED, PEPPER
        };

        Class<?>[] expectedTypes = {
                FrameNumberer.class,
                FrameScratcher.class,
                FrameFlickerer.class,
                FrameBlackAndWhiter.class,
                FrameYellower.class,
                FrameVignetter.class,
                FrameDuster.class,
                FrameJitterer.class,
                FrameMottler.class,
                FrameBleeder.class,
                FramePepperer.class
        };

        for (int i = 0; i < effects.length; i++) {
            assertThat(effects[i].createProcessor())
                    .as("Processor created for %s", effects[i])
                    .isExactlyInstanceOf(expectedTypes[i]);
        }
    }

    @Test
    void createProcessor_whenCalledRepeatedly_shouldCreateDistinctInstances() {
        for (FrameEffect effect : FrameEffect.values()) {
            FrameProcessor first = effect.createProcessor();
            FrameProcessor second = effect.createProcessor();

            assertThat(first)
                    .as("Repeated %s selections need separate instances", effect)
                    .isNotSameAs(second);
        }
    }
}