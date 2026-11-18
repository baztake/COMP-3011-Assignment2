/* Subtitle client tests prepared by Bailey Staykov, a1886621, with assistance from OpenAI Codex (GPT-6.1 Sol). */
package comp3011;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class SubtitleClientTest {

    @RegisterExtension
    static WireMockExtension server = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @Test
    void shouldUploadMultipartAudioAndReadTimings() throws Exception {
        // Assemble
        server.stubFor(post("/api/v1/subtitles").willReturn(okJson(SubtitleTestMedia.TRANSCRIPT)));
        byte[] audio = "RIFF-test-audio".getBytes(StandardCharsets.UTF_8);
        try (SubtitleClient client = new SubtitleClient(endpoint())) {
            // Act
            SubtitleTranscript result = client.transcribe(audio);

            // Assert
            assertThat(result.duration()).isEqualTo(4.0);
            assertThat(result.words().getFirst().word()).isEqualTo("Current.");
            server.verify(postRequestedFor(urlEqualTo("/api/v1/subtitles"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBody(containing("name=\"file\""))
                .withRequestBody(containing("filename=\"audio.wav\""))
                .withRequestBody(containing("RIFF-test-audio")));
        }
    }

    @Test
    void shouldReportServerFailureWithoutReturningItsBody() {
        // Assemble
        server.stubFor(post("/api/v1/subtitles").willReturn(aResponse().withStatus(503).withBody("private details")));
        try (SubtitleClient client = new SubtitleClient(endpoint())) {
            // Act
            Throwable failure = catchThrowable(() -> client.transcribe(new byte[]{1}));

            // Assert
            assertThat(failure).isInstanceOf(IOException.class).hasMessageContaining("HTTP 503");
            assertThat(failure.getMessage()).doesNotContain("private details");
            server.verify(1, postRequestedFor(urlEqualTo("/api/v1/subtitles")));
        }
    }

    @Test
    void shouldRetryWhenProviderConnectivityReturns() throws Exception {
        // Assemble
        server.stubFor(post("/api/v1/subtitles").inScenario("Recovery")
            .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("Connected")
            .willReturn(serviceUnavailable().withHeader("Retry-After", "3")));
        server.stubFor(post("/api/v1/subtitles").inScenario("Recovery")
            .whenScenarioStateIs("Connected").willReturn(okJson(SubtitleTestMedia.TRANSCRIPT)));

        try (SubtitleClient client = new SubtitleClient(endpoint())) {
            // Act
            SubtitleTranscript result = client.transcribe(new byte[]{1});

            // Assert
            assertThat(result.words().getFirst().word()).isEqualTo("Current.");
            server.verify(2, postRequestedFor(urlEqualTo("/api/v1/subtitles")));
        }
    }

    private URI endpoint() {
        return URI.create(server.baseUrl() + "/api/v1/subtitles");
    }
}
