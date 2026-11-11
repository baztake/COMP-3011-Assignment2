package au.edu.adelaide.assignment1.controller;

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
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test to verify that the TranscriptionController can handle a high number of simultaneous requests
 * without crashing or significant delays. This test uses a stubbed TranscriptionService to simulate processing time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
    "transcription.service.provider.base-url=https://fake-stt.example.com",
    "transcription.service.provider.api-key=test-key-not-real",
    "server.tomcat.threads.max=300"
})
class TranscriptionControllerConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionControllerConcurrencyTest.class);

    private static final int REQUEST_COUNT = 250;
    private static final long STUB_LATENCY_MILLIS = 50;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Test configuration that provides a stub implementation of the TranscriptionService.
     * This stub simulates a fixed processing time for each transcription request.
     */
    @TestConfiguration
    static class StubSttConfig {
        @Bean
        @Primary
        TranscriptionService stubTranscriptionService() {
            return _ -> {
                try {
                    Thread.sleep(STUB_LATENCY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new TranscriptionResult() {
                    @Override public String text() { return "stub transcript"; }
                    @Override public long inputTokens() { return 1L; }
                    @Override public long outputTokens() { return 1L; }
                };
            };
        }
    }

    /**
     * Test that sends 250 simultaneous transcription requests to the /api/v1/transcribe endpoint and verifies that all
     * requests complete successfully without crashing or significant delays.
     */
    @Test
    void transcribe_handles250SimultaneousTranscribeRequests_withoutCrashingOrSignificantDelay() {
        // Use a thread pool to send requests concurrently
        String url = "http://localhost:" + port + "/api/v1/transcribe";
        ExecutorService pool = Executors.newFixedThreadPool(REQUEST_COUNT);

        // Measure the time taken to complete all requests
        long start = System.nanoTime();
        List<CompletableFuture<HttpStatusCode>> futures = IntStream.range(0, REQUEST_COUNT)
            .mapToObj(_ -> CompletableFuture.supplyAsync(
                () -> restTemplate.postForEntity(url, multipartAudioRequest(), String.class).getStatusCode(),
                pool))
            .toList();

        // Surfaces any failed/timed-out request as a test failure
        List<HttpStatusCode> results = futures.stream()
            .map(f -> f.orTimeout(30, TimeUnit.SECONDS).join())
            .toList();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();

        // Verify that all requests succeeded
        assertThat(results)
            .allMatch(HttpStatus.OK::equals);

        // Sequential handling would take ~REQUEST_COUNT * STUB_LATENCY_MILLIS, we expect the concurrent handling
        // to be at least 20x faster.
        long sequentialWorstCase = REQUEST_COUNT * STUB_LATENCY_MILLIS;
        log.info(
            "Elapsed time for {} concurrent requests: {}ms (sequential worst-case: {}ms)",
            REQUEST_COUNT,
            elapsedMillis,
            sequentialWorstCase
        );
        assertThat(elapsedMillis)
            .isLessThan(sequentialWorstCase / 20);
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

