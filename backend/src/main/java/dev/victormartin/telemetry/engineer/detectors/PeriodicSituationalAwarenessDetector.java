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
 * "P3. 1.2 to NORRIS. 0.8 to RUSSELL." Race only, every 3 laps, suppressed if
 * a reactive ahead/behind message already fired this lap.
 *
 * Reactive-suppression hook is
 * exposed via {@link #notifyReactiveFired(String, int)}.
 */
public class PeriodicSituationalAwarenessDetector implements RadioDetector {

    private static final int EVERY_N_LAPS = 3;

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "PeriodicSituationalAwareness"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE); }

    public void notifyReactiveFired(String sessionUid, int lap) {
        State s = stateByUid.computeIfAbsent(sessionUid, k -> new State());
        s.lastReactiveLap = lap;
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        int lap = tick.currentLap();
        if (lap <= 1) return Optional.empty();
        if (tick.totalLaps() > 0 && lap >= tick.totalLaps()) return Optional.empty();
        if (lap % EVERY_N_LAPS != 0) return Optional.empty();
        if (s.lastFiredLap == lap) return Optional.empty();
        if (s.lastReactiveLap == lap) return Optional.empty();

        int pos = tick.playerPos();
        JsonNode ahead = pos > 1 ? EngineerMessageHelpers.findCarAtPosition(tick.cars(), pos - 1) : null;
        JsonNode behind = EngineerMessageHelpers.findCarAtPosition(tick.cars(), pos + 1);
        if (ahead == null && behind == null) return Optional.empty();

        StringBuilder sb = new StringBuilder("P").append(pos).append(".");
        if (ahead != null) {
            float gap = EngineerMessageHelpers.gapToCarSeconds(ahead, lap, tick.playerLapDist(), tick.trackLength());
            String name = ahead.has("name") ? ahead.get("name").asText() : "car ahead";
            if (gap >= 0) sb.append(" ").append(EngineerMessageHelpers.formatTenths(gap)).append(" seconds to ").append(name).append(".");
        }
        if (behind != null) {
            int behindLap = behind.has("lap") ? behind.get("lap").asInt() : 0;
            if (behindLap == lap) {
                float behindDist = behind.has("lapDist") ? (float) behind.get("lapDist").asDouble() : 0f;
                float gap = tick.playerLapDist() - behindDist;
                if (gap < 0) gap += tick.trackLength();
                gap /= EngineerMessageHelpers.METRES_PER_SECOND;
                String name = behind.has("name") ? behind.get("name").asText() : "car behind";
                sb.append(" ").append(EngineerMessageHelpers.formatTenths(gap)).append(" seconds to ").append(name).append(".");
            }
        }

        s.lastFiredLap = lap;
        return Optional.of(new EngineerMessage(
                Priority.NORMAL, sb.toString(),
                tick.wallClockMs(), lap, 2));
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
        int lastFiredLap = 0;
        int lastReactiveLap = 0;
    }
}
