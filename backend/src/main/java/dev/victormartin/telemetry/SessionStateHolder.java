package dev.victormartin.telemetry;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.stereotype.Component;

@Component
public class SessionStateHolder {

    private final RaceWebSocketHandler raceWebSocketHandler;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private String sessionUid;
    private int trackId;
    private boolean active;

    public SessionStateHolder(RaceWebSocketHandler raceWebSocketHandler) {
        this.raceWebSocketHandler = raceWebSocketHandler;
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
        lock.writeLock().lock();
        try {
            this.active = false;
            System.out.println("Session ended: uid=" + sessionUid);
            System.out.println("Calibration triggered for session " + sessionUid);
        } finally {
            lock.writeLock().unlock();
        }

        raceWebSocketHandler.broadcast("{\"type\":\"sessionEnded\",\"sessionUid\":\"" + sessionUid + "\"}");
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
