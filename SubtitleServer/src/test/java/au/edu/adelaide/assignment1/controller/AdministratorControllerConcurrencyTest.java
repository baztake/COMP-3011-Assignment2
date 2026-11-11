package au.edu.adelaide.assignment1.controller;

import au.edu.adelaide.assignment1.service.ShutdownService;
import au.edu.adelaide.assignment1.service.UptimeService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Concurrency test for the AdministratorController's shutdown endpoint.
 * This test simulates 250 concurrent requests to the shutdown endpoint and verifies that
 * only one request is accepted (HTTP 202) while the others receive a conflict response (HTTP 409).
 */
@WebMvcTest(AdministratorController.class)
class AdministratorControllerConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(AdministratorControllerConcurrencyTest.class);
    private static final int REQUEST_COUNT = 250;

    @Autowired
    private MockMvc mockMvc;

    /**
     * Test configuration that provides the necessary beans for the test context.
     * It includes a mock ShutdownService and a real UptimeService.
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        ShutdownService shutdownService() {
            return new ShutdownService(mock(ConfigurableApplicationContext.class));
        }

        @Bean
        UptimeService uptimeService() {
            return new UptimeService();
        }
    }

    /**
     * Test that simulates 250 concurrent requests to the shutdown endpoint.
     * It verifies that only one request is accepted (HTTP 202) and the rest receive a conflict response (HTTP 409).
     */
    @Test
    void shutdown_handlesManyConcurrentRequests() {
        // Simulate 250 concurrent requests to the shutdown endpoint
        log.info("Simulating {} concurrent requests to /api/v1/admin/shutdown", REQUEST_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);

        // Use CompletableFuture to send concurrent requests and collect their status codes
        List<Integer> statuses = IntStream.range(0, REQUEST_COUNT)
            .mapToObj(_ -> CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return mockMvc.perform(post("/api/v1/admin/shutdown")).andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                executor)
            )
            .toList()
            .stream()
            .map(f -> f.orTimeout(5, TimeUnit.SECONDS).join())
            .toList();
        executor.shutdown();

        // Count the occurrences of each status code
        Map<Integer, Long> statusCounts = statuses.stream()
            .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

        // Verify that only one request was accepted (HTTP 202) and the rest received a conflict response (HTTP 409)
        assertThat(statusCounts.keySet()).isSubsetOf(202, 409);
        assertThat(statusCounts.getOrDefault(202, 0L)).isEqualTo(1L);
        assertThat(statusCounts.getOrDefault(409, 0L)).isEqualTo(REQUEST_COUNT - 1L);

        log.info("Shutdown endpoint concurrency test completed. Status counts: {}", statusCounts);
    }
}
