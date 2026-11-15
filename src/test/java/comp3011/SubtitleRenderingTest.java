/* Subtitle rendering tests prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SubtitleRenderingTest {

    @RegisterExtension
    static WireMockExtension server = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private VideoPlayerController controller;
    private ImageView videoImage;
    private ImageView captionImage;
    private Scene scene;
    private File video;
    private String previousMode;
    private String previousUrl;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @BeforeEach
    void saveProperties() {
        previousMode = System.getProperty("comp3011.playback");
        previousUrl = System.getProperty("comp3011.subtitles.url");
    }

    @AfterEach
    void closePlayer() throws Exception {
        try {
            if (controller != null) {
                FxToolkit.setupFixture(controller::shutdown);
                // Awaitility retries until asynchronous native cleanup releases the Windows file handle.
                await().atMost(Duration.ofSeconds(5)).ignoreExceptions().until(() -> Files.deleteIfExists(video.toPath()));
            }
        } finally {
            restoreProperty("comp3011.playback", previousMode);
            restoreProperty("comp3011.subtitles.url", previousUrl);
        }
    }

    @Test
    void shouldKeepVideoBoundsWhenSubtitleStatusChanges() throws Exception {
        FxToolkit.setupFixture(() -> {
            // Assemble
            VideoPlayerView view = new VideoPlayerView();
            Scene layoutScene = new Scene(view.getRoot(), 960, 430);
            BorderPane content = (BorderPane) ((StackPane) view.getRoot()).getChildren().getFirst();
            StackPane surface = (StackPane) content.getCenter();
            ImageView video = (ImageView) surface.getChildren().getFirst();
            view.setImage(new WritableImage(1920, 800));
            layoutScene.getRoot().resize(960, 430);
            layoutScene.getRoot().applyCss();
            layoutScene.getRoot().layout();
            Bounds original = video.getBoundsInParent();

            // Act
            view.setSubtitleStatus("Loading subtitles...");
            layoutScene.getRoot().layout();

            // Assert
            assertThat(video.getBoundsInParent()).isEqualTo(original);
        });
    }

    @Test
    void shouldDrawNativeTextDuringItsCue() {
        // Assemble
        FrameSubtitler renderer = new FrameSubtitler(new SubtitleTrack(List.of(
            new SubtitleCue(1_000_000, 3_000_000, "Outlined subtitle."))));
        try (Frame frame = new Frame(320, 180, Frame.DEPTH_UBYTE, 3)) {
            ByteBuffer pixels = (ByteBuffer) frame.image[0];
            byte[] original = new byte[pixels.capacity()];
            Arrays.fill(original, (byte) 64);
            pixels.put(0, original);

            // Act
            renderer.process(frame, new InfoFrame(0, 1_000_000));
            byte[] result = new byte[pixels.capacity()];
            pixels.get(0, result);

            // Assert
            assertThat(result).isNotEqualTo(original);
            assertThat(result[0]).isEqualTo((byte) 64);
        }
    }

    @Test
    void shouldHideSubtitlesWithoutChangingThePausedFrame(@TempDir Path directory) throws Exception {
        // Assemble
        playAndPause(directory);
        Image frame = FxToolkit.setupFixture(() -> videoImage.getImage());

        // Act
        FxToolkit.setupFixture(() -> controller.getView().getSubtitleButton().fire());

        // Assert
        FxToolkit.setupFixture(() -> {
            assertThat(captionImage.getImage()).isNull();
            assertThat(videoImage.getImage()).isSameAs(frame);
        });
    }

    @Test
    void shouldRestoreSubtitlesWithTheHotkeyWhilePaused(@TempDir Path directory) throws Exception {
        // Assemble
        playAndPause(directory);
        Image caption = FxToolkit.setupFixture(() -> captionImage.getImage());
        Image frame = FxToolkit.setupFixture(() -> videoImage.getImage());
        FxToolkit.setupFixture(() -> controller.getView().getSubtitleButton().fire());

        // Act
        FxToolkit.setupFixture(() -> Event.fireEvent(scene, new KeyEvent(KeyEvent.KEY_PRESSED,
            "s", "s", KeyCode.S, false, false, false, false)));

        // Assert
        FxToolkit.setupFixture(() -> {
            assertThat(captionImage.getImage()).isSameAs(caption);
            assertThat(videoImage.getImage()).isSameAs(frame);
            assertThat(controller.getView().getSubtitleButton().getAccessibleText()).isEqualTo("Subtitles on");
        });
    }

    @Test
    void shouldReusePrefetchedCaptionsAfterSeekingBackwards(@TempDir Path directory) throws Exception {
        // Assemble: load the opening chunk and its prefetch, then seek into the second chunk.
        playAndPause(directory, 35);
        Image openingCaption = FxToolkit.setupFixture(() -> captionImage.getImage());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            server.verify(2, postRequestedFor(urlEqualTo("/api/v1/subtitles"))));
        seekForward(6);
        await().atMost(Duration.ofSeconds(5)).until(() -> FxToolkit.setupFixture(() ->
            captionImage.getImage() != null && captionImage.getImage() != openingCaption));
        Image laterFrame = FxToolkit.setupFixture(() -> videoImage.getImage());

        // Act
        FxToolkit.setupFixture(() -> controller.getView().getStartOverButton().fire());

        // Assert: returning to the opening chunk must not upload it again.
        await().atMost(Duration.ofSeconds(5)).until(() -> FxToolkit.setupFixture(() ->
            videoImage.getImage() != laterFrame && captionImage.getImage() != null));
        server.verify(2, postRequestedFor(urlEqualTo("/api/v1/subtitles")));
    }

    @Test
    void shouldLoadAnUncachedChunkAfterSeeking(@TempDir Path directory) throws Exception {
        // Assemble
        playAndPause(directory, 65);
        Image openingCaption = FxToolkit.setupFixture(() -> captionImage.getImage());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            server.verify(2, postRequestedFor(urlEqualTo("/api/v1/subtitles"))));

        // Act: jump past the prefetched second chunk into the final chunk.
        seekForward(12);

        // Assert
        await().atMost(Duration.ofSeconds(5)).until(() -> FxToolkit.setupFixture(() ->
            captionImage.getImage() != null && captionImage.getImage() != openingCaption));
        server.verify(3, postRequestedFor(urlEqualTo("/api/v1/subtitles")));
    }

    private void playAndPause(Path directory) throws Exception {
        playAndPause(directory, 4);
    }

    private void seekForward(int steps) throws Exception {
        FxToolkit.setupFixture(() -> {
            for (int step = 0; step < steps; step++) {
                controller.getView().getForwardFiveButton().fire();
            }
        });
    }

    private void playAndPause(Path directory, int seconds) throws Exception {
        video = SubtitleTestMedia.video(directory, seconds, true);
        server.stubFor(post("/api/v1/subtitles").willReturn(okJson(SubtitleTestMedia.TRANSCRIPT)));
        System.setProperty("comp3011.playback", "multi");
        System.setProperty("comp3011.subtitles.url", server.baseUrl() + "/api/v1/subtitles");

        FxToolkit.setupFixture(() -> {
            controller = new VideoPlayerController(false, List.of(FrameEffect.SUBTITLE), (width, height) -> {});
            StackPane root = (StackPane) controller.getView().getRoot();
            BorderPane content = (BorderPane) root.getChildren().getFirst();
            StackPane surface = (StackPane) content.getCenter();
            videoImage = (ImageView) surface.getChildren().getFirst();
            captionImage = (ImageView) surface.getChildren().get(1);
            scene = new Scene(root);
            controller.installKeyboardShortcuts(scene);
            controller.play(video);
        });

        await().atMost(Duration.ofSeconds(5)).until(() ->
            FxToolkit.setupFixture(() -> videoImage.getImage() != null));
        FxToolkit.setupFixture(() -> controller.getView().getPausePlayButton().fire());
        await().atMost(Duration.ofSeconds(5)).until(() ->
            FxToolkit.setupFixture(() -> captionImage.getImage() != null));
    }

    private void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
