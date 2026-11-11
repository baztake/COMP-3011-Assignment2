/* Subtitle server tests prepared with assistance from OpenAI Codex. */
package comp3011.subtitleserver;

import com.sun.net.httpserver.HttpServer;
import comp3011.subtitleserver.service.SubtitleStore;
import comp3011.subtitleserver.service.SubtitleService;
import comp3011.subtitleserver.service.OpenAiTranscriptionService;
import comp3011.subtitleserver.dto.SubtitleResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises real multipart HTTP requests through Spring and out to a local fake provider.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(20)
class SubtitleApiTest {

    private static final String TRANSCRIPT = """
        {
          "task":"transcribe", "duration":12.5, "language":"english",
          "text":"Hello. Ghost. Keep. Boundary. Other.",
          "usage":{"type":"duration","seconds":13.0},
          "words":[
            {"word":"Hello.","start":0.0,"end":1.0},
            {"word":"Ghost.","start":2.0,"end":3.0},
            {"word":"Keep.","start":4.0,"end":5.0},
            {"word":"Boundary.","start":6.0,"end":7.0},
            {"word":"Other.","start":8.0,"end":9.0}
          ],
          "segments":[
            {"id":0,"start":0.0,"end":1.5,"text":" Hello.","no_speech_prob":0.1,"avg_logprob":-0.2},
            {"id":1,"start":2.0,"end":3.5,"text":" Ghost.","no_speech_prob":0.9,"avg_logprob":-1.2},
            {"id":2,"start":4.0,"end":5.5,"text":" Keep.","no_speech_prob":0.9,"avg_logprob":-0.2},
            {"id":3,"start":6.0,"end":7.5,"text":" Boundary.","no_speech_prob":0.6,"avg_logprob":-1.2},
            {"id":4,"start":8.0,"end":9.5,"text":" Other.","no_speech_prob":0.9,"avg_logprob":-1.0}
          ]
        }
        """;
    @TempDir
    static Path directory;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final AtomicInteger providerCalls = new AtomicInteger();
    private static final AtomicInteger providerStatus = new AtomicInteger(200);
    private static final AtomicReference<String> providerBody = new AtomicReference<>(TRANSCRIPT);
    private static final AtomicReference<String> receivedBody = new AtomicReference<>();
    private static final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> providerGate = new AtomicReference<>();
    private static final ExecutorService PROVIDER_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static HttpServer provider;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.setExecutor(PROVIDER_THREADS);
        provider.createContext("/v1/audio/transcriptions", exchange -> {
            providerCalls.incrementAndGet();
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            CountDownLatch gate = providerGate.get();
            if (gate != null) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] response = providerBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(providerStatus.get(), response.length);
            try (exchange) {
                exchange.getResponseBody().write(response);
            }
        });
        provider.start();
        registry.add("transcription.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
        registry.add("transcription.api-key", () -> "test-only-key");
        registry.add("transcription.read-timeout-seconds", () -> 1);
        registry.add("subtitles.store-file", () -> directory.resolve("state.json").toString());
        registry.add("spring.servlet.multipart.max-file-size", () -> "1KB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "4KB");
    }

    @BeforeEach
    void resetProvider() {
        providerStatus.set(200);
        providerBody.set(TRANSCRIPT);
        providerGate.set(null);
    }

    @AfterAll
    static void stopProvider() {
        provider.stop(0);
        PROVIDER_THREADS.close();
    }

    @Test
    void shouldRequestWhisperTimingsFilterOnlyCombinedThresholdsAndReportUsage() throws Exception {
        // Assemble
        double before = usage();
        int callsBefore = providerCalls.get();

        // Act
        HttpResponse<String> response = upload("file", "first.WAV", "first audio");
        JsonNode result = JSON.readTree(response.body());

        // Assert
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(result.get("duration").asDouble()).isEqualTo(12.5);
        assertThat(result.get("text").asText()).isEqualTo("Hello. Keep. Boundary. Other.");
        assertThat(result.get("segments").size()).isEqualTo(4);
        assertThat(result.get("words").size()).isEqualTo(4);
        assertThat(response.body()).doesNotContain("Ghost", "test-only-key", "suppressed");
        assertThat(usage()).isEqualTo(before + 13.0);
        assertThat(providerCalls.get()).isEqualTo(callsBefore + 1);
        assertThat(receivedAuthorization.get()).isEqualTo("Bearer test-only-key");
        assertThat(receivedBody.get()).contains("name=\"model\"", "whisper-1", "verbose_json",
            "name=\"timestamp_granularities[]\"", "word", "segment", "filename=\"audio.wav\"", "first audio");
        assertThat(receivedBody.get().split("name=\"timestamp_granularities\\[\\]\"", -1)).hasSize(3);
        SubtitleStore restarted = new SubtitleStore(directory.resolve("state.json").toString(), 256);
        assertThat(restarted.getUsage()).isEqualTo(usage());

        // Reuse the entry created through HTTP, rather than duplicating the server's hash algorithm in the test.
        OpenAiTranscriptionService offlineProvider = new OpenAiTranscriptionService(RestClient.create(), "");
        SubtitleService offlineService = new SubtitleService(offlineProvider, restarted);
        MockMultipartFile cachedAudio = new MockMultipartFile("file", "renamed.wav", "audio/wav",
            "first audio".getBytes(StandardCharsets.UTF_8));
        assertThat(offlineService.transcribe(cachedAudio)).isEqualTo(JSON.readValue(response.body(), SubtitleResponse.class));
        assertThatThrownBy(() -> offlineService.transcribe(
            new MockMultipartFile("file", "new.wav", "audio/wav", new byte[]{1})))
            .hasMessageContaining("OPENAI_API_KEY");
        assertThat(restarted.getUsage()).isEqualTo(before + 13.0);
    }

    @Test
    void shouldShareOneTranscriptionAcrossConcurrentClientsAndServeCachedDataDuringOutage() throws Exception {
        // Assemble
        double before = usage();
        int callsBefore = providerCalls.get();
        List<Future<HttpResponse<String>>> requests = new ArrayList<>();

        // Act
        try (ExecutorService clients = Executors.newFixedThreadPool(6)) {
            for (int index = 0; index < 6; index++) {
                int client = index;
                requests.add(clients.submit(() -> upload("file", "client-" + client + ".wav", "shared audio")));
            }
            for (Future<HttpResponse<String>> request : requests) {
                assertThat(request.get(8, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            }
        }
        providerStatus.set(503);
        HttpResponse<String> cached = upload("file", "renamed.wav", "shared audio");

        // Assert
        assertThat(cached.statusCode()).isEqualTo(200);
        assertThat(providerCalls.get()).isEqualTo(callsBefore + 1);
        assertThat(usage()).isEqualTo(before + 13.0);
    }

    @Test
    void shouldNotCacheFailuresAndShouldRecoverWithoutLeakingProviderErrors() throws Exception {
        // Assemble
        double before = usage();
        providerStatus.set(500);
        providerBody.set("{\"error\":\"provider-secret-detail\"}");

        // Act
        HttpResponse<String> failed = upload("file", "recover.wav", "recover audio");
        double afterFailure = usage();
        providerStatus.set(200);
        providerBody.set(TRANSCRIPT);
        HttpResponse<String> recovered = upload("file", "recover.wav", "recover audio");

        // Assert
        assertThat(failed.statusCode()).isEqualTo(502);
        assertThat(failed.body()).doesNotContain("provider-secret-detail", "test-only-key");
        assertThat(afterFailure).isEqualTo(before);
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(usage()).isEqualTo(before + 13.0);
    }

    @Test
    void shouldCountFractionalDurationForSilenceWhenUsageIsOmitted() throws Exception {
        // Assemble
        double before = usage();
        providerBody.set("{\"duration\":0.75,\"language\":\"english\",\"text\":\"\"}");

        // Act
        HttpResponse<String> response = upload("file", "silence.wav", "silent audio");
        JsonNode result = JSON.readTree(response.body());

        // Assert
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(result.get("text").asText()).isEmpty();
        assertThat(result.get("words").size()).isZero();
        assertThat(result.get("segments").size()).isZero();
        assertThat(usage()).isEqualTo(before + 0.75);
    }

    @Test
    void shouldRejectInvalidUploadsBeforeCallingTheProvider() throws Exception {
        // Assemble
        int before = providerCalls.get();

        // Act and Assert
        assertThat(upload("file", "empty.wav", "").statusCode()).isEqualTo(400);
        assertThat(upload("wrong", "missing.wav", "audio").statusCode()).isEqualTo(400);
        assertThat(upload("file", "bad.txt", "audio").statusCode()).isEqualTo(415);
        assertThat(upload("file", "large.wav", "x".repeat(2048)).statusCode()).isEqualTo(413);
        assertThat(providerCalls.get()).isEqualTo(before);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/subtitles")).GET().build();
        HttpResponse<String> wrongMethod = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(wrongMethod.statusCode()).isEqualTo(405);
    }

    @Test
    void shouldRejectMalformedTimingDataAndRecoverAfterAProviderTimeout() throws Exception {
        // Assemble
        double before = usage();
        providerBody.set("{\"text\":\"Missing timestamps and duration\"}");

        // Act and Assert
        assertThat(upload("file", "malformed.wav", "malformed audio").statusCode()).isEqualTo(502);
        providerBody.set(TRANSCRIPT);
        CountDownLatch gate = new CountDownLatch(1);
        providerGate.set(gate);
        try {
            assertThat(upload("file", "timeout.wav", "timeout audio").statusCode()).isEqualTo(503);
            assertThat(usage()).isEqualTo(before);
        } finally {
            providerGate.set(null);
            gate.countDown();
        }
        assertThat(upload("file", "timeout.wav", "timeout audio").statusCode()).isEqualTo(200);
        assertThat(usage()).isEqualTo(before + 13.0);
    }

    private double usage() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/global/usage")).GET().build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.size()).isEqualTo(1);
        return body.get("usage").asDouble();
    }

    private HttpResponse<String> upload(String field, String filename, String audio) throws Exception {
        String boundary = "SubtitleTestBoundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field
            + "\"; filename=\"" + filename + "\"\r\nContent-Type: audio/wav\r\n\r\n" + audio + "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/subtitles")).timeout(Duration.ofSeconds(8))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
