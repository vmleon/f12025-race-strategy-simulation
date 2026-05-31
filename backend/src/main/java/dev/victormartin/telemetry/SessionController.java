package dev.victormartin.telemetry;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionStateHolder sessionStateHolder;
    private final RaceWebSocketHandler raceWebSocketHandler;
    private final JdbcTemplate jdbc;

    public SessionController(SessionStateHolder sessionStateHolder,
                             RaceWebSocketHandler raceWebSocketHandler,
                             JdbcTemplate jdbc) {
        this.sessionStateHolder = sessionStateHolder;
        this.raceWebSocketHandler = raceWebSocketHandler;
        this.jdbc = jdbc;
    }

    public record ActiveSessionDto(String sessionUid, String trackName, String sessionType, boolean live) {}

    private static final long LIVE_THRESHOLD_MS = 5_000;

    @GetMapping("/active")
    public List<ActiveSessionDto> activeSessions() {
        long now = System.currentTimeMillis();
        return sessionStateHolder.getActiveSessions().stream()
                .map(s -> {
                    String sessionType = "";
                    try {
                        int typeId = jdbc.queryForObject(
                                "SELECT session_type FROM sessions WHERE session_uid = ?",
                                Integer.class, s.sessionUid());
                        sessionType = GameMappings.sessionTypeName(typeId);
                    } catch (Exception ignored) {}
                    boolean live = (now - s.lastStateAtMs()) < LIVE_THRESHOLD_MS;
                    return new ActiveSessionDto(
                            s.sessionUid(),
                            GameMappings.trackName(s.trackId()),
                            sessionType,
                            live);
                })
                .sorted((a, b) -> Boolean.compare(b.live(), a.live()))
                .toList();
    }

    @GetMapping("/active/state")
    public ResponseEntity<String> activeState() {
        if (sessionStateHolder.getActiveSessions().isEmpty()) return ResponseEntity.notFound().build();
        String state = raceWebSocketHandler.getLatestState();
        if (state == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(state);
    }
}
