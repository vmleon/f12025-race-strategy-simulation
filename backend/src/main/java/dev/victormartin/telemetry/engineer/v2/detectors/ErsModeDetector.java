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
 * "ERS mode N. Go to strat N." — fires on ERS mode change. Suppressed when
 * the game's ERS assist is on (player isn't in control).
 *
 * Ports v1 detectErsMode.
 */
public class ErsModeDetector implements RadioDetector {

    private final Map<String, Integer> previousByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "ErsMode"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        int ersAssist = tick.state().has("ersAssist") ? tick.state().get("ersAssist").asInt() : 0;
        if (ersAssist != 0) return Optional.empty();

        int mode = tick.playerCar().has("ersMode") ? tick.playerCar().get("ersMode").asInt() : -1;
        if (mode < 0) return Optional.empty();

        Integer previous = previousByUid.get(tick.sessionUid());
        previousByUid.put(tick.sessionUid(), mode);
        if (previous == null || previous == mode) return Optional.empty();

        return Optional.of(new EngineerMessage(
                Priority.NORMAL,
                "ERS mode " + mode + ". Go to strat " + mode + ".",
                tick.wallClockMs(), tick.currentLap(), 1));
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
