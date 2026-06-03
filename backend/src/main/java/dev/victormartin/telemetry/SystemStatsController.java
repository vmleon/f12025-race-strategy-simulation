package dev.victormartin.telemetry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only aggregate stats for the Portal System dashboard. All queries are
 * cheap GROUP BY / COUNT over existing tables; the window for per-day series is
 * the last 30 days.
 */
@RestController
@RequestMapping("/api/system/stats")
public class SystemStatsController {

    private final JdbcTemplate jdbc;
    private final SimulationOrchestrator orchestrator;

    public SystemStatsController(JdbcTemplate jdbc, SimulationOrchestrator orchestrator) {
        this.jdbc = jdbc;
        this.orchestrator = orchestrator;
    }

    public record DayCount(String day, long count) {}

    public record PriorityCount(String priority, long count) {}

    @GetMapping("/simulations")
    public Map<String, Object> simulations() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", firstNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM simulation_runs", Long.class), 0L));
        out.put("avgDurationMs", firstNonNull(jdbc.queryForObject(
                "SELECT AVG(duration_ms) FROM simulation_runs WHERE status = 'completed'", Double.class), 0.0));
        out.put("avgIterations", firstNonNull(jdbc.queryForObject(
                "SELECT AVG(iterations) FROM simulation_runs WHERE status = 'completed'", Double.class), 0.0));
        out.put("perDay", perDay(
                "SELECT TO_CHAR(TRUNC(started_at), 'YYYY-MM-DD') AS day, COUNT(*) AS cnt "
                + "FROM simulation_runs WHERE started_at >= TRUNC(SYSTIMESTAMP) - 30 "
                + "GROUP BY TRUNC(started_at) ORDER BY TRUNC(started_at)"));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT status, COUNT(*) AS cnt FROM simulation_runs GROUP BY status")) {
            byStatus.put(String.valueOf(row.get("STATUS")), asLong(row.get("CNT")));
        }
        out.put("byStatus", byStatus);
        return out;
    }

    @GetMapping("/radio")
    public Map<String, Object> radio() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", firstNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM radio_messages", Long.class), 0L));
        List<PriorityCount> byPriority = jdbc.queryForList(
                "SELECT priority, COUNT(*) AS cnt FROM radio_messages GROUP BY priority ORDER BY priority")
                .stream().map(r -> new PriorityCount(String.valueOf(r.get("PRIORITY")), asLong(r.get("CNT")))).toList();
        out.put("byPriority", byPriority);
        Map<String, Object> split = jdbc.queryForMap(
                "SELECT SUM(CASE WHEN rendered_text IS NOT NULL AND rendered_text <> message_text "
                + "THEN 1 ELSE 0 END) AS rendered, "
                + "SUM(CASE WHEN rendered_text IS NULL OR rendered_text = message_text "
                + "THEN 1 ELSE 0 END) AS fallback FROM radio_messages");
        Map<String, Long> rvf = new LinkedHashMap<>();
        rvf.put("rendered", asLong(split.get("RENDERED")));
        rvf.put("fallback", asLong(split.get("FALLBACK")));
        out.put("renderedVsFallback", rvf);
        out.put("perDay", perDay(
                "SELECT TO_CHAR(TRUNC(sent_at), 'YYYY-MM-DD') AS day, COUNT(*) AS cnt "
                + "FROM radio_messages WHERE sent_at >= TRUNC(SYSTIMESTAMP) - 30 "
                + "GROUP BY TRUNC(sent_at) ORDER BY TRUNC(sent_at)"));
        return out;
    }

    @GetMapping("/live")
    public Map<String, Object> live() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("simsInFlight", orchestrator.simsInFlight());
        Map<String, Long> today = new LinkedHashMap<>();
        today.put("simulations", firstNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM simulation_runs WHERE started_at >= TRUNC(SYSTIMESTAMP)", Long.class), 0L));
        today.put("radioMessages", firstNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM radio_messages WHERE sent_at >= TRUNC(SYSTIMESTAMP)", Long.class), 0L));
        out.put("today", today);
        return out;
    }

    @GetMapping("/calibration")
    public Map<String, Object> calibration() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCoefficients", firstNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_coefficients", Long.class), 0L));
        out.put("totalRuns", firstNonNull(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT trained_at) FROM calibration_coefficients", Long.class), 0L));
        out.put("perDay", perDay(
                "SELECT TO_CHAR(TRUNC(trained_at), 'YYYY-MM-DD') AS day, COUNT(*) AS cnt "
                + "FROM calibration_coefficients WHERE trained_at >= TRUNC(SYSTIMESTAMP) - 30 "
                + "GROUP BY TRUNC(trained_at) ORDER BY TRUNC(trained_at)"));
        return out;
    }

    private List<DayCount> perDay(String sql) {
        return jdbc.queryForList(sql).stream()
                .map(r -> new DayCount(String.valueOf(r.get("DAY")), asLong(r.get("CNT"))))
                .toList();
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static <T> T firstNonNull(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
