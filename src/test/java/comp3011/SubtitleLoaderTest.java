/* Subtitle loading tests prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.testfx.api.FxToolkit;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class SubtitleLoaderTest {

    @RegisterExtension
    static WireMockExtension server = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private SubtitleLoader loader;
    private File video;
    private String previousUrl;

    @BeforeAll
    static void startJavaFx() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @BeforeEach
    void prepareLoader(@TempDir Path directory) throws Exception {
        video = SubtitleTestMedia.video(directory, 1, true);
        previousUrl = System.getProperty("comp3011.subtitles.url");
        System.setProperty("comp3011.subtitles.url", server.baseUrl() + "/api/v1/subtitles");
        loader = FxToolkit.setupFixture(SubtitleLoader::new);
    }

    @AfterEach
    void closeLoader() throws Exception {
        FxToolkit.setupFixture(loader::close);
        if (previousUrl == null) {
            System.clearProperty("comp3011.subtitles.url");
        } else {
            System.setProperty("comp3011.subtitles.url", previousUrl);
        }
    }

    @Test
    void shouldDeliverTheLoadedTrack() throws Exception {
        // Assemble
        server.stubFor(post("/api/v1/subtitles").willReturn(okJson(SubtitleTestMedia.TRANSCRIPT)));
        Consumer<SubtitleTrack> ready = mock();
        Consumer<String> failed = mock();
        ArgumentCaptor<SubtitleTrack> result = ArgumentCaptor.forClass(SubtitleTrack.class);

        // Act
        FxToolkit.setupFixture(() -> loader.load(video, ready, failed));

        // Assert
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(ready).accept(result.capture()));
        assertThat(result.getValue().findCue(0).text()).isEqualTo("Current.");
        verifyNoInteractions(failed);
    }

    @Test
    void shouldReportAnUnavailableServer() throws Exception {
        // Assemble
        server.stubFor(post("/api/v1/subtitles").willReturn(serviceUnavailable()));
        Consumer<SubtitleTrack> ready = mock();
        Consumer<String> failed = mock();

        // Act
        FxToolkit.setupFixture(() -> loader.load(video, ready, failed));

        // Assert
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(failed).accept(contains("retry")));
        verifyNoInteractions(ready);
    }

}
