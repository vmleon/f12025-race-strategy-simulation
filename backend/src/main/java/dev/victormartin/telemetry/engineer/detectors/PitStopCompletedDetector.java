package dev.victormartin.telemetry.engineer.detectors;

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
 * "Good stop. X seconds. Push now." + pit-exit recap. Race only.
 *
 * The detection runs across all pit
 * states because the transition signal comes from the pitStopCount changing
 * on the way out — but we only enqueue the messages once.
 */
public class PitStopCompletedDetector implements RadioDetector {

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "PitStopCompleted"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); /* runs always so the pit-cycle latch sees both halves */ }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        int pitStatus = tick.playerPitStatus();
        int pitCount = tick.playerCar().has("pits") ? tick.playerCar().get("pits").asInt() : 0;

        Optional<EngineerMessage> out = Optional.empty();

        if (pitStatus > 0 && s.previousPitStatus == 0) {
            s.pitEnteredAt = tick.wallClockMs();
            s.recapPending = false;
        }
        if (pitStatus == 0 && s.previousPitStatus > 0 && pitCount > s.previousPitCount) {
            float durationSec = s.pitEnteredAt > 0
                    ? (tick.wallClockMs() - s.pitEnteredAt) / 1000f : 0f;
            s.pitEnteredAt = 0;
            s.recapPending = true;
            out = Optional.of(new EngineerMessage(
                    Priority.HIGH,
                    "Good stop. " + EngineerMessageHelpers.formatTenths(durationSec) + " seconds. Push now.",
                    tick.wallClockMs(), tick.currentLap(), 1));
        }

        s.previousPitStatus = pitStatus;
        s.previousPitCount = pitCount;
        return out;
    }

    /** Tells the orchestrator to also enqueue a pit-exit recap on the same exit tick. */
    public Optional<EngineerMessage> takePendingRecap(EngineerTick tick) {
        State s = stateByUid.get(tick.sessionUid());
        if (s == null || !s.recapPending) return Optional.empty();
        s.recapPending = false;
        int exitPos = tick.playerPos();
        return Optional.of(new EngineerMessage(
                Priority.HIGH,
                buildRecap(tick.cars(), exitPos, tick.currentLap(), tick.playerLapDist(), tick.trackLength()),
                tick.wallClockMs(), tick.currentLap(), 2));
    }

    private static String buildRecap(JsonNode cars, int exitPos, int playerLap, float playerLapDist, int trackLength) {
        StringBuilder sb = new StringBuilder("Out of the pits in P").append(exitPos).append(".");
        JsonNode ahead = exitPos > 1 ? EngineerMessageHelpers.findCarAtPosition(cars, exitPos - 1) : null;
        JsonNode behind = EngineerMessageHelpers.findCarAtPosition(cars, exitPos + 1);
        if (ahead != null) {
            float gap = EngineerMessageHelpers.gapToCarSeconds(ahead, playerLap, playerLapDist, trackLength);
            String name = ahead.has("name") ? ahead.get("name").asText() : "car ahead";
            if (gap >= 0) sb.append(" ").append(EngineerMessageHelpers.formatTenths(gap)).append(" seconds to ").append(name).append(".");
        }
        if (behind != null) {
            int behindLap = behind.has("lap") ? behind.get("lap").asInt() : 0;
            if (behindLap == playerLap) {
                float behindDist = behind.has("lapDist") ? (float) behind.get("lapDist").asDouble() : 0f;
                float gap = playerLapDist - behindDist;
                if (gap < 0) gap += trackLength;
                gap /= EngineerMessageHelpers.METRES_PER_SECOND;
                String name = behind.has("name") ? behind.get("name").asText() : "car behind";
                sb.append(" ").append(EngineerMessageHelpers.formatTenths(gap)).append(" seconds to ").append(name).append(".");
            }
        }
        return sb.toString();
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
        int previousPitStatus = 0;
        int previousPitCount = 0;
        long pitEnteredAt = 0;
        boolean recapPending = false;
    }
}
