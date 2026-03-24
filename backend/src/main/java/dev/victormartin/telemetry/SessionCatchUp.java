package dev.victormartin.telemetry;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SessionCatchUp implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final SessionStateHolder sessionStateHolder;

    public SessionCatchUp(JdbcTemplate jdbc, SessionStateHolder sessionStateHolder) {
        this.jdbc = jdbc;
        this.sessionStateHolder = sessionStateHolder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var rows = jdbc.queryForList(
                    """
                    SELECT s.session_uid, s.track_id FROM sessions s
                    WHERE s.created_at = (SELECT MAX(s2.created_at) FROM sessions s2)
                      AND NOT EXISTS (
                        SELECT 1 FROM session_events e
                        WHERE e.session_uid = s.session_uid AND e.event_code = 'SEND'
                      )
                    """);

            if (!rows.isEmpty()) {
                var row = rows.getFirst();
                String uid = String.valueOf(row.get("SESSION_UID"));
                int trackId = ((Number) row.get("TRACK_ID")).intValue();
                sessionStateHolder.onSessionStarted(uid, trackId);
                System.out.println("Session catch-up: restored active session uid=" + uid);
            } else {
                System.out.println("Session catch-up: no active session found");
            }
        } catch (Exception e) {
            System.out.println("Session catch-up: database not available — " + e.getMessage());
        }
    }
}
