package au.edu.adelaide.assignment1.service;

import au.edu.adelaide.assignment1.exception.ShutdownInProgressException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the ShutdownService class.
 */
class ShutdownServiceTest {

    private ShutdownService shutdownService;
    private ConfigurableApplicationContext applicationContext;

    /**
     * Sets up the test environment before each test case.
     */
    @BeforeEach
    void setUp() {
        applicationContext = mock(ConfigurableApplicationContext.class);
        shutdownService = new ShutdownService(applicationContext);
    }

    /**
     * Tests that the first call to requestShutdown initiates the shutdown process.
     */
    @Test
    void requestShutdown_firstCall_initiatesShutdown() {
        // Request shutdown
        shutdownService.requestShutdown();

        // Verify that the application context's close method was called once
        verify(applicationContext, timeout(1000).times(1)).close();
    }

    /**
     * Tests that multiple calls to requestShutdown throw a ShutdownInProgressException.
     */
    @Test
    void requestShutdown_multipleCalls_throwsShutdownInProgressException() {
        // Request shutdown for the first time
        shutdownService.requestShutdown();

        // Request shutdown for the second time, expecting a ShutdownInProgressException
        assertThrows(ShutdownInProgressException.class, () -> shutdownService.requestShutdown());
    }
}
