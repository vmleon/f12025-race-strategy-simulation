package dev.victormartin.telemetry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persists simulation / strategy I/O rows to {@code simulation_run_io}. Writes
 * run on a single background daemon thread so a slow or unavailable DB never
 * blocks the orchestrator/consumer, and any failure is logged and swallowed
 * (best-effort), matching {@link JdbcSimulationRunLog}.
 */
@Component
public class JdbcSimulationIoLog implements SimulationIoLog {

    private static final Logger log = LoggerFactory.getLogger(JdbcSimulationIoLog.class);

    private static final String INSERT_SQL = """
            INSERT INTO simulation_run_io (io_id, job_id, kind, request_json, player_car_index)
            VALUES (seq_simulation_run_io.NEXTVAL, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE simulation_run_io
               SET result_json = ?, player_mean_position = ?
             WHERE job_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simulation-io-log");
        t.setDaemon(true);
        return t;
    });

    public JdbcSimulationIoLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordRequest(String jobId, String kind, String requestJson, int playerCarIndex) {
        writer.submit(() -> {
            try {
                jdbc.update(INSERT_SQL, jobId, kind, requestJson,
                        playerCarIndex < 0 ? null : playerCarIndex);
            } catch (Exception e) {
                log.warn("simulation io log insert failed: {}", e.getMessage());
            }
        });
    }

    @Override
    public void recordResult(String jobId, String resultJson, Double playerMeanPosition) {
        writer.submit(() -> {
            try {
                jdbc.update(UPDATE_SQL, resultJson, playerMeanPosition, jobId);
            } catch (Exception e) {
                log.warn("simulation io log update failed: {}", e.getMessage());
            }
        });
    }
}
