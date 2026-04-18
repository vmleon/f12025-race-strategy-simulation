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
 * Position change announcer with debounce + consolidation.
 *
 * v1 / early v2 fired immediately on any position change. In a battle this
 * spammed the radio with "lost a place / gained / lost again" in quick
 * succession. v2 buffers changes for {@link #DEBOUNCE_MS} of stable position
 * before emitting a single message that reports the *net* change against the
 * pre-battle baseline:
 *
 *  - Net zero (lost-then-regained, regained-then-lost) → silence
 *  - Net gain of N places → "P{pos}. {name ahead} is next, {gap}s up the road"
 *    (or "Up N places. P{pos}. ..." for N ≥ 2)
 *  - Net loss of N places → "Lost a place. P{pos}. {name ahead} is now ahead"
 *    (or "Lost N places. P{pos}. ..." for N ≥ 2)
 */
public class PositionChangeDetector implements RadioDetector {

    private static final long DEBOUNCE_MS = 5_000L;
    private static final float METRES_PER_SECOND = 55f;

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "PositionChange"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        int currentPos = tick.playerPos();

        // First ON_TRACK tick of a session, or first ON_TRACK tick after a pit
        // cycle: reset the baseline, drop any pending fire, emit nothing. The
        // grid may have reshuffled while we were in the pit lane.
        if (tick.previousPitState() != PitState.ON_TRACK) {
            s.baseline = currentPos;
            s.lastSeen = currentPos;
            s.pendingFireAt = 0;
            return Optional.empty();
        }

        // First-ever tick on track: seed baseline.
        if (s.lastSeen == 0) {
            s.baseline = currentPos;
            s.lastSeen = currentPos;
            return Optional.empty();
        }

        long now = tick.wallClockMs();

        if (currentPos != s.lastSeen) {
            // Position changed this tick. If no debounce was running, seed the
            // baseline at where we *were* — that's the pre-battle position we
            // want to compare the eventual settled position against.
            if (s.pendingFireAt == 0) {
                s.baseline = s.lastSeen;
            }
            // Extend (or start) the debounce window.
            s.pendingFireAt = now + DEBOUNCE_MS;
            s.lastSeen = currentPos;
            return Optional.empty();
        }

        // Position unchanged this tick. Check if a pending fire is due.
        if (s.pendingFireAt == 0 || now < s.pendingFireAt) {
            return Optional.empty();
        }

        int net = s.baseline - currentPos; // positive = gained, negative = lost
        s.pendingFireAt = 0;
        s.baseline = currentPos;
        if (net == 0) return Optional.empty();

        if (net > 0) {
            String text;
            if (currentPos <= 1) {
                text = "P1. Leading now.";
            } else {
                JsonNode carAhead = findCarAtPosition(tick.cars(), currentPos - 1);
                String name = carAhead != null && carAhead.has("name")
                        ? carAhead.get("name").asText() : "Car ahead";
                float gap = gapToCarSeconds(tick, carAhead);
                String tail = gap >= 0
                        ? name + " is next, " + formatTenths(gap) + " seconds up the road."
                        : name + " is next.";
                text = (net == 1)
                        ? "P" + currentPos + ". " + tail
                        : "Up " + net + " places. P" + currentPos + ". " + tail;
            }
            return Optional.of(new EngineerMessage(
                    Priority.IMMEDIATE, text,
                    now, tick.currentLap(), 1));
        }

        int lost = -net;
        JsonNode carAhead = findCarAtPosition(tick.cars(), currentPos - 1);
        String name = carAhead != null && carAhead.has("name")
                ? carAhead.get("name").asText() : "Car ahead";
        String text = (lost == 1)
                ? "Lost a place. P" + currentPos + ". " + name + " is now ahead."
                : "Lost " + lost + " places. P" + currentPos + ". " + name + " is now ahead.";
        return Optional.of(new EngineerMessage(
                Priority.HIGH, text,
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

    private static JsonNode findCarAtPosition(JsonNode cars, int pos) {
        for (JsonNode car : cars) {
            int p = car.has("pos") ? car.get("pos").asInt() : 0;
            if (p == pos) return car;
        }
        return null;
    }

    private static float gapToCarSeconds(EngineerTick tick, JsonNode otherCar) {
        if (otherCar == null) return -1f;
        int otherLap = otherCar.has("lap") ? otherCar.get("lap").asInt() : 0;
        if (tick.currentLap() != otherLap) return -1f;
        float otherDist = otherCar.has("lapDist") ? (float) otherCar.get("lapDist").asDouble() : 0f;
        float gap = otherDist - tick.playerLapDist();
        if (gap < 0) gap += tick.trackLength();
        return gap / METRES_PER_SECOND;
    }

    private static String formatTenths(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.floor(rounded)) return String.format("%.0f", rounded);
        return String.format("%.1f", rounded);
    }

    private static class State {
        int baseline = 0;        // pre-battle position; the message compares against this
        int lastSeen = 0;        // most recent observed position
        long pendingFireAt = 0;  // 0 = no pending fire; else absolute wallClockMs to fire at
    }
}
