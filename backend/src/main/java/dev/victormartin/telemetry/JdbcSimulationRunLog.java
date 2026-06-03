package dev.victormartin.telemetry;

import java.sql.Timestamp;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persists simulation-run rows to {@code simulation_runs}. Writes run on a
 * single background daemon thread so a slow or unavailable DB never blocks the
 * orchestrator, and any failure is logged and swallowed (best-effort).
 */
@Component
public class JdbcSimulationRunLog implements SimulationRunLog {

    private static final Logger log = LoggerFactory.getLogger(JdbcSimulationRunLog.class);

    private static final String INSERT_SQL = """
            INSERT INTO simulation_runs (run_id, job_id, session_uid, started_at, status)
            VALUES (seq_simulation_runs.NEXTVAL, ?, ?, ?, 'running')
            """;

    private static final String UPDATE_SQL = """
            UPDATE simulation_runs
               SET completed_at = SYSTIMESTAMP, duration_ms = ?, iterations = ?, status = ?
             WHERE job_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simulation-run-log");
        t.setDaemon(true);
        return t;
    });

    public JdbcSimulationRunLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordStarted(String jobId, String sessionUid, long startedAtEpochMs) {
        writer.submit(() -> {
            try {
                jdbc.update(INSERT_SQL, jobId, parseSessionUid(sessionUid), new Timestamp(startedAtEpochMs));
            } catch (Exception e) {
                log.warn("simulation run log insert failed: {}", e.getMessage());
            }
        });
    }

    @Override
    public void recordCompleted(String jobId, long durationMs, int iterations, String status) {
        writer.submit(() -> {
            try {
                jdbc.update(UPDATE_SQL, durationMs, iterations, status, jobId);
            } catch (Exception e) {
                log.warn("simulation run log update failed: {}", e.getMessage());
            }
        });
    }

    /** session_uid is a NUMBER column; the orchestrator may pass "-" or blank -> store null. */
    static Long parseSessionUid(String sessionUid) {
        if (sessionUid == null || sessionUid.isBlank() || sessionUid.equals("-")) return null;
        try {
            return Long.parseLong(sessionUid.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
