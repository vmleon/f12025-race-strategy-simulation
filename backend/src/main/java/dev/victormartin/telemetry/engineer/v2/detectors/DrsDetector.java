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
 * "DRS enabled" — fires when DRS becomes available on a lap.
 * Suppressed when {@code drsAssist != 0} (game does it for the player).
 *
 * Ports v1 detectDrs.
 */
public class DrsDetector implements RadioDetector {

    private final Map<String, Integer> previousByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "Drs"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        int drsAssist = tick.state().has("drsAssist") ? tick.state().get("drsAssist").asInt() : 0;
        if (drsAssist != 0) return Optional.empty();

        int drsAllowed = tick.playerCar().has("drsAllowed") ? tick.playerCar().get("drsAllowed").asInt() : 0;
        Integer previous = previousByUid.get(tick.sessionUid());
        previousByUid.put(tick.sessionUid(), drsAllowed);

        if (drsAllowed > 0 && (previous == null || previous == 0)) {
            return Optional.of(new EngineerMessage(
                    Priority.NORMAL, "DRS enabled.",
                    tick.wallClockMs(), tick.currentLap(), 1));
        }
        return Optional.empty();
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        previousByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        previousByUid.remove(sessionUid);
    }
}
