package dev.victormartin.telemetry.engineer;

public record EngineerMessage(
        Priority priority,
        String text,
        long createdAt,
        int createdAtLap,
        int ttlLaps
) {
    /** Wall-clock TTL: discard messages older than this before delivery. */
    static final long TTL_MILLIS = 8_000;

    public enum Priority {
        IMMEDIATE,
        HIGH,
        NORMAL
    }

    public boolean isExpired(int currentLap) {
        return currentLap > createdAtLap + ttlLaps;
    }

    public boolean isStale() {
        return System.currentTimeMillis() - createdAt > TTL_MILLIS;
    }
}
