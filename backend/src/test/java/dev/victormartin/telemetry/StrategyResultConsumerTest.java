package dev.victormartin.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.simulation.StrategyEvaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyResultConsumerTest {

    /**
     * The simulator owns the STRATEGY_RESULT schema and may add fields ahead of
     * the backend — it added {@code repairFrontWing} to each pit stop. The
     * consumer must tolerate unknown fields, otherwise the whole strategy result
     * is dropped and never reaches the dashboard (regression for the GP weekend
     * where 14/27 results — every one with pit stops — failed to deserialize).
     */
    @Test
    void deserializesStrategyResultWithUnknownSimulatorFields() throws Exception {
        String result = """
                {"playerCarIndex":0,"strategies":[
                  {"rank":1,"candidate":{"label":"1-stop: Soft->Hard",
                    "stops":[{"onLap":20,"newCompound":18,"repairFrontWing":true}]}}]}
                """;

        ObjectMapper mapper = StrategyResultConsumer.newResultMapper();
        JsonNode node = mapper.readTree(result);

        StrategyEvaluation eval = mapper.treeToValue(node, StrategyEvaluation.class);

        assertEquals(1, eval.strategies().size());
        var stops = eval.strategies().get(0).candidate().stops();
        assertEquals(1, stops.size());
        assertEquals(20, stops.get(0).onLap());
        assertEquals(18, stops.get(0).newCompound());
    }

    /**
     * The simulator flags when the player's pace is uncalibrated (circuit
     * default). The backend must carry that flag through to the dashboard so the
     * strategy panel can show "insufficient calibration" instead of fake-precise
     * numbers.
     */
    @Test
    void carriesInsufficientCalibrationFlag() throws Exception {
        String result = """
                {"playerCarIndex":0,"insufficientCalibration":true,"strategies":[]}
                """;

        ObjectMapper mapper = StrategyResultConsumer.newResultMapper();
        StrategyEvaluation eval = mapper.treeToValue(mapper.readTree(result), StrategyEvaluation.class);

        assertTrue(eval.insufficientCalibration());
    }
}
