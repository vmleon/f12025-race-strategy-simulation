package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * Speed-trap ranking during free practice. Tells the driver where they sit
 * relative to the field on the speed-trap leaderboard — a useful signal for
 * downforce/wing setup work ("P3 in the speed trap, 327 km/h").
 *
 * Fires when the player's speed-trap value improves to a new personal best.
 * 60-second cooldown so a string of small improvements doesn't spam the radio.
 */
public class PracticeSpeedTrapDetector implements RadioDetector {

    private static final long COOLDOWN_MS = 60_000L;
    private static final float MIN_IMPROVEMENT_KMH = 1.0f;

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "PracticeSpeedTrap"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.PRACTICE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());

        JsonNode p = tick.playerCar();
        float playerSpeed = p.has("speedTrap") ? (float) p.get("speedTrap").asDouble() : 0f;
        if (playerSpeed <= 0f) return Optional.empty();
        if (playerSpeed - s.lastFiredSpeedKmh < MIN_IMPROVEMENT_KMH) return Optional.empty();

        long now = tick.wallClockMs();
        if (now - s.lastFiredAtMs < COOLDOWN_MS && s.lastFiredAtMs > 0) return Optional.empty();

        // Rank: count cars whose speed-trap is faster than the player's.
        int faster = 0;
        for (JsonNode car : tick.cars()) {
            if (car == p) continue;
            float v = car.has("speedTrap") ? (float) car.get("speedTrap").asDouble() : 0f;
            if (v > playerSpeed) faster++;
        }
        int rank = faster + 1;

        s.lastFiredSpeedKmh = playerSpeed;
        s.lastFiredAtMs = now;

        String text = "P" + rank + " in the speed trap, "
                + Math.round(playerSpeed) + " kilometres per hour.";
        return Optional.of(new EngineerMessage(
                Priority.NORMAL, text,
                now, tick.currentLap(), 3));
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        stateByUid.put(sessionUid, new State());
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        stateByUid.remove(sessionUid);
    }

    private static class State {
        float lastFiredSpeedKmh = 0f;
        long lastFiredAtMs = 0L;
    }
}
