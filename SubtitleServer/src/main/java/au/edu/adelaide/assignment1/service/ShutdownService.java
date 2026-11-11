package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.exception.ShutdownInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service to handle application shutdown requests.
 */
@Service
public class ShutdownService {

    private static final Logger log = LoggerFactory.getLogger(ShutdownService.class);
    private final ConfigurableApplicationContext applicationContext;
    // AtomicBoolean to track if a shutdown is already in progress for concurrency
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);

    /**
     * Constructor for ShutdownService.
     *
     * @param applicationContext the Spring application context
     */
    public ShutdownService(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Requests the application to shut down. If a shutdown is already in progress,
     * a ShutdownInProgressException is thrown.
     * <p>
     * This method starts a new virtual thread to close the application context,
     * allowing the shutdown process to run asynchronously.
     *
     * @throws ShutdownInProgressException if a shutdown is already in progress
     */
    public void requestShutdown() {
        log.info("requestShutdown called");
        // Attempt to set shutdownInProgress to true atomically. If it was already true, throw an exception.
        if (!shutdownInProgress.compareAndSet(false, true)) {
            log.warn("Shutdown requested but already in progress");
            throw new ShutdownInProgressException();
        }

        // Start a new virtual thread to close the application context asynchronously
        Thread.startVirtualThread(() -> {
            try {
                log.info("Initiating graceful shutdown");
                applicationContext.close();
                log.info("Application context closed");
            } catch (Throwable t) {
                log.error("Error during shutdown: {}", t.getMessage(), t);
                throw t;
            }
        });
    }
}
