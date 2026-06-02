package dev.victormartin.telemetry.engineer.log;

/**
 * One delivered radio message plus the situational context captured at the
 * moment it was broadcast to the iOS client. {@code sessionUid} is the decimal
 * string of the game session uid (bound directly to the NUMBER column, matching
 * how {@code SessionController} queries it). {@code bestStrategiesJson} is null
 * when no strategy evaluation exists yet (e.g. Practice).
 */
public record RadioMessageLogEntry(
        String sessionUid,
        int trackId,
        int sessionType,
        int lapNumber,
        int totalLaps,
        int playerPosition,
        double lapDistanceM,
        int sector,
        String pitState,
        String tyreCompound,
        int tyreAgeLaps,
        String priority,
        String messageText,
        String bestStrategiesJson,
        long sentAtEpochMs
) {}
