/*
 * Unit tests for the FrameEffect class.
 * Created by Bailey Staykov, a1886621, in collaboration with GPT-6.1 Sol
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
    void fromOption_withUnsupportedInput_shouldReturnNull() {
        String[] inputs = {
            null,
            "",
            "video.mp4",
            "-q",
            "--unknown",
            "--NUMBER-FRAMES",
            "--number-frames-extra",
            "--audio",
            "-jf"
        };

        for (String input : inputs) {
            assertThat(FrameEffect.fromOption(input))
                .as("Unsupported effect option %s", input)
                .isNull();
        }
    }

    @Test
    void createProcessor_shouldCreateCorrectTypeAndIndependentInstances() {
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
            FrameProcessor first = effects[i].createProcessor(0);
            FrameProcessor second = effects[i].createProcessor(0);

            assertThat(first)
                .as("Processor created for %s", effects[i])
                .isExactlyInstanceOf(expectedTypes[i]);

            assertThat(first)
                .as("Equivalent worker chains need separate %s instances", effects[i])
                .isNotSameAs(second);
        }
    }
}