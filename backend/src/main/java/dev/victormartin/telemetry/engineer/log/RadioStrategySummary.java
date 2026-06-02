package dev.victormartin.telemetry.engineer.log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.victormartin.telemetry.simulation.StrategyEvaluation;
import dev.victormartin.telemetry.simulation.StrategyEvaluation.RankedStrategy;

/**
 * Builds the compact top-3 strategy summary stored alongside a logged radio
 * message. Stored as JSON text (not Oracle's native JSON type) so the existing
 * string-based exporter in {@code manage.py} handles the column unchanged.
 */
public final class RadioStrategySummary {

    private RadioStrategySummary() {}

    /** Top 3 ranked strategies as a JSON array string, or {@code null} when none. */
    public static String topThreeJson(ObjectMapper mapper, StrategyEvaluation evaluation) {
        if (evaluation == null || evaluation.strategies() == null || evaluation.strategies().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> top = evaluation.strategies().stream()
                .limit(3)
                .map(RadioStrategySummary::summarize)
                .toList();
        try {
            return mapper.writeValueAsString(top);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> summarize(RankedStrategy rs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", rs.rank());
        m.put("label", rs.candidate().label());
        m.put("meanPosition", rs.meanPosition());
        m.put("expectedPoints", rs.expectedPoints());
        return m;
    }
}
