package au.edu.adelaide.assignment1.controller;

import au.edu.adelaide.assignment1.service.ShutdownService;
import au.edu.adelaide.assignment1.service.UptimeService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link AdministratorController}.
 */
@WebMvcTest(AdministratorController.class)
class AdministratorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShutdownService shutdownService;

    /**
     * Test configuration that provides a real instance of UptimeService for testing.
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        UptimeService uptimeService() {
            return new UptimeService();
        }
    }

    /**
     * Test that the /api/v1/admin/uptime endpoint returns a valid response with non-negative uptime.
     */
    @Test
    void uptime_returnsStartTimeNowAndNonNegativeDuration() throws Exception {
        // Perform the GET request to the /api/v1/admin/uptime endpoint and verify the response
        mockMvc.perform(get("/api/v1/admin/uptime"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.utcServerStart").exists())
            .andExpect(jsonPath("$.utcNow").exists())
            .andExpect(jsonPath("$.serverUptimeSeconds").value(Matchers.greaterThanOrEqualTo(0)));
    }

    /**
     * Test that the /api/v1/admin/shutdown endpoint returns a 202 Accepted status and a message indicating that a graceful shutdown has been requested.
     */
    @Test
    void shutdown_returns202() throws Exception {
        // perform the POST request to the /api/v1/admin/shutdown endpoint and verify the response
        mockMvc.perform(post("/api/v1/admin/shutdown"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").value("Graceful shutdown requested."));

        // Verify that the shutdownService's requestShutdown method was called exactly once
        verify(shutdownService, times(1)).requestShutdown();
    }

}