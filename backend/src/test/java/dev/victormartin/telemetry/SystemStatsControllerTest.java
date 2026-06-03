package dev.victormartin.telemetry;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemStatsController.class)
class SystemStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbc;

    @MockBean
    private SimulationOrchestrator orchestrator;

    @Test
    void simulationsReturnsTotalsAndPerDay() throws Exception {
        when(jdbc.queryForObject(contains("COUNT(*) FROM simulation_runs"), eq(Long.class)))
                .thenReturn(7L);
        when(jdbc.queryForObject(contains("AVG(duration_ms)"), eq(Double.class))).thenReturn(1234.0);
        when(jdbc.queryForObject(contains("AVG(iterations)"), eq(Double.class))).thenReturn(2000.0);
        when(jdbc.queryForList(contains("TRUNC(started_at)")))
                .thenReturn(List.of(Map.of("DAY", "2026-06-01", "CNT", 3),
                                    Map.of("DAY", "2026-06-02", "CNT", 4)));
        when(jdbc.queryForList(contains("GROUP BY status")))
                .thenReturn(List.of(Map.of("STATUS", "completed", "CNT", 6),
                                    Map.of("STATUS", "failed", "CNT", 1)));

        mockMvc.perform(get("/api/system/stats/simulations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.avgDurationMs").value(1234.0))
                .andExpect(jsonPath("$.avgIterations").value(2000.0))
                .andExpect(jsonPath("$.perDay.length()").value(2))
                .andExpect(jsonPath("$.perDay[0].day").value("2026-06-01"))
                .andExpect(jsonPath("$.perDay[0].count").value(3))
                .andExpect(jsonPath("$.byStatus.completed").value(6))
                .andExpect(jsonPath("$.byStatus.failed").value(1));
    }

    @Test
    void radioReturnsPriorityAndRenderedSplit() throws Exception {
        when(jdbc.queryForObject(contains("COUNT(*) FROM radio_messages"), eq(Long.class)))
                .thenReturn(10L);
        when(jdbc.queryForList(contains("GROUP BY priority")))
                .thenReturn(List.of(Map.of("PRIORITY", "IMMEDIATE", "CNT", 4),
                                    Map.of("PRIORITY", "NORMAL", "CNT", 6)));
        when(jdbc.queryForMap(contains("rendered")))
                .thenReturn(Map.of("RENDERED", 0, "FALLBACK", 10));
        when(jdbc.queryForList(contains("TRUNC(sent_at)")))
                .thenReturn(List.of(Map.of("DAY", "2026-06-02", "CNT", 10)));

        mockMvc.perform(get("/api/system/stats/radio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.byPriority[0].priority").value("IMMEDIATE"))
                .andExpect(jsonPath("$.byPriority[0].count").value(4))
                .andExpect(jsonPath("$.renderedVsFallback.rendered").value(0))
                .andExpect(jsonPath("$.renderedVsFallback.fallback").value(10));
    }

    @Test
    void calibrationReturnsTotals() throws Exception {
        when(jdbc.queryForObject(contains("COUNT(*) FROM calibration_coefficients"), eq(Long.class)))
                .thenReturn(42L);
        when(jdbc.queryForObject(contains("COUNT(DISTINCT trained_at)"), eq(Long.class)))
                .thenReturn(3L);
        when(jdbc.queryForList(contains("TRUNC(trained_at)")))
                .thenReturn(List.of(Map.of("DAY", "2026-05-30", "CNT", 14)));

        mockMvc.perform(get("/api/system/stats/calibration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCoefficients").value(42))
                .andExpect(jsonPath("$.totalRuns").value(3))
                .andExpect(jsonPath("$.perDay[0].day").value("2026-05-30"))
                .andExpect(jsonPath("$.perDay[0].count").value(14));
    }

    @Test
    void liveReturnsInFlightAndTodayCounts() throws Exception {
        when(orchestrator.simsInFlight()).thenReturn(2);
        when(jdbc.queryForObject(contains("simulation_runs WHERE started_at >= TRUNC"), eq(Long.class)))
                .thenReturn(5L);
        when(jdbc.queryForObject(contains("radio_messages WHERE sent_at >= TRUNC"), eq(Long.class)))
                .thenReturn(9L);

        mockMvc.perform(get("/api/system/stats/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simsInFlight").value(2))
                .andExpect(jsonPath("$.today.simulations").value(5))
                .andExpect(jsonPath("$.today.radioMessages").value(9));
    }
}
