package dev.victormartin.telemetry.engineer.detectors;

import java.util.HashMap;
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
 * Announces when the session-fastest lap is set. Two variants:
 *   - Player owns the new best:   "Fastest lap of the session, 1:23.4. Nice one."
 *   - Rival owns the new best:    "VERSTAPPEN sets the fastest lap, 1:23.4. You're 0.4 seconds off the pace."
 *
 * Fires when the session-best lap improves over the previously announced one.
 * The very first session-best is recorded silently to avoid an opening
 * "fastest lap" message on every car's first lap. A 20 s cooldown caps
 * repetition when several cars improve in quick succession (typical early
 * in qualifying).
 */
public class FastestLapByRivalDetector implements RadioDetector {

    private static final long COOLDOWN_MS = 20_000L;

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "FastestLapByRival"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() {
        return Set.of(SessionKind.PRACTICE, SessionKind.QUALIFYING,
                SessionKind.SPRINT_QUALIFYING, SessionKind.RACE, SessionKind.SPRINT_RACE);
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());

        // Player identity comes from the orchestrator (single source of truth).
        // Iterating tick.cars() looking for ai=false used to mis-identify
        // when the participants packet hadn't fully populated, falling through
        // with playerIdx=-1 and announcing the player's own fastest lap as if
        // it were a rival's.
        JsonNode playerCar = tick.playerCar();
        int playerIdx = playerCar != null && playerCar.has("idx")
                ? playerCar.get("idx").asInt() : -1;

        for (JsonNode car : tick.cars()) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx < 0) continue;
            long lapMs = car.has("lastLapTimeMs") ? car.get("lastLapTimeMs").asLong() : 0;
            if (lapMs <= 0) continue;
            Long prev = s.bestByCarIdx.get(idx);
            if (prev == null || lapMs < prev) s.bestByCarIdx.put(idx, lapMs);
        }

        int sessionBestIdx = -1;
        long sessionBest = Long.MAX_VALUE;
        for (var e : s.bestByCarIdx.entrySet()) {
            if (e.getValue() < sessionBest) {
                sessionBest = e.getValue();
                sessionBestIdx = e.getKey();
            }
        }
        if (sessionBestIdx < 0) return Optional.empty();

        // No improvement since the previous announcement.
        if (s.lastAnnouncedBest != 0 && sessionBest >= s.lastAnnouncedBest) return Optional.empty();

        // First-ever fastest — record silently so the opening lap doesn't fire.
        if (s.lastAnnouncedBest == 0) {
            s.lastAnnouncedBest = sessionBest;
            return Optional.empty();
        }

        long now = tick.wallClockMs();
        if (s.lastFiredAtMs > 0 && now - s.lastFiredAtMs < COOLDOWN_MS) return Optional.empty();

        String lapTime = EngineerMessageHelpers.formatLapTime(sessionBest);
        String text;
        if (sessionBestIdx == playerIdx) {
            text = "Fastest lap of the session, " + lapTime + ". Nice one.";
        } else {
            String setterName = "Car " + sessionBestIdx;
            for (JsonNode car : tick.cars()) {
                int idx = car.has("idx") ? car.get("idx").asInt() : -1;
                if (idx == sessionBestIdx && car.has("name")) {
                    setterName = car.get("name").asText();
                    break;
                }
            }
            Long playerBest = s.bestByCarIdx.get(playerIdx);
            if (playerBest != null && playerBest > 0) {
                long deltaMs = playerBest - sessionBest;
                String gap = EngineerMessageHelpers.formatTenths(deltaMs / 1000.0);
                text = setterName + " sets the fastest lap, " + lapTime + ". "
                        + "You're " + gap + " seconds off the pace.";
            } else {
                text = setterName + " sets the fastest lap, " + lapTime + ".";
            }
        }

        s.lastAnnouncedBest = sessionBest;
        s.lastFiredAtMs = now;

        return Optional.of(new EngineerMessage(
                Priority.NORMAL, text,
                now, tick.currentLap(), 2));
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
        final Map<Integer, Long> bestByCarIdx = new HashMap<>();
        long lastAnnouncedBest = 0;
        long lastFiredAtMs = 0;
    }
}
