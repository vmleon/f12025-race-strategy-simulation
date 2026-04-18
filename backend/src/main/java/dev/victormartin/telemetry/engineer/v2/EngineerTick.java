package dev.victormartin.telemetry.engineer.v2;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable per-tick value object passed to every {@link RadioDetector}.
 *
 * The orchestrator builds this once per state update by parsing the incoming
 * JSON, extracting the most-read player fields into typed slots, and
 * computing the {@link PitState} via {@link PitStateClassifier}. JsonNode is
 * still exposed for the AI-cars iteration (which varies in shape) and for
 * fields a particular detector needs that we haven't promoted yet.
 */
public record EngineerTick(
        long wallClockMs,
        String sessionUid,
        int sessionType,
        SessionKind sessionKind,
        int trackId,
        int currentLap,
        int totalLaps,
        int trackLength,
        PitState pitState,
        PitState previousPitState,
        JsonNode state,
        JsonNode playerCar,
        JsonNode cars,
        int playerPos,
        float playerLapDist,
        int playerSpeedKmh,
        float playerThrottle,
        int playerPitStatus,
        int playerPitLaneTimerActive,
        int playerPitLaneTimeMs) {
}
