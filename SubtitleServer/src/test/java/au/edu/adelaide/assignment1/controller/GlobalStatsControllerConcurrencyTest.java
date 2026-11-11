package au.edu.adelaide.assignment1.controller;

import au.edu.adelaide.assignment1.dto.GlobalStatsResponse;
import au.edu.adelaide.assignment1.dto.TranscriptionResult;
import au.edu.adelaide.assignment1.service.TranscriptionService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
    "transcription.service.provider.base-url=https://fake-stt.example.com",
    "transcription.service.provider.api-key=test-key-not-real",
    "server.tomcat.threads.max=300"
})
class GlobalStatsControllerConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(GlobalStatsControllerConcurrencyTest.class);
    private static final long INPUT_TOKENS_PER_CALL = 3L;
    private static final long OUTPUT_TOKENS_PER_CALL = 7L;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @TestConfiguration
    static class StubSttConfig {
        @Bean
        @Primary
        TranscriptionService stubTranscriptionService() {
            return _ -> new TranscriptionResult() {
                @Override public String text() { return "stub"; }
                @Override public long inputTokens() { return INPUT_TOKENS_PER_CALL; }
                @Override public long outputTokens() { return OUTPUT_TOKENS_PER_CALL; }
            };
        }
    }

    @Test
    void concurrentTranscribeRequests_globalStatsAggregateWithoutLostUpdates() {
        // Simulate a high number of concurrent transcription requests to verify that global stats are aggregated correctly
        int requestCount = 100;
        String transcribeUrl = "http://localhost:" + port + "/api/v1/transcribe";
        String statsUrl = "http://localhost:" + port + "/api/v1/global/stats";
        ExecutorService pool = Executors.newFixedThreadPool(50);
        log.info("Starting {} concurrent transcription requests to {}", requestCount, transcribeUrl);

        // Get the initial global stats before making requests
        GlobalStatsResponse before = restTemplate.getForObject(statsUrl, GlobalStatsResponse.class);

        // Make concurrent transcription requests, mapping the HTTP status code
        List<CompletableFuture<HttpStatusCode>> futures = IntStream.range(0, requestCount)
            .mapToObj(_ -> CompletableFuture.supplyAsync(
                () -> restTemplate.postForEntity(transcribeUrl, multipartAudioRequest(), String.class).getStatusCode(),
                pool))
            .toList();

        // Get the list of statuses from the concurrent requests
        List<HttpStatusCode> statuses = futures.stream()
            .map(f -> f.orTimeout(20, TimeUnit.SECONDS).join())
            .toList();
        pool.shutdown();

        // Assert that all the requests returned a 200 OK response
        assertThat(statuses)
            .allMatch(HttpStatus.OK::equals);

        // Get the global stats after making all the requests
        GlobalStatsResponse after = restTemplate.getForObject(statsUrl, GlobalStatsResponse.class);

        // Assert that the difference in input/output tokens after making the requests is equal to the number of requests
        // times the input/ouput tokens per call
        assertThat(after.inputTokens() - before.inputTokens())
            .isEqualTo(requestCount * INPUT_TOKENS_PER_CALL);
        assertThat(after.outputTokens() - before.outputTokens())
            .isEqualTo(requestCount * OUTPUT_TOKENS_PER_CALL);

        log.info("Completed {} concurrent transcription requests to {}. Global stats updated correctly).", requestCount, transcribeUrl);
    }

    /**
     * Helper method to create a multipart/form-data request containing a fake audio file.
     * @return HttpEntity representing the multipart request
     */
    private HttpEntity<MultiValueMap<String, Object>> multipartAudioRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("fake-audio".getBytes()) {
            @Override
            public String getFilename() {
                return "clip.webm";
            }
        });

        return new HttpEntity<>(body, headers);
    }
}
