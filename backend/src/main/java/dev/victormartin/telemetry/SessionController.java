package dev.victormartin.telemetry;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionStateHolder sessionStateHolder;
    private final RaceWebSocketHandler raceWebSocketHandler;

    public SessionController(SessionStateHolder sessionStateHolder, RaceWebSocketHandler raceWebSocketHandler) {
        this.sessionStateHolder = sessionStateHolder;
        this.raceWebSocketHandler = raceWebSocketHandler;
    }

    @GetMapping("/active")
    public ResponseEntity<SessionStateHolder.SessionInfo> activeSession() {
        var session = sessionStateHolder.getActiveSession();
        if (session == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(session);
    }

    @GetMapping("/active/state")
    public ResponseEntity<String> activeState() {
        if (!sessionStateHolder.isSessionActive()) return ResponseEntity.notFound().build();
        String state = raceWebSocketHandler.getLatestState();
        if (state == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(state);
    }
}
