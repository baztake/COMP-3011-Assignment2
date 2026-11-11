package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.dto.GlobalStatsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Service to track global statistics for input and output tokens.
 */
@Service
public class GlobalStatsService {

    private static final Logger log = LoggerFactory.getLogger(GlobalStatsService.class);
    // AtomicLong is used to ensure thread-safe operations on the token counts.
    private final AtomicLong inputTokens = new AtomicLong(0);
    private final AtomicLong outputTokens = new AtomicLong(0);

    /**
     * Records the usage of input and output tokens.
     *
     * @param input  The number of input tokens.
     * @param output The number of output tokens.
     */
    public void recordUsage(Long input, Long output) {
        // Ensure that null values are handled gracefully
        log.debug("recordUsage called with input={}, output={}", input, output);
        if (input != null) {
            long newInput = inputTokens.addAndGet(input);
            log.debug("Updated inputTokens -> {}", newInput);
        }
        if (output != null) {
            long newOutput = outputTokens.addAndGet(output);
            log.debug("Updated outputTokens -> {}", newOutput);
        }
    }

    /**
     * Retrieves the current global statistics.
     *
     * @return The global statistics response.
     */
    public GlobalStatsResult getStats() {
        GlobalStatsResult res = new GlobalStatsResult(
            inputTokens.get(),
            outputTokens.get()
        );
        log.debug("getStats -> inputTokens={}, outputTokens={}", res.inputTokens(), res.outputTokens());
        return res;
    }
}
