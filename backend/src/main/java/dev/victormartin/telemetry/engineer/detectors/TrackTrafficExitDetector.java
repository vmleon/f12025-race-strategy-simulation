package dev.victormartin.telemetry.engineer.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.RadioDetector;
import dev.victormartin.telemetry.engineer.SessionKind;

/**
 * "Track is clear, go now" / "Hold position, X about to pass" — issued once per
 * pit visit when the player crosses the pit-exit line.
 *
 * It declares {@code appliesToStates = {PIT_EXIT}}, so the orchestrator only
 * invokes it in PIT_EXIT — not on pit ENTRY (the moment the player crosses
 * into the pit lane). Phase C bugs 3.1, 3.3.
 */
public class TrackTrafficExitDetector implements RadioDetector {

    private static final float CLEAR_GAP_SEC = 15f;
    private static final float HOLD_GAP_SEC = 8f;
    /** Rough average lap-pace conversion: 200 km/h ≈ 55 m/s. */
    private static final float METRES_PER_SECOND = 55f;

    private final Map<String, Boolean> sentThisStint = new ConcurrentHashMap<>();

    @Override
    public String name() { return "TrackTrafficExit"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.PIT_EXIT); }

    @Override
    public Set<SessionKind> appliesToSessions() {
        return Set.of(SessionKind.PRACTICE, SessionKind.QUALIFYING,
                SessionKind.SPRINT_QUALIFYING);
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        // Reset the "already sent" flag whenever the player has just re-entered
        // PIT_EXIT from PIT_STOPPED — a new outing is a new chance to fire.
        if (tick.previousPitState() == PitState.PIT_STOPPED) {
            sentThisStint.put(tick.sessionUid(), false);
        }
        if (Boolean.TRUE.equals(sentThisStint.get(tick.sessionUid()))) {
            return Optional.empty();
        }

        float playerDist = tick.playerLapDist();
        float nearestSec = Float.MAX_VALUE;
        String nearestName = null;
        for (JsonNode car : tick.cars()) {
            boolean isAi = car.has("ai") && car.get("ai").asBoolean();
            if (!isAi) continue;
            int otherPit = car.has("pitStatus") ? car.get("pitStatus").asInt() : 0;
            if (otherPit > 0) continue;

            float otherDist = car.has("lapDist") ? (float) car.get("lapDist").asDouble() : 0f;
            float gap = playerDist - otherDist;
            if (gap < 0) gap += tick.trackLength();
            float seconds = gap / METRES_PER_SECOND;
            if (seconds < nearestSec) {
                nearestSec = seconds;
                nearestName = car.has("name") ? car.get("name").asText() : "a car";
            }
        }

        if (nearestName == null) return Optional.empty();

        String text;
        if (nearestSec > CLEAR_GAP_SEC) {
            text = "Track is clear, go now.";
        } else if (nearestSec < HOLD_GAP_SEC) {
            text = "Hold position, " + nearestName + " about to pass.";
        } else {
            return Optional.empty();
        }

        sentThisStint.put(tick.sessionUid(), true);
        return Optional.of(new EngineerMessage(
                Priority.HIGH, text,
                tick.wallClockMs(), tick.currentLap(), 2));
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        sentThisStint.put(sessionUid, false);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        sentThisStint.remove(sessionUid);
    }
}
