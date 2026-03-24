package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CalibrationController.class)
class CalibrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    @Test
    void statusReturnsCalibrationCoefficients() throws Exception {
        when(jdbc.query(contains("calibration_coefficients"), any(RowMapper.class), eq(5)))
                .thenReturn(List.of(
                        new CalibrationController.CalibrationStatusDto(
                                "tyre_deg_soft", "PLAYER", null, 0.05,
                                0.85, false, 3, 150, "2026-03-20T14:30:00")));

        mockMvc.perform(get("/api/calibration/status?trackId=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].knobName").value("tyre_deg_soft"))
                .andExpect(jsonPath("$[0].isDefault").value(false));
    }

    @Test
    void runReturns501() throws Exception {
        mockMvc.perform(post("/api/calibration/run?trackId=5"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error").exists());
    }
}
