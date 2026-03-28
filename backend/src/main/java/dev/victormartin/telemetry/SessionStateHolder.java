package dev.victormartin.telemetry;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.stereotype.Component;

@Component
public class SessionStateHolder {

    private final RaceWebSocketHandler raceWebSocketHandler;
    private final QueueService queueService;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private String sessionUid;
    private int trackId;
    private boolean active;

    public SessionStateHolder(RaceWebSocketHandler raceWebSocketHandler,
                              QueueService queueService) {
        this.raceWebSocketHandler = raceWebSocketHandler;
        this.queueService = queueService;
    }

    public void onSessionStarted(String sessionUid, int trackId) {
        lock.writeLock().lock();
        try {
            this.sessionUid = sessionUid;
            this.trackId = trackId;
            this.active = true;
            System.out.println("Session started: uid=" + sessionUid + " trackId=" + trackId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onSessionEnded(String sessionUid) {
        int endedTrackId;
        lock.writeLock().lock();
        try {
            this.active = false;
            endedTrackId = this.trackId;
            System.out.println("Session ended: uid=" + sessionUid);
        } finally {
            lock.writeLock().unlock();
        }

        raceWebSocketHandler.broadcast("{\"type\":\"sessionEnded\",\"sessionUid\":\"" + sessionUid + "\"}");

        // Enqueue calibration request for the track
        System.out.println("Enqueuing calibration request for track " + endedTrackId + " after session " + sessionUid);
        queueService.enqueue("PDBADMIN.CALIBRATION_REQUEST",
                "{\"trackId\":" + endedTrackId + ",\"sessionUid\":\"" + sessionUid + "\",\"trigger\":\"sessionEnded\"}");
    }

    public SessionInfo getActiveSession() {
        lock.readLock().lock();
        try {
            if (!active) return null;
            return new SessionInfo(sessionUid, trackId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isSessionActive() {
        lock.readLock().lock();
        try {
            return active;
        } finally {
            lock.readLock().unlock();
        }
    }

    public record SessionInfo(String sessionUid, int trackId) {}
}
