package dev.victormartin.telemetry.engineer;

public record EngineerMessage(
        Priority priority,
        String text,
        long createdAt,
        int createdAtLap,
        int ttlLaps
) {
    public enum Priority {
        IMMEDIATE,
        HIGH,
        NORMAL
    }

    public boolean isExpired(int currentLap) {
        return currentLap > createdAtLap + ttlLaps;
    }
}
