package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * Race-end announcer — final position, DNF/DSQ. Race only.
 *
 * Ports v1 detectRaceFinish. The chequered flag bit comes from
 * {@link #notifyChequered(String)} which the orchestrator calls from
 * {@code onEvent("CHQF")}.
 */
public class RaceFinishDetector implements RadioDetector {

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "RaceFinish"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE); }

    public void notifyChequered(String sessionUid) {
        State s = stateByUid.computeIfAbsent(sessionUid, k -> new State());
        s.chequered = true;
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        if (s.finished) return Optional.empty();

        int rs = tick.playerCar().has("resultStatus") ? tick.playerCar().get("resultStatus").asInt() : 2;
        String dnfText = switch (rs) {
            case 4, 7 -> "That's our day done. Engine off, come back to the pits.";
            case 5 -> "Disqualified. We'll review and figure out what happened.";
            case 6 -> "Didn't make the classified distance. Tough one.";
            default -> null;
        };
        if (dnfText != null) {
            s.finished = true;
            return Optional.of(new EngineerMessage(
                    Priority.IMMEDIATE, dnfText,
                    tick.wallClockMs(), tick.currentLap(), 5));
        }

        if (rs == 3 || s.chequered) {
            s.finished = true;
            int pos = tick.playerPos();
            int gridSize = tick.cars().size();
            return Optional.of(new EngineerMessage(
                    Priority.IMMEDIATE,
                    buildFinishMessage(pos, gridSize),
                    tick.wallClockMs(), tick.currentLap(), 5));
        }
        return Optional.empty();
    }

    static String buildFinishMessage(int pos, int gridSize) {
        if (pos <= 0) return "Chequered flag. Box this lap.";
        if (pos <= 3) return "P" + pos + "! Brilliant drive. Cooldown lap, bring it home.";
        if (pos <= 10) return "P" + pos + ". Solid points today. Good job.";
        int midfieldEnd = Math.max(10, gridSize * 3 / 4);
        if (pos <= midfieldEnd) return "P" + pos + ". We'll take it. Plenty to review.";
        if (pos < gridSize) return "P" + pos + ". Tough one. We'll debrief and come back stronger.";
        return "P" + pos + ". Rough day at the office. Box this lap and we regroup.";
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
        boolean finished = false;
        boolean chequered = false;
    }
}
