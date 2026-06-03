package dev.victormartin.telemetry.engineer.log;

import java.sql.Timestamp;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persists delivered radio messages to {@code radio_messages}. Writes run on a
 * single background daemon thread so a slow or unavailable DB never blocks the
 * telemetry tick thread, and any failure is logged and swallowed (best-effort).
 */
@Component
public class JdbcRadioMessageLog implements RadioMessageLog {

    private static final Logger log = LoggerFactory.getLogger(JdbcRadioMessageLog.class);

    private static final String INSERT_SQL = """
            INSERT INTO radio_messages (
                message_id, session_uid, track_id, session_type, lap_number, total_laps,
                player_position, lap_distance_m, sector, pit_state, tyre_compound,
                tyre_age_laps, priority, message_text, rendered_text, best_strategies, sent_at
            ) VALUES (seq_radio_messages.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "radio-message-log");
        t.setDaemon(true);
        return t;
    });

    public JdbcRadioMessageLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(RadioMessageLogEntry entry) {
        writer.submit(() -> {
            try {
                jdbc.update(INSERT_SQL, bindArgs(entry));
            } catch (Exception e) {
                log.warn("radio message log write failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Telemetry serializes the session uid as unsigned hex ({@code Long.toHexString});
     * {@code radio_messages.session_uid} is the signed {@code NUMBER} matching
     * {@code sessions.session_uid}. Convert hex → signed long here so the insert
     * (and its FK) succeeds. Returns null for the {@code "-"}/blank placeholders.
     */
    static Long parseSessionUid(String hexUid) {
        if (hexUid == null || hexUid.isBlank() || hexUid.equals("-")) return null;
        return Long.parseUnsignedLong(hexUid.trim(), 16);
    }

    /** Bind values in the exact column order of {@link #INSERT_SQL} (message_id comes from the sequence). */
    static Object[] bindArgs(RadioMessageLogEntry e) {
        return new Object[] {
                parseSessionUid(e.sessionUid()),
                e.trackId(),
                e.sessionType(),
                e.lapNumber(),
                e.totalLaps(),
                e.playerPosition(),
                e.lapDistanceM(),
                e.sector(),
                e.pitState(),
                e.tyreCompound(),
                e.tyreAgeLaps(),
                e.priority(),
                e.messageText(),
                e.renderedText(),
                e.bestStrategiesJson(),
                new Timestamp(e.sentAtEpochMs())
        };
    }
}
