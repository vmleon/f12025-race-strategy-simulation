package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.HashMap;
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
 * Announces when a rival sets a new session-fastest lap, with the gap to the
 * player's own best. Helps the driver benchmark their pace against the field
 * without having to scan the timing screen.
 *
 * Fires when the session-best lap improves AND the new setter is not the
 * player. The very first session-best is recorded silently to avoid an
 * opening "fastest lap" message on every car's first lap. A 20 s cooldown
 * caps repetition when several cars improve in quick succession (typical
 * early in qualifying).
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

        int playerIdx = -1;
        for (JsonNode car : tick.cars()) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx < 0) continue;
            long lapMs = car.has("lastLapTimeMs") ? car.get("lastLapTimeMs").asLong() : 0;
            boolean isAi = !car.has("ai") || car.get("ai").asBoolean();
            if (!isAi) playerIdx = idx;
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

        // If the player owns the new best, update baseline silently. Player's
        // own personal best is handled by PracticeLapCompleteDetector etc.
        if (sessionBestIdx == playerIdx) {
            s.lastAnnouncedBest = sessionBest;
            return Optional.empty();
        }

        long now = tick.wallClockMs();
        if (s.lastFiredAtMs > 0 && now - s.lastFiredAtMs < COOLDOWN_MS) return Optional.empty();

        String setterName = "Car " + sessionBestIdx;
        for (JsonNode car : tick.cars()) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx == sessionBestIdx && car.has("name")) {
                setterName = car.get("name").asText();
                break;
            }
        }

        Long playerBest = s.bestByCarIdx.get(playerIdx);
        String lapTime = EngineerMessageHelpers.formatLapTime(sessionBest);
        String text;
        if (playerBest != null && playerBest > 0) {
            long deltaMs = playerBest - sessionBest;
            String gap = EngineerMessageHelpers.formatTenths(deltaMs / 1000.0);
            text = setterName + " sets the fastest lap, " + lapTime + ". "
                    + "You're " + gap + " seconds off the pace.";
        } else {
            text = setterName + " sets the fastest lap, " + lapTime + ".";
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
