package au.edu.adelaide.assignment1.controller;

import au.edu.adelaide.assignment1.dto.GlobalStatsResult;
import au.edu.adelaide.assignment1.service.GlobalStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for the GlobalStatsController.
 */
@WebMvcTest(GlobalStatsController.class)
class GlobalStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GlobalStatsService globalStatsService;

    /**
     * Test that the /api/v1/global/stats endpoint returns the correct global statistics.
     */
    @Test
    void getGlobalStats_returnsCurrentTotals() throws Exception {
        // Mock the service to return specific totals
        when(globalStatsService.getStats()).thenReturn(new GlobalStatsResult(150L, 75L));

        // Perform the GET request and verify the response
        mockMvc.perform(get("/api/v1/global/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inputTokens").value(150))
            .andExpect(jsonPath("$.outputTokens").value(75));
    }

    /**
     * Test that the /api/v1/global/stats endpoint returns zeroes when there is no usage.
     */
    @Test
    void getGlobalStats_zeroUsage_returnsZeroes() throws Exception {
        // Mock the service to return zero usage
        when(globalStatsService.getStats()).thenReturn(new GlobalStatsResult(0L, 0L));

        // Perform the GET request and verify the response
        mockMvc.perform(get("/api/v1/global/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inputTokens").value(0))
            .andExpect(jsonPath("$.outputTokens").value(0));
    }

    /**
     * Test that the /api/v1/global/stats endpoint returns 405 Method Not Allowed for unsupported HTTP methods.
     */
    @Test
    void getGlobalStats_wrongHttpMethod_returns405MethodNotAllowed() throws Exception {
        // Perform a POST request to the GET endpoint and expect a 405 Method Not Allowed response
        mockMvc.perform(post("/api/v1/global/stats"))
            .andExpect(status().isMethodNotAllowed());
    }
}