/*
 * Unit tests for the CommandLineController class.
 * Created by Bailey Staykov, a1886621, with assistance from GPT-6.1 Sol.
 */
package comp3011;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CommandLineControllerTest {

    @TempDir
    Path tempDir;

    private File videoFile;

    @BeforeEach
    void setUp() throws IOException {
        videoFile = Files.createFile(
                tempDir.resolve("video with spaces.mp4")
        ).toFile();
    }

    @Test
    void init_withVideoOnly_shouldUseDefaultOptions() {
        CommandLineController controller = controllerWithVideo();

        assertThat(controller.getVideoFile()).isEqualTo(videoFile);
        assertThat(controller.isAudioRequested()).isFalse();
        assertThat(controller.isMaximiseRequested()).isFalse();
        assertThat(controller.getDisplayId()).isNull();
        assertThat(controller.getFrameEffects()).isEmpty();
        assertValid(controller);
    }

    @Test
    void init_withBasicOptions_shouldAcceptShortLongAndMixedForms() {
        String[][] combinations = {
                {"--audio", "--maximise"},
                {"-a", "-x"},
                {"--audio", "-x"},
                {"-a", "--maximise"},
                {"--maximise", "--audio"}
        };

        for (String[] options : combinations) {
            CommandLineController controller = controllerWithVideo(options);

            assertThat(controller.isAudioRequested()).isTrue();
            assertThat(controller.isMaximiseRequested()).isTrue();
            assertThat(controller.getVideoFile()).isEqualTo(videoFile);
            assertValid(controller);
        }
    }

    @Test
    void init_withMonitorOptions_shouldAcceptBothAliases() {
        String[][] aliases = {
                {"-1", "--monitor-1"},
                {"-2", "--monitor-2"}
        };

        for (int i = 0; i < aliases.length; i++) {
            for (String option : aliases[i]) {
                CommandLineController controller = controllerWithVideo(option);

                assertThat(controller.getDisplayId()).isEqualTo(i + 1);
                assertValid(controller);
            }
        }
    }

    @Test
    void init_withRepeatedMonitorOption_shouldRemainValid() {
        CommandLineController controller =
                controllerWithVideo("-1", "--monitor-1", "-1");

        assertThat(controller.getDisplayId()).isEqualTo(1);
        assertValid(controller);
    }

    @Test
    void init_withConflictingMonitors_shouldReportError() {
        String[][] combinations = {
                {"-1", "-2"},
                {"--monitor-1", "--monitor-2"},
                {"-2", "--monitor-1"}
        };

        for (String[] options : combinations) {
            CommandLineController controller = controllerWithVideo(options);

            assertThat(controller.getErrorMessage())
                    .isEqualTo("Only one display option can be used");
            assertInvalid(controller);
        }
    }

    @Test
    void init_withIndividualEffects_shouldAcceptShortAndLongForms() {
        String[][] aliases = {
                {"-n", "--number-frames"},
                {"-s", "--scratch-frames"},
                {"-f", "--flicker-frames"},
                {"-w", "--black-and-white"},
                {"-y", "--yellow-frames"},
                {"-v", "--vignette-frames"},
                {"-d", "--dust-frames"},
                {"-j", "--jitter-frames"},
                {"-m", "--mottle-frames"},
                {"-b", "--bleed-frames"},
                {"-p", "--pepper-frames"}
        };

        for (String[] pair : aliases) {
            List<FrameEffect> expected = expectedEffects(pair[1]);

            for (String option : pair) {
                CommandLineController controller = controllerWithVideo(option);

                assertThat(controller.getFrameEffects())
                        .containsExactlyElementsOf(expected);
                assertValid(controller);
            }
        }
    }

    @Test
    void init_withEquivalentEffectCombinations_shouldPreserveOrderAndRepeats() {
        String[][] combinations = {
                {
                        "--jitter-frames", "--flicker-frames",
                        "--dust-frames", "--dust-frames", "--number-frames"
                },
                {"-j", "-f", "-d", "-d", "-n"},
                {"-jfddn"},
                {"-jf", "--dust-frames", "-d", "--number-frames"}
        };

        List<FrameEffect> expected = expectedEffects(
                "--jitter-frames",
                "--flicker-frames",
                "--dust-frames",
                "--dust-frames",
                "--number-frames"
        );

        for (String[] options : combinations) {
            CommandLineController controller = controllerWithVideo(options);

            assertThat(controller.getFrameEffects())
                    .containsExactlyElementsOf(expected);
            assertValid(controller);
        }
    }

    @Test
    void init_withAssignmentExample_shouldPreserveEntireSequence() {
        CommandLineController controller =
                controllerWithVideo("-nssnfwyvdjmbp");

        assertThat(controller.getFrameEffects())
                .containsExactlyElementsOf(expectedEffects(
                        "--number-frames",
                        "--scratch-frames",
                        "--scratch-frames",
                        "--number-frames",
                        "--flicker-frames",
                        "--black-and-white",
                        "--yellow-frames",
                        "--vignette-frames",
                        "--dust-frames",
                        "--jitter-frames",
                        "--mottle-frames",
                        "--bleed-frames",
                        "--pepper-frames"
                ));

        assertValid(controller);
    }

    @Test
    void init_withLaunchOptionsBetweenEffects_shouldKeepEffectOrder() {
        CommandLineController controller = controllerWithVideo(
                "-j", "--audio", "-fd", "--maximise",
                "--monitor-2", "-dn"
        );

        assertThat(controller.isAudioRequested()).isTrue();
        assertThat(controller.isMaximiseRequested()).isTrue();
        assertThat(controller.getDisplayId()).isEqualTo(2);

        assertThat(controller.getFrameEffects())
                .containsExactlyElementsOf(expectedEffects(
                        "--jitter-frames",
                        "--flicker-frames",
                        "--dust-frames",
                        "--dust-frames",
                        "--number-frames"
                ));

        assertValid(controller);
    }

    @Test
    void init_withVideoBeforeOptions_shouldStillParseOptions() {
        CommandLineController controller = new CommandLineController(
                new String[]{
                        videoFile.getPath(), "--audio", "-jf", "--maximise"
                }
        );

        assertThat(controller.getVideoFile()).isEqualTo(videoFile);
        assertThat(controller.isAudioRequested()).isTrue();
        assertThat(controller.isMaximiseRequested()).isTrue();
        assertThat(controller.getFrameEffects())
                .containsExactlyElementsOf(expectedEffects(
                        "--jitter-frames", "--flicker-frames"
                ));

        assertValid(controller);
    }

    @Test
    void init_withHelpOnly_shouldSucceedWithoutLaunching() {
        for (String option : new String[]{"-h", "--help"}) {
            CommandLineController controller =
                    new CommandLineController(new String[]{option});

            assertThat(controller.getErrorMessage()).isNull();
            assertThat(controller.getExitCode()).isZero();
            assertThat(controller.getVideoFile()).isNull();
            assertThat(controller.shouldLaunchApplication()).isFalse();
        }
    }

    @Test
    void init_withoutVideo_shouldReportError() {
        String[][] combinations = {
                {},
                {"--audio"},
                {"--maximise", "-1"},
                {"-jfddn"}
        };

        for (String[] args : combinations) {
            CommandLineController controller = new CommandLineController(args);

            assertThat(controller.getErrorMessage())
                    .isEqualTo("No video file specified.");
            assertThat(controller.getVideoFile()).isNull();
            assertInvalid(controller);
        }
    }

    @Test
    void init_withMissingFile_shouldReportError() {
        File missing = tempDir.resolve("missing.mp4").toFile();

        CommandLineController controller = new CommandLineController(
                new String[]{"--audio", missing.getPath()}
        );

        assertThat(controller.getErrorMessage())
                .isEqualTo("File not found: " + missing.getPath());
        assertThat(controller.getVideoFile()).isNull();
        assertInvalid(controller);
    }

    @Test
    void init_withDirectoryInsteadOfFile_shouldReportError() {
        CommandLineController controller = new CommandLineController(
                new String[]{tempDir.toString()}
        );

        assertThat(controller.getErrorMessage())
                .isEqualTo("File not found: " + tempDir);
        assertThat(controller.getVideoFile()).isNull();
        assertInvalid(controller);
    }

    @Test
    void init_withMultipleFiles_shouldReportUsageError() throws IOException {
        File second = Files.createFile(
                tempDir.resolve("second.mp4")
        ).toFile();

        CommandLineController controller = new CommandLineController(
                new String[]{videoFile.getPath(), "-a", second.getPath()}
        );

        assertThat(controller.getErrorMessage())
                .isEqualTo("Usage: VideoPlayer [options] [video-file]");
        assertInvalid(controller);
    }

    @Test
    void init_withUnknownLetterInMixedStack_shouldReportError() {
        CommandLineController controller = controllerWithVideo("-ajq");

        assertThat(controller.getErrorMessage())
                .isEqualTo("Unknown option: -q");
        assertInvalid(controller);
    }

    @Test
    void getFrameEffects_shouldReturnImmutableList() {
        CommandLineController controller = controllerWithVideo("-jf");

        assertThatThrownBy(() -> controller.getFrameEffects().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(controller.getFrameEffects()).hasSize(2);
    }

    @Test
    void init_withMixedShortStack_shouldParseFlagsAndPreserveEffectOrder() {
        CommandLineController controller = controllerWithVideo("-jax2fddn");

        assertThat(controller.isAudioRequested()).isTrue();
        assertThat(controller.isMaximiseRequested()).isTrue();
        assertThat(controller.getDisplayId()).isEqualTo(2);

        assertThat(controller.getFrameEffects()).containsExactly(
                FrameEffect.JITTER,
                FrameEffect.FLICKER,
                FrameEffect.DUST,
                FrameEffect.DUST,
                FrameEffect.NUMBER
        );

        assertValid(controller);
    }

    private CommandLineController controllerWithVideo(String... options) {
        List<String> args = new ArrayList<>(List.of(options));
        args.add(videoFile.getPath());

        return new CommandLineController(args.toArray(new String[0]));
    }

    private List<FrameEffect> expectedEffects(String... options) {
        List<FrameEffect> expected = new ArrayList<>();

        for (String option : options) {
            FrameEffect effect = switch (option) {
                case "--number-frames" -> FrameEffect.NUMBER;
                case "--scratch-frames" -> FrameEffect.SCRATCH;
                case "--flicker-frames" -> FrameEffect.FLICKER;
                case "--black-and-white" -> FrameEffect.BLACK_AND_WHITE;
                case "--yellow-frames" -> FrameEffect.YELLOW;
                case "--vignette-frames" -> FrameEffect.VIGNETTE;
                case "--dust-frames" -> FrameEffect.DUST;
                case "--jitter-frames" -> FrameEffect.JITTER;
                case "--mottle-frames" -> FrameEffect.MOTTLE;
                case "--bleed-frames" -> FrameEffect.BLEED;
                case "--pepper-frames" -> FrameEffect.PEPPER;
                default -> throw new IllegalArgumentException(
                        "Unknown test option: " + option
                );
            };

            expected.add(effect);
        }

        return expected;
    }

    private void assertValid(CommandLineController controller) {
        assertThat(controller.getErrorMessage()).isNull();
        assertThat(controller.getExitCode()).isZero();
        assertThat(controller.shouldLaunchApplication()).isTrue();
    }

    private void assertInvalid(CommandLineController controller) {
        assertThat(controller.getErrorMessage()).isNotNull();
        assertThat(controller.getExitCode()).isEqualTo(1);
        assertThat(controller.shouldLaunchApplication()).isFalse();
    }
}