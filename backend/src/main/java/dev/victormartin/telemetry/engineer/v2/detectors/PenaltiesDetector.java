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
 * Penalty announcer — added seconds, unserved DT/SG, track-limit warnings.
 *
 * Ports v1 detectPenalties. Returns at most one message per tick (priority
 * order: unserved penalty &gt; track-limits &gt; new penalty seconds).
 */
public class PenaltiesDetector implements RadioDetector {

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "Penalties"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        JsonNode p = tick.playerCar();
        int penalties = p.has("pen") ? p.get("pen").asInt() : 0;
        int unservedDT = p.has("unservedDT") ? p.get("unservedDT").asInt() : 0;
        int unservedSG = p.has("unservedSG") ? p.get("unservedSG").asInt() : 0;
        int warnings = p.has("warnings") ? p.get("warnings").asInt() : 0;

        Optional<EngineerMessage> out = Optional.empty();

        int totalUnserved = unservedDT + unservedSG;
        if (totalUnserved > s.previousUnserved) {
            String type = unservedDT > s.previousUnservedDT ? "drive-through" : "stop-go";
            out = Optional.of(new EngineerMessage(
                    Priority.IMMEDIATE,
                    "You have an unserved " + type + " penalty. Box this lap.",
                    tick.wallClockMs(), tick.currentLap(), 2));
        } else if (warnings > s.previousWarnings) {
            out = Optional.of(new EngineerMessage(
                    Priority.IMMEDIATE,
                    "Track limits warning. That's warning number " + warnings + ". Be careful.",
                    tick.wallClockMs(), tick.currentLap(), 2));
        } else if (penalties > s.previousPenaltySeconds) {
            int added = penalties - s.previousPenaltySeconds;
            out = Optional.of(new EngineerMessage(
                    Priority.HIGH,
                    "Penalty received. " + added + " seconds added. We'll talk strategy.",
                    tick.wallClockMs(), tick.currentLap(), 3));
        }

        s.previousPenaltySeconds = penalties;
        s.previousUnservedDT = unservedDT;
        s.previousUnservedSG = unservedSG;
        s.previousUnserved = totalUnserved;
        s.previousWarnings = warnings;
        return out;
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
        int previousPenaltySeconds = 0;
        int previousUnservedDT = 0;
        int previousUnservedSG = 0;
        int previousUnserved = 0;
        int previousWarnings = 0;
    }
}
