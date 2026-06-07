package dev.victormartin.telemetry.engineer.log;

/**
 * One delivered radio message plus the situational context captured at the
 * moment it was broadcast to the iOS client. {@code sessionUid} is the unsigned
 * hex string of the game session uid as serialized by telemetry; the radio log
 * converts it to the signed NUMBER value before persisting (see
 * {@code JdbcRadioMessageLog#parseSessionUid}). {@code bestStrategiesJson} is null
 * when no strategy evaluation exists yet (e.g. Practice). {@code jobId} links the
 * row to the strategy run that justified it ({@code simulation_run_io.job_id});
 * null when no evaluation has been pushed yet.
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
        String renderedText,
        String bestStrategiesJson,
        String jobId,
        long sentAtEpochMs
) {}
