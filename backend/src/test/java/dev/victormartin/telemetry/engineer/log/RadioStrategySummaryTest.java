package dev.victormartin.telemetry.engineer.log;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.simulation.StrategyEvaluation;
import dev.victormartin.telemetry.simulation.StrategyEvaluation.RankedStrategy;
import dev.victormartin.telemetry.simulation.StrategyEvaluation.StrategyCandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioStrategySummaryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static RankedStrategy ranked(int rank, String label, double meanPos, double pts) {
        return new RankedStrategy(rank, new StrategyCandidate(label, List.of()),
                meanPos, 0, 0, 0, 0, 0, 0, pts);
    }

    @Test
    void nullEvaluationReturnsNull() {
        assertNull(RadioStrategySummary.topThreeJson(mapper, null));
    }

    @Test
    void emptyStrategiesReturnsNull() {
        assertNull(RadioStrategySummary.topThreeJson(mapper, new StrategyEvaluation(0, List.of())));
    }

    @Test
    void keepsTopThreeInOrderWithKeyFields() throws Exception {
        StrategyEvaluation eval = new StrategyEvaluation(0, List.of(
                ranked(1, "1-stop: Soft->Hard", 4.1, 12.0),
                ranked(2, "2-stop: Soft->Med->Soft", 4.8, 10.0),
                ranked(3, "no stop", 6.2, 6.0),
                ranked(4, "fourth", 9.9, 1.0)));

        String json = RadioStrategySummary.topThreeJson(mapper, eval);
        JsonNode arr = mapper.readTree(json);

        assertEquals(3, arr.size());
        assertEquals(1, arr.get(0).get("rank").asInt());
        assertEquals("1-stop: Soft->Hard", arr.get(0).get("label").asText());
        assertEquals(4.1, arr.get(0).get("meanPosition").asDouble(), 1e-9);
        assertEquals(12.0, arr.get(0).get("expectedPoints").asDouble(), 1e-9);
        assertEquals(3, arr.get(2).get("rank").asInt());
        assertTrue(arr.get(2).get("label").asText().contains("no stop"));
    }
}
