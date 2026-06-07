package dev.victormartin.telemetry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * On a finished Race, compares the simulator's last predicted player finishing
 * position against the actual result and records the error to
 * {@code simulation_accuracy} — the cheapest, highest-signal tripwire for
 * systematic strategy bias (the "predicted P20, finished P2" class of bug).
 * Runs best-effort on a daemon thread so a slow/unavailable DB never blocks the
 * session-end path; any failure is logged and swallowed.
 */
@Component
public class SimulationAccuracyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SimulationAccuracyEvaluator.class);

    /** Above this many places of error, flag a WARN — the systematic-error alarm. */
    static final double WARN_THRESHOLD_PLACES = 5.0;

    private final JdbcTemplate jdbc;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simulation-accuracy");
        t.setDaemon(true);
        return t;
    });

    public SimulationAccuracyEvaluator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static double absError(double predicted, int actual) {
        return Math.abs(predicted - actual);
    }

    /** Evaluate accuracy for a finished race. {@code hexUid} is the telemetry
     * unsigned-hex session uid. Idempotent: skips if a row already exists. */
    public void evaluate(String hexUid) {
        writer.submit(() -> {
            try {
                Long uid = parseUid(hexUid);
                if (uid == null) return;

                Long existing = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM simulation_accuracy WHERE session_uid = ?", Long.class, uid);
                if (existing != null && existing > 0) return;

                // Last strategy prediction made for the player during this session.
                List<Map<String, Object>> preds = jdbc.queryForList(
                        "SELECT rm.lap_number AS lap, io.player_car_index AS car, "
                        + "io.player_mean_position AS predicted, io.job_id AS job_id "
                        + "FROM radio_messages rm "
                        + "JOIN simulation_run_io io ON io.job_id = rm.job_id "
                        + "WHERE rm.session_uid = ? AND rm.job_id IS NOT NULL "
                        + "  AND io.player_mean_position IS NOT NULL "
                        + "ORDER BY rm.sent_at DESC FETCH FIRST 1 ROW ONLY",
                        uid);
                if (preds.isEmpty()) {
                    log.debug("SimulationAccuracyEvaluator: no prediction for session {}", hexUid);
                    return;
                }
                Map<String, Object> p = preds.get(0);
                int carIndex = ((Number) p.get("CAR")).intValue();
                double predicted = ((Number) p.get("PREDICTED")).doubleValue();
                Integer predictedAtLap = p.get("LAP") != null ? ((Number) p.get("LAP")).intValue() : null;
                String jobId = (String) p.get("JOB_ID");

                List<Integer> actuals = jdbc.queryForList(
                        "SELECT position FROM final_classifications WHERE session_uid = ? AND car_index = ?",
                        Integer.class, uid, carIndex);
                if (actuals.isEmpty()) {
                    log.debug("SimulationAccuracyEvaluator: no final classification for session {} car {}",
                            hexUid, carIndex);
                    return;
                }
                int actual = actuals.get(0);
                double absError = absError(predicted, actual);

                jdbc.update(
                        "INSERT INTO simulation_accuracy (accuracy_id, session_uid, player_car_index, "
                        + "predicted_position, predicted_at_lap, actual_position, abs_error, job_id) "
                        + "VALUES (seq_simulation_accuracy.NEXTVAL, ?, ?, ?, ?, ?, ?, ?)",
                        uid, carIndex, predicted, predictedAtLap, actual, absError, jobId);

                if (absError > WARN_THRESHOLD_PLACES) {
                    log.warn("SimulationAccuracyEvaluator: large strategy error — predicted P{} but finished P{} "
                            + "(|Δ|={} places) for session {}",
                            String.format("%.1f", predicted), actual, String.format("%.1f", absError), hexUid);
                } else {
                    log.info("SimulationAccuracyEvaluator: predicted P{} vs actual P{} (|Δ|={}) for session {}",
                            String.format("%.1f", predicted), actual, String.format("%.1f", absError), hexUid);
                }
            } catch (Exception e) {
                log.warn("SimulationAccuracyEvaluator: evaluate failed: {}", e.getMessage());
            }
        });
    }

    /** Telemetry serializes session uid as unsigned hex; the table stores the signed long. */
    static Long parseUid(String hexUid) {
        if (hexUid == null || hexUid.isBlank() || hexUid.equals("-")) return null;
        try {
            return Long.parseUnsignedLong(hexUid.trim(), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
