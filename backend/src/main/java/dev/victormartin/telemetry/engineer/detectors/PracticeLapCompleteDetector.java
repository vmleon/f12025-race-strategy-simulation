package dev.victormartin.telemetry.engineer.detectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
 * Per-lap pace announcement during free practice. On each completed lap,
 * reports the lap time, delta to personal best, and the player's position on
 * the time sheet with a gap to the nearest reference (P1 if leading, leader
 * otherwise).
 */
public class PracticeLapCompleteDetector implements RadioDetector {

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "PracticeLapComplete"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.PRACTICE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());

        // Track every car's session-best lap (player + AI). Used to rank the
        // player on the time sheet when reporting their own completed lap.
        int playerIdx = -1;
        for (JsonNode car : tick.cars()) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx < 0) continue;
            long ms = car.has("lastLapTimeMs") ? car.get("lastLapTimeMs").asLong() : 0;
            if (ms > 0) {
                Long prev = s.bestByCarIdx.get(idx);
                if (prev == null || ms < prev) s.bestByCarIdx.put(idx, ms);
            }
            if (car.has("ai") && !car.get("ai").asBoolean()) playerIdx = idx;
        }

        int lap = tick.currentLap();
        if (lap <= s.lastFiredLap) return Optional.empty();

        long lapMs = tick.playerCar().has("lastLapTimeMs")
                ? tick.playerCar().get("lastLapTimeMs").asLong() : 0;
        if (lapMs <= 0) return Optional.empty();
        if (lapMs == s.lastReportedLapMs) return Optional.empty();

        s.lastFiredLap = lap;
        s.lastReportedLapMs = lapMs;

        boolean personalBest = (s.bestLapMs == 0 || lapMs < s.bestLapMs);
        if (personalBest) s.bestLapMs = lapMs;

        StringBuilder text = new StringBuilder(EngineerMessageHelpers.formatLapTime(lapMs));
        if (personalBest) {
            text.append(". Personal best.");
        } else {
            long delta = lapMs - s.bestLapMs;
            text.append(", ")
                .append(EngineerMessageHelpers.formatTenths(delta / 1000.0))
                .append(" seconds off your best.");
        }
        String positionInfo = formatPositionInfo(s.bestByCarIdx, playerIdx, s.bestLapMs, tick.cars());
        if (positionInfo != null) {
            text.append(' ').append(positionInfo);
        }
        int timeLeft = tick.state().has("sessionTimeLeft")
                ? tick.state().get("sessionTimeLeft").asInt() : 0;
        // The F1 clock stops counting down once the session timer hits zero and the
        // reported sessionTimeLeft is no longer monotonic (it can reset back up).
        // Latch on the first non-decreasing reading so we never announce a fresh
        // "X minutes left" on the chequered lap.
        if (timeLeft > 0 && s.lastSessionTimeLeft >= 0 && timeLeft >= s.lastSessionTimeLeft) {
            s.sessionClockExpired = true;
        }
        if (timeLeft > 0) s.lastSessionTimeLeft = timeLeft;
        if (!s.sessionClockExpired) {
            String timeLeftStr = EngineerMessageHelpers.formatSessionTimeLeft(timeLeft);
            if (!timeLeftStr.isEmpty()) {
                text.append(' ').append(EngineerMessageHelpers.capitalize(timeLeftStr)).append('.');
            }
        }

        return Optional.of(new EngineerMessage(
                Priority.NORMAL, text.toString(),
                tick.wallClockMs(), lap, 2));
    }

    /** "P1 on the time sheet, 0.5 seconds clear of Verstappen." or null if no peers yet. */
    private static String formatPositionInfo(Map<Integer, Long> bestByCarIdx, int playerIdx,
                                              long playerBestMs, JsonNode cars) {
        if (playerIdx < 0 || playerBestMs <= 0) return null;

        List<Map.Entry<Integer, Long>> ranked = new ArrayList<>(bestByCarIdx.entrySet());
        ranked.sort(Comparator.comparingLong(Map.Entry::getValue));

        int playerRank = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getKey() == playerIdx) { playerRank = i; break; }
        }
        if (playerRank < 0) return null;

        if (ranked.size() < 2) return "P1 on the time sheet.";

        if (playerRank == 0) {
            Map.Entry<Integer, Long> next = ranked.get(1);
            long gapMs = next.getValue() - playerBestMs;
            return "P1 on the time sheet, " + EngineerMessageHelpers.formatTenths(gapMs / 1000.0)
                    + " seconds clear of " + lookupName(cars, next.getKey()) + ".";
        }

        Map.Entry<Integer, Long> ahead = ranked.get(playerRank - 1);
        long gapMs = playerBestMs - ahead.getValue();
        return "P" + (playerRank + 1) + " on the time sheet, "
                + EngineerMessageHelpers.formatTenths(gapMs / 1000.0)
                + " seconds behind " + lookupName(cars, ahead.getKey()) + ".";
    }

    private static String lookupName(JsonNode cars, int idx) {
        for (JsonNode car : cars) {
            int i = car.has("idx") ? car.get("idx").asInt() : -1;
            if (i == idx && car.has("name")) return car.get("name").asText();
        }
        return "the car ahead";
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
        int lastFiredLap = 0;
        long lastReportedLapMs = 0;
        long bestLapMs = 0;
        int lastSessionTimeLeft = -1;
        boolean sessionClockExpired = false;
        final Map<Integer, Long> bestByCarIdx = new HashMap<>();
    }
}
