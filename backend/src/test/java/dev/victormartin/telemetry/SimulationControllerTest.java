package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import dev.victormartin.telemetry.simulation.CoefficientRepository;
import dev.victormartin.telemetry.simulation.Coefficients;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SimulationController.class)
class SimulationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CoefficientRepository coefficientRepository;

    @MockBean
    private JdbcTemplate jdbc;

    @Test
    void runReturnsSimulationResult() throws Exception {
        when(coefficientRepository.loadForTrack(anyInt()))
                .thenReturn(Coefficients.defaults());

        String body = """
                {
                    "trackId": 5,
                    "totalLaps": 10,
                    "currentLap": 5,
                    "currentSector": 0,
                    "weather": 0,
                    "trackTemp": 35,
                    "airTemp": 25,
                    "safetyCar": false,
                    "cars": [
                        {
                            "carIndex": 0,
                            "driverName": "Player",
                            "aiControlled": false,
                            "position": 1,
                            "tyreCompound": 17,
                            "tyreAgeLaps": 5,
                            "fuelKg": 30.0,
                            "fuelBurnPerSectorKg": 0.1,
                            "frontWingDamage": 0,
                            "floorDamage": 0,
                            "engineDamage": 0,
                            "numPitStops": 0,
                            "totalTimeMs": 500000.0
                        },
                        {
                            "carIndex": 1,
                            "driverName": "AI Driver",
                            "aiControlled": true,
                            "position": 2,
                            "tyreCompound": 17,
                            "tyreAgeLaps": 5,
                            "fuelKg": 30.0,
                            "fuelBurnPerSectorKg": 0.1,
                            "frontWingDamage": 0,
                            "floorDamage": 0,
                            "engineDamage": 0,
                            "numPitStops": 0,
                            "totalTimeMs": 501000.0
                        }
                    ],
                    "pitStrategy": null
                }
                """;

        mockMvc.perform(post("/api/simulation/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iterations").isNumber())
                .andExpect(jsonPath("$.cars.length()").value(2))
                .andExpect(jsonPath("$.cars[0].driverName").value("Player"))
                .andExpect(jsonPath("$.cars[0].meanPosition").isNumber());
    }

    @Test
    void resultsReturns501() throws Exception {
        mockMvc.perform(get("/api/simulation/results/some-job-id"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error").exists());
    }
}
