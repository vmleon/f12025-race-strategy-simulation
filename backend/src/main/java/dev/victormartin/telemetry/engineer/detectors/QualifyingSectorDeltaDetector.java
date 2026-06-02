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
 * "Purple sector N. X seconds." / "Sector N down 0.4 seconds." Qualifying-only.
 */
public class QualifyingSectorDeltaDetector implements RadioDetector {

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "QualifyingSectorDelta"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.QUALIFYING, SessionKind.SPRINT_QUALIFYING); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        int sector = tick.playerCar().has("sector") ? tick.playerCar().get("sector").asInt() : 0;
        int previous = s.previousSector;
        s.previousSector = sector;
        if (sector == previous) return Optional.empty();

        int completed = -1;
        if (sector == 1 && previous == 0) completed = 0;
        else if (sector == 2 && previous == 1) completed = 1;
        if (completed < 0) return Optional.empty();

        JsonNode arr = tick.playerCar().get("lastSectorMs");
        if (arr == null || !arr.isArray() || arr.size() <= completed) return Optional.empty();
        long ms = arr.get(completed).asLong();
        if (ms <= 0) return Optional.empty();

        long best = s.bestSectorMs[completed];
        if (best <= 0 || ms < best) {
            s.bestSectorMs[completed] = ms;
            return Optional.of(new EngineerMessage(
                    Priority.NORMAL,
                    "Purple sector " + (completed + 1) + ". " + EngineerMessageHelpers.formatLapTime(ms) + ".",
                    tick.wallClockMs(), tick.currentLap(), 2));
        }
        long delta = ms - best;
        return Optional.of(new EngineerMessage(
                Priority.NORMAL,
                "Sector " + (completed + 1) + " down " + EngineerMessageHelpers.formatTenths(delta / 1000.0) + " seconds.",
                tick.wallClockMs(), tick.currentLap(), 2));
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
        int previousSector = -1;
        final long[] bestSectorMs = new long[3];
    }
}
