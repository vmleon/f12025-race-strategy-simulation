package dev.victormartin.telemetry.engineer;

public record EngineerMessage(
        Priority priority,
        String text,
        long createdAt,
        int createdAtLap,
        int ttlLaps
) {
    /** Wall-clock TTL per priority: discard messages older than this before delivery. */
    static final long TTL_MILLIS_IMMEDIATE = 8_000;
    static final long TTL_MILLIS_HIGH = 30_000;
    static final long TTL_MILLIS_NORMAL = 60_000;

    public enum Priority {
        IMMEDIATE,
        HIGH,
        NORMAL
    }

    public boolean isExpired(int currentLap) {
        return currentLap > createdAtLap + ttlLaps;
    }

    public boolean isStale() {
        long ttl = switch (priority) {
            case IMMEDIATE -> TTL_MILLIS_IMMEDIATE;
            case HIGH -> TTL_MILLIS_HIGH;
            case NORMAL -> TTL_MILLIS_NORMAL;
        };
        return System.currentTimeMillis() - createdAt > ttl;
    }
}
