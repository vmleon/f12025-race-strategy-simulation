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
 * Announces when the player's current lap is invalidated, once per invalidation
 * (the 0 -> 1 transition of {@code lapInvalid}). IMMEDIATE priority so the driver
 * always hears it — it bypasses the queue's per-zone NORMAL budget / safe-zone
 * throttling. Corner number is NOT available in the F1 UDP feed, so we announce the
 * track-limits warning count instead of a turn number.
 */
public class InvalidLapDetector implements RadioDetector {

    private final Map<String, Integer> lastInvalidByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "InvalidLap"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() {
        return Set.of(SessionKind.PRACTICE, SessionKind.QUALIFYING,
                SessionKind.SPRINT_QUALIFYING, SessionKind.RACE, SessionKind.SPRINT_RACE);
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        JsonNode player = tick.playerCar();
        if (player == null) return Optional.empty();

        int invalid = player.has("lapInvalid") ? player.get("lapInvalid").asInt() : 0;
        int prev = lastInvalidByUid.getOrDefault(tick.sessionUid(), 0);
        lastInvalidByUid.put(tick.sessionUid(), invalid);

        // Fire once on the 0 -> 1 transition (a fresh invalidation), not every tick.
        if (invalid == 0 || prev != 0) return Optional.empty();

        int warnings = player.has("cornerCutting") ? player.get("cornerCutting").asInt() : 0;
        String text = warnings > 0
                ? "That lap's deleted — track limits. That's warning number " + warnings + "."
                : "That lap's deleted — track limits.";
        return Optional.of(new EngineerMessage(
                Priority.IMMEDIATE, text, tick.wallClockMs(), tick.currentLap(), 1));
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        lastInvalidByUid.put(sessionUid, 0);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        lastInvalidByUid.remove(sessionUid);
    }
}
