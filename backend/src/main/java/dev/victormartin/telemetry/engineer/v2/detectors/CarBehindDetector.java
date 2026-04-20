package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerMessageHelpers;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * "X closing from behind" — race only, fires when the car immediately behind
 * crosses the 2-second threshold. A 30-second per-pursuer cooldown stops the
 * message from spamming when the gap oscillates around the threshold.
 *
 * Ports v1 detectCarBehind, plus cooldown pattern from
 * SlowLapTrafficWarningDetector.
 */
public class CarBehindDetector implements RadioDetector {

    private static final float CLOSE_THRESHOLD_SEC = 2.0f;
    private static final long COOLDOWN_MS = 30_000L;

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "CarBehind"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());

        JsonNode behind = EngineerMessageHelpers.findCarAtPosition(tick.cars(), tick.playerPos() + 1);
        if (behind == null) {
            s.previousGap = -1f;
            s.previousBehindIdx = -1;
            s.previousPlayerPos = tick.playerPos();
            return Optional.empty();
        }

        int behindLap = behind.has("lap") ? behind.get("lap").asInt() : 0;
        if (behindLap != tick.currentLap()) {
            s.previousGap = -1f;
            s.previousBehindIdx = -1;
            s.previousPlayerPos = tick.playerPos();
            return Optional.empty();
        }

        // Prefer the game's authoritative delta (deltaToCarInFront on the car
        // behind the player IS the gap from that car to the player). The
        // distance/55 m/s fallback is only used when the game hasn't populated
        // the delta yet (early laps, lap-up boundary).
        long deltaMs = behind.has("deltaToFrontMs") ? behind.get("deltaToFrontMs").asLong() : 0L;
        float gapSec;
        if (deltaMs > 0) {
            gapSec = deltaMs / 1000f;
        } else {
            float behindLapDist = behind.has("lapDist") ? (float) behind.get("lapDist").asDouble() : 0f;
            float gap = tick.playerLapDist() - behindLapDist;
            if (gap < 0) gap += tick.trackLength();
            gapSec = gap / EngineerMessageHelpers.METRES_PER_SECOND;
        }

        int behindIdx = behind.has("idx") ? behind.get("idx").asInt() : -1;
        boolean identityChanged = behindIdx != s.previousBehindIdx
                || tick.playerPos() != s.previousPlayerPos;
        float previous = s.previousGap;
        s.previousGap = gapSec;
        s.previousBehindIdx = behindIdx;
        s.previousPlayerPos = tick.playerPos();
        if (identityChanged) return Optional.empty();

        boolean isClose = gapSec < CLOSE_THRESHOLD_SEC;
        boolean wasClose = previous > 0 && previous < CLOSE_THRESHOLD_SEC;
        if (!isClose || wasClose) return Optional.empty();

        long now = tick.wallClockMs();
        Long lastFired = s.cooldownByCar.get(behindIdx);
        if (lastFired != null && (now - lastFired) < COOLDOWN_MS) return Optional.empty();
        s.cooldownByCar.put(behindIdx, now);

        String name = behind.has("name") ? behind.get("name").asText() : "Car behind";
        return Optional.of(new EngineerMessage(
                Priority.HIGH,
                name + " closing from behind. " + EngineerMessageHelpers.formatTenths(gapSec)
                        + " seconds back. Defend your position.",
                now, tick.currentLap(), 1));
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
        float previousGap = -1f;
        int previousBehindIdx = -1;
        int previousPlayerPos = -1;
        final Map<Integer, Long> cooldownByCar = new HashMap<>();
    }
}
