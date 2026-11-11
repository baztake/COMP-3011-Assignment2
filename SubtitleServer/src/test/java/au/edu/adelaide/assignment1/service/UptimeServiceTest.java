package au.edu.adelaide.assignment1.service;


import au.edu.adelaide.assignment1.dto.UptimeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the UptimeService class.
 */
class UptimeServiceTest {

    private UptimeService service;

    /**
     * Sets up a new instance of UptimeService before each test.
     */
    @BeforeEach
    void setUp() {
        service = new UptimeService();
    }

    /**
     * Tests that getUptimeSeconds returns a non-null result with valid timestamps.
     */
    @Test
    void getUptimeSeconds_returnsNonNullResultWithTimestamps() {
        // Get the uptime result from the service
        UptimeResult result = service.getUptimeSeconds();

        // Assert that the result and its timestamps are not null
        assertThat(result).isNotNull();
        assertThat(result.startTime()).isNotNull();
        assertThat(result.currentTime()).isNotNull();
    }

    /**
     * Tests that the uptime is non-negative and that the current time is after the start time.
     */
    @Test
    void getUptimeSeconds_immediatelyAfterConstruction_isNearZero() {
        // Get the uptime result from the service
        UptimeResult result = service.getUptimeSeconds();

        // Assert that the uptime is between 0 and 1 second, and that the current time is after the start time
        assertThat(result.uptimeSeconds()).isBetween(0L, 1L);
        assertThat(result.currentTime()).isAfterOrEqualTo(result.startTime());
    }

    /**
     * Tests that the start time remains stable across multiple calls to getUptimeSeconds.
     */
    @Test
    void getUptimeSeconds_startTimeIsStableAcrossManyCalls() {
        // Get the expected start time from the service
        Instant expectedStart = service.getUptimeSeconds().startTime();

        // Call getUptimeSeconds multiple times and collect the start times
        List<Instant> starts = IntStream.range(0, 20)
            .mapToObj(_ -> service.getUptimeSeconds().startTime())
            .toList();

        // Assert that all collected start times match the expected start time
        assertThat(starts)
            .allMatch(expectedStart::equals);
    }

    /**
     * Tests that the uptime increases after a delay, confirming that the service correctly tracks elapsed time.
     */
    @Test
    void getUptimeSeconds_increasesAfterDelay() throws InterruptedException {
        // Get the uptime result before the delay
        UptimeResult before = service.getUptimeSeconds();

        // Sleep for a little over one second to ensure that the uptime increases
        Thread.sleep(1_100); // cross at least one full second boundary

        // Get the uptime result after the delay
        UptimeResult after = service.getUptimeSeconds();

        // Assert that the uptime after the delay is greater than before, and that the current time has advanced
        assertThat(after.uptimeSeconds()).isGreaterThan(before.uptimeSeconds());
        assertThat(after.currentTime()).isAfter(before.currentTime());
    }

    /**
     * This test checks that the getUptimeSeconds method is thread-safe and consistent under concurrent access.
     * It spawns multiple threads to call the method simultaneously and verifies that the start time remains the same
     * across all calls, and that the uptime is non-negative.
     */
    @Test
    void getUptimeSeconds_underConcurrency_isThreadSafeAndConsistent() {
        // This test checks that the start time remains consistent across multiple concurrent calls
        int callCount = 200;
        ExecutorService pool = Executors.newFixedThreadPool(50);

        // Submit multiple concurrent calls to getUptimeSeconds
        List<CompletableFuture<UptimeResult>> futures = IntStream.range(0, callCount)
            .mapToObj(_ -> CompletableFuture.supplyAsync(service::getUptimeSeconds, pool))
            .toList();

        // Wait for all futures to complete and collect results
        List<UptimeResult> results = futures.stream()
            .map(f -> f.orTimeout(10, TimeUnit.SECONDS).join())
            .toList();
        pool.shutdown();

        // Verify that all results have the same start time and that uptime is non-negative
        assertThat(results).hasSize(callCount);

        // All results should have the same start time
        Instant expectedStart = results.getFirst().startTime();
        assertThat(results)
            .allMatch(r -> r.startTime().equals(expectedStart));
        assertThat(results)
            .allMatch(r -> !r.currentTime().isBefore(r.startTime()));
        assertThat(results)
            .allMatch(r -> r.uptimeSeconds() >= 0L);
    }
}
