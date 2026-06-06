package dev.victormartin.telemetry.engineer.detectors;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.EngineerMessageHelpers;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.RadioDetector;
import dev.victormartin.telemetry.engineer.SessionKind;

/**
 * "X closing from behind" — race only, fires when the car immediately behind
 * crosses the 2-second threshold. A 30-second per-pursuer cooldown stops the
 * message from spamming when the gap oscillates around the threshold.
 *
 * Uses the cooldown pattern from
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

        // Out-lap suppression: right after a pit stop the field shuffles through
        // traffic, which otherwise fires contradictory "closing from behind" /
        // "lost a place" battle messages seconds apart. Suppress until the out-lap
        // completes (mirrors PositionChangeDetector's pit-exit guard).
        if (tick.previousPitState() != PitState.ON_TRACK) {
            s.suppressUntilLap = tick.currentLap() + 1;
            s.previousGap = -1f;
            s.previousBehindIdx = -1;
            s.previousPlayerPos = tick.playerPos();
            return Optional.empty();
        }
        if (tick.currentLap() < s.suppressUntilLap) {
            s.previousGap = -1f;
            return Optional.empty();
        }

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
        String text;
        if (gapSec < 1.0f) {
            // Under a second: an explicit gap rounds to "0 seconds" and reads broken.
            text = "Defend from " + name + ".";
        } else {
            int gapWhole = Math.round(gapSec);
            String unit = gapWhole == 1 ? "second" : "seconds";
            text = name + " closing from behind. Gap of " + gapWhole + " " + unit + "."
                    + tyreComparison(behind, tick);
        }
        return Optional.of(new EngineerMessage(Priority.HIGH, text, now, tick.currentLap(), 1));
    }

    /** Appends "{rival} on {new|worn }{compound}s, you're on {compound}s." when the
     * rival is on a different compound. Tyre data is already in the per-car state. */
    private static String tyreComparison(JsonNode behind, EngineerTick tick) {
        JsonNode player = tick.playerCar();
        if (player == null) return "";
        String rivalC = behind.has("tyre") ? behind.get("tyre").asText() : "";
        String playerC = player.has("tyre") ? player.get("tyre").asText() : "";
        if (rivalC.isEmpty() || playerC.isEmpty() || rivalC.equals(playerC)) return "";

        String name = behind.has("name") ? behind.get("name").asText() : "They're";
        int rivalAge = behind.has("tyreAge") ? behind.get("tyreAge").asInt() : -1;
        String qualifier = "";
        if (rivalAge >= 0 && rivalAge <= 3) {
            qualifier = "new ";
        } else if (behind.has("tyreWear") && behind.get("tyreWear").isArray()) {
            double maxWear = 0;
            for (JsonNode w : behind.get("tyreWear")) maxWear = Math.max(maxWear, w.asDouble());
            if (maxWear >= 50.0) qualifier = "worn ";
        }
        return " " + name + " on " + qualifier + compoundPlural(rivalC)
                + ", you're on " + compoundPlural(playerC) + ".";
    }

    private static String compoundPlural(String c) {
        return switch (c) {
            case "S" -> "softs";
            case "M" -> "mediums";
            case "H" -> "hards";
            case "I" -> "inters";
            case "W" -> "wets";
            default -> c;
        };
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
        int suppressUntilLap = 0;
        final Map<Integer, Long> cooldownByCar = new HashMap<>();
    }
}
