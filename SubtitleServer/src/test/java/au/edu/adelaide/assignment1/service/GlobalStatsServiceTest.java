package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.dto.GlobalStatsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalStatsService}.
 */
class GlobalStatsServiceTest {

    private static final Logger log = LoggerFactory.getLogger(GlobalStatsServiceTest.class);

    private GlobalStatsService service;

    /**
     * Sets up a new instance of {@link GlobalStatsService} before each test.
     */
    @BeforeEach
    void setUp() {
        service = new GlobalStatsService();
    }

    /**
     * Tests that the initial stats are zero.
     */
    @Test
    void getStats_initiallyZero() {
        // Get the stats without recording any usage
        GlobalStatsResult result = service.getStats();

        // Verify that the initial stats are zero
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
    }

    /**
     * Tests that recording usage adds to the totals.
     */
    @Test
    void recordUsage_addsToTotals() {
        // Record some usage
        service.recordUsage(10L, 5L);

        // Get the stats after recording usage
        GlobalStatsResult result = service.getStats();

        // Verify that the totals are updated correctly
        assertThat(result.inputTokens()).isEqualTo(10L);
        assertThat(result.outputTokens()).isEqualTo(5L);
    }

    /**
     * Tests that multiple calls to recordUsage accumulate the totals.
     */
    @Test
    void recordUsage_multipleCalls_accumulate() {
        // Record multiple usages
        service.recordUsage(10L, 5L);
        service.recordUsage(3L, 7L);
        service.recordUsage(0L, 0L);

        // Get the stats after recording usage
        GlobalStatsResult result = service.getStats();

        // Verify that the totals are accumulated correctly
        assertThat(result.inputTokens()).isEqualTo(13L);
        assertThat(result.outputTokens()).isEqualTo(12L);
    }

    /**
     * Tests that recording usage with null values does not throw an exception or add to the totals.
     */
    @Test
    void recordUsage_withNulls_doesNotThrowOrAdd() {
        // Record usage with null values
        service.recordUsage(10L, 5L);
        service.recordUsage(null, null);

        // Get the stats after recording usage
        GlobalStatsResult result = service.getStats();

        // Verify that the totals are unchanged
        assertThat(result.inputTokens()).isEqualTo(10L);
        assertThat(result.outputTokens()).isEqualTo(5L);
    }

    /**
     * Tests that recording usage with one null argument only adds the non-null value.
     */
    @Test
    void recordUsage_withOneNullArgument_onlyAddsNonNullValue() {
        // Record usage with one null argument
        service.recordUsage(4L, null);
        service.recordUsage(null, 6L);

        // Get the stats after recording usage
        GlobalStatsResult result = service.getStats();

        // Verify that the totals are updated correctly
        assertThat(result.inputTokens()).isEqualTo(4L);
        assertThat(result.outputTokens()).isEqualTo(6L);
    }

    /**
     * Tests that under high concurrency, no updates are lost when recording usage.
     */
    @Test
    void recordUsage_underHighConcurrency_noLostUpdates() {
        // Simulate high concurrency by making multiple calls to recordUsage in parallel
        int callCount = 250;
        long inputPerCall = 2L;
        long outputPerCall = 3L;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        log.info("Simulating {} concurrent calls to recordUsage with input={} and output={}", callCount, inputPerCall, outputPerCall);

        // Create a list of CompletableFutures to run the recordUsage calls in parallel
        List<CompletableFuture<Void>> futures = IntStream.range(0, callCount)
            .mapToObj(i -> CompletableFuture.runAsync(() -> service.recordUsage(inputPerCall, outputPerCall), pool))
            .toList();
        futures.forEach(f -> f.orTimeout(10, TimeUnit.SECONDS).join());
        pool.shutdown();

        // Get the stats after all concurrent calls have completed
        GlobalStatsResult result = service.getStats();

        // Verify that no updates were lost due to race conditions
        assertThat(result.inputTokens()).isEqualTo(callCount * inputPerCall);
        assertThat(result.outputTokens()).isEqualTo(callCount * outputPerCall);

        log.info("High concurrency test completed. Final stats: inputTokens={}, outputTokens={}", result.inputTokens(), result.outputTokens());
    }
}
