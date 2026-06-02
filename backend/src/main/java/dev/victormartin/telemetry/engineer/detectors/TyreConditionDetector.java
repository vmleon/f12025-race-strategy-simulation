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
 * Tyre age announcer + new-tyre out-lap recap.
 */
public class TyreConditionDetector implements RadioDetector {

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "TyreCondition"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        JsonNode p = tick.playerCar();
        int tyreAge = p.has("tyreAge") ? p.get("tyreAge").asInt() : 0;
        String compound = p.has("tyre") ? p.get("tyre").asText() : "";

        Optional<EngineerMessage> out = Optional.empty();

        if (tyreAge < s.previousTyreAge) {
            // Tyre change.
            s.lastAlert = 0;
            out = Optional.of(new EngineerMessage(
                    Priority.NORMAL,
                    "Copy, new " + EngineerMessageHelpers.tyreSpokenName(compound) + " tyres on. Take it easy for the out lap.",
                    tick.wallClockMs(), tick.currentLap(), 1));
        } else if (tyreAge >= 30 && s.lastAlert < 30) {
            out = Optional.of(new EngineerMessage(
                    Priority.HIGH,
                    "Tyres are " + tyreAge + " laps old and degrading. Box soon.",
                    tick.wallClockMs(), tick.currentLap(), 2));
            s.lastAlert = 30;
        } else if (tyreAge >= 20 && s.lastAlert < 20) {
            out = Optional.of(new EngineerMessage(
                    Priority.NORMAL,
                    EngineerMessageHelpers.capitalize(EngineerMessageHelpers.tyreSpokenName(compound))
                            + " tyres are " + tyreAge + " laps old. Consider a pit stop.",
                    tick.wallClockMs(), tick.currentLap(), 3));
            s.lastAlert = 20;
        }

        s.previousTyreAge = tyreAge;
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
        int previousTyreAge = 0;
        int lastAlert = 0;
    }
}
