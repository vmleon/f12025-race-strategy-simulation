package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SimulationController.class)
class SimulationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SimulationOrchestrator orchestrator;

    @MockBean
    private QueueService queueService;

    @MockBean
    private JdbcTemplate jdbc;

    @Test
    void resultsReturns404ForUnknownJob() throws Exception {
        when(orchestrator.getJob(anyString())).thenReturn(null);

        mockMvc.perform(get("/api/simulation/results/some-job-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resultsReturns202ForRunningJob() throws Exception {
        when(orchestrator.getJob("abc123")).thenReturn(
                new SimulationOrchestrator.SimulationJob("abc123", System.currentTimeMillis(), -1, null));

        mockMvc.perform(get("/api/simulation/results/abc123"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("running"));
    }
}
