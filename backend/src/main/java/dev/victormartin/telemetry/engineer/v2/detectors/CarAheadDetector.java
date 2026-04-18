package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerMessageHelpers;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * "You have DRS. Attack." — race only, fires when player drops below 1.0s of
 * the car ahead (DRS range). Suppressed when DRS assist is on.
 *
 * Ports v1 detectCarAhead.
 */
public class CarAheadDetector implements RadioDetector {

    private static final float DRS_RANGE_SEC = 1.0f;

    private final Map<String, Float> previousGapByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "CarAhead"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        int drsAssist = tick.state().has("drsAssist") ? tick.state().get("drsAssist").asInt() : 0;
        if (drsAssist != 0) return Optional.empty();
        if (tick.playerPos() <= 1) {
            previousGapByUid.put(tick.sessionUid(), -1f);
            return Optional.empty();
        }

        JsonNode ahead = EngineerMessageHelpers.findCarAtPosition(tick.cars(), tick.playerPos() - 1);
        if (ahead == null) {
            previousGapByUid.put(tick.sessionUid(), -1f);
            return Optional.empty();
        }

        float gap = EngineerMessageHelpers.gapToCarSeconds(ahead, tick.currentLap(), tick.playerLapDist(), tick.trackLength());
        if (gap < 0) return Optional.empty();

        Float previous = previousGapByUid.put(tick.sessionUid(), gap);
        boolean isInRange = gap < DRS_RANGE_SEC;
        boolean wasInRange = previous != null && previous >= 0 && previous < DRS_RANGE_SEC;
        if (!isInRange || wasInRange) return Optional.empty();

        return Optional.of(new EngineerMessage(
                Priority.HIGH, "You have DRS. Attack.",
                tick.wallClockMs(), tick.currentLap(), 1));
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        previousGapByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        previousGapByUid.remove(sessionUid);
    }
}
