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
 * Safety car coming-in alert. Deployment is event-driven (handled in
 * {@code RaceEngineerServiceV2.onEvent}); this detector only watches for the
 * status flipping back to 0 (green flag next lap).
 *
 * Ports v1 detectFlagChanges. Behaviour parity.
 */
public class FlagChangesDetector implements RadioDetector {

    private final Map<String, Integer> previousStatusByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "FlagChanges"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        int safetyCarStatus = tick.state().has("safetyCarStatus") ? tick.state().get("safetyCarStatus").asInt() : 0;
        Integer previous = previousStatusByUid.get(tick.sessionUid());
        previousStatusByUid.put(tick.sessionUid(), safetyCarStatus);

        if (previous != null && previous > 0 && safetyCarStatus == 0) {
            return Optional.of(new EngineerMessage(
                    Priority.IMMEDIATE,
                    "Safety car coming in. Green flag next lap. Push now, push now.",
                    tick.wallClockMs(), tick.currentLap(), 2));
        }
        return Optional.empty();
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        previousStatusByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        previousStatusByUid.remove(sessionUid);
    }
}
