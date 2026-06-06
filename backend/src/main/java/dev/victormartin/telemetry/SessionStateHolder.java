package dev.victormartin.telemetry;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.engineer.SessionKind;

@Component
public class SessionStateHolder {

    private final RaceWebSocketHandler raceWebSocketHandler;
    private final QueueService queueService;
    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public SessionStateHolder(RaceWebSocketHandler raceWebSocketHandler,
                              QueueService queueService) {
        this.raceWebSocketHandler = raceWebSocketHandler;
        this.queueService = queueService;
    }

    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        sessions.put(sessionUid, new SessionInfo(sessionUid, trackId, sessionType, System.currentTimeMillis()));
        System.out.println("Session started: uid=" + sessionUid + " trackId=" + trackId);
    }

    public void onStateReceived(String sessionUid) {
        long now = System.currentTimeMillis();
        sessions.computeIfPresent(sessionUid,
                (k, old) -> new SessionInfo(k, old.trackId(), old.sessionType(), now));
    }

    public void onSessionEnded(String sessionUid) {
        SessionInfo removed = sessions.remove(sessionUid);
        int endedTrackId = removed != null ? removed.trackId() : -1;
        System.out.println("Session ended: uid=" + sessionUid);

        raceWebSocketHandler.broadcast("{\"type\":\"sessionEnded\",\"sessionUid\":\"" + sessionUid + "\"}");

        // Calibration learns only from Free Practice: Qualy pace (push-mode ERS, low
        // fuel) and Race pace (traffic, dirty air, fuel saving) aren't clean baselines.
        boolean isPractice = removed != null
                && SessionKind.fromSessionType(removed.sessionType()) == SessionKind.PRACTICE;
        if (endedTrackId >= 0 && isPractice) {
            System.out.println("Enqueuing calibration request for track " + endedTrackId + " after session " + sessionUid);
            queueService.enqueue("PDBADMIN.CALIBRATION_REQUEST",
                    "{\"trackId\":" + endedTrackId + ",\"sessionUid\":\"" + sessionUid + "\",\"trigger\":\"sessionEnded\"}");
        }
    }

    public List<SessionInfo> getActiveSessions() {
        return List.copyOf(sessions.values());
    }

    public boolean isSessionActive(String sessionUid) {
        return sessions.containsKey(sessionUid);
    }

    public record SessionInfo(String sessionUid, int trackId, int sessionType, long lastStateAtMs) {
        public SessionInfo(String sessionUid, int trackId, int sessionType) {
            this(sessionUid, trackId, sessionType, System.currentTimeMillis());
        }
    }
}
