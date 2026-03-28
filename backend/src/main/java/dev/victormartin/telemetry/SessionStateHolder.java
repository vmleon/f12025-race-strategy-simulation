package dev.victormartin.telemetry;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

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

    public void onSessionStarted(String sessionUid, int trackId) {
        sessions.put(sessionUid, new SessionInfo(sessionUid, trackId));
        System.out.println("Session started: uid=" + sessionUid + " trackId=" + trackId);
    }

    public void onSessionEnded(String sessionUid) {
        SessionInfo removed = sessions.remove(sessionUid);
        int endedTrackId = removed != null ? removed.trackId() : -1;
        System.out.println("Session ended: uid=" + sessionUid);

        raceWebSocketHandler.broadcast("{\"type\":\"sessionEnded\",\"sessionUid\":\"" + sessionUid + "\"}");

        // Enqueue calibration request for the track
        if (endedTrackId >= 0) {
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

    public record SessionInfo(String sessionUid, int trackId) {}
}
