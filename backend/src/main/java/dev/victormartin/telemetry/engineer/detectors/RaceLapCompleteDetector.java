package dev.victormartin.telemetry.engineer.detectors;

import java.util.ArrayList;
import java.util.Comparator;
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
 * Per-lap race status. Fires once when the player rolls into a new lap on
 * track, summarising position, laps remaining, gap to the car ahead, and gap
 * to the leader. This was the gap the user flagged in the lap-start radio
 * coverage — practice/quali already had a per-lap announcement; race didn't.
 *
 * Message variants:
 *   Leader:   "Lap 8 done. Leading the race, 9 laps to go. 0.6 seconds clear of VERSTAPPEN."
 *   Midfield: "Lap 8 done. P3, 9 laps to go. 0.6 back on HAMILTON. 2.4 from the lead."
 *
 * The detector is gated to RACE / SPRINT_RACE and to {@link PitState#ON_TRACK}
 * so it doesn't fire while the player is stationary in the box.
 */
public class RaceLapCompleteDetector implements RadioDetector {

    private final Map<String, Integer> lastFiredLapByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "RaceLapComplete"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() {
        return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE);
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        int lap = tick.currentLap();
        if (lap < 2) return Optional.empty();
        Integer last = lastFiredLapByUid.get(tick.sessionUid());
        if (last != null && lap == last) return Optional.empty();
        if (last != null && lap < last) {
            // Lap counter regressed (session reset, restart). Re-arm cleanly.
            lastFiredLapByUid.remove(tick.sessionUid());
            return Optional.empty();
        }

        int playerPos = tick.playerPos();
        int totalLaps = tick.totalLaps();
        int remaining = Math.max(totalLaps - lap + 1, 0);

        // Sort cars by position so we can walk from leader to player.
        List<JsonNode> byPos = new ArrayList<>();
        for (JsonNode car : tick.cars()) {
            int rs = car.has("resultStatus") ? car.get("resultStatus").asInt() : 2;
            if (rs <= 1 || rs >= 4) continue; // inactive or retired
            byPos.add(car);
        }
        byPos.sort(Comparator.comparingInt(c -> c.has("pos") ? c.get("pos").asInt() : 99));

        String aheadName = null;
        long gapAheadMs = -1;
        long gapToLeaderMs = 0;
        boolean leaderGapKnown = true;
        for (JsonNode car : byPos) {
            int pos = car.has("pos") ? car.get("pos").asInt() : 0;
            if (pos <= 0 || pos > playerPos) break;
            long deltaMs = car.has("deltaToFrontMs") ? car.get("deltaToFrontMs").asLong() : 0;
            if (pos == 1) {
                // Leader has no car in front; deltaToFrontMs typically 0.
                continue;
            }
            if (deltaMs <= 0) leaderGapKnown = false;
            gapToLeaderMs += Math.max(deltaMs, 0);
            if (pos == playerPos) {
                gapAheadMs = deltaMs;
                JsonNode ahead = findCarAtPosition(byPos, playerPos - 1);
                if (ahead != null && ahead.has("name")) aheadName = ahead.get("name").asText();
            }
        }

        StringBuilder text = new StringBuilder("Lap ").append(lap - 1).append(" done. ");
        if (playerPos == 1) {
            text.append("Leading the race");
        } else {
            text.append("P").append(playerPos);
        }
        if (remaining > 0) {
            if (remaining == 1) text.append(", last lap");
            else text.append(", ").append(remaining).append(" laps to go");
        }
        text.append(".");

        if (playerPos == 1) {
            // For the leader, report gap to P2 instead of "gap ahead".
            JsonNode p2 = findCarAtPosition(byPos, 2);
            if (p2 != null) {
                long p2Delta = p2.has("deltaToFrontMs") ? p2.get("deltaToFrontMs").asLong() : 0;
                if (p2Delta > 0) {
                    String name = p2.has("name") ? p2.get("name").asText() : "the chasing car";
                    text.append(" ")
                        .append(EngineerMessageHelpers.formatSecondsPhrase(p2Delta / 1000.0))
                        .append(" clear of ").append(name).append(".");
                }
            }
        } else {
            if (gapAheadMs > 0 && aheadName != null) {
                text.append(" ")
                    .append(EngineerMessageHelpers.formatSecondsPhrase(gapAheadMs / 1000.0))
                    .append(" back on ").append(aheadName).append(".");
            }
            if (playerPos > 2 && leaderGapKnown && gapToLeaderMs > 0) {
                text.append(" ")
                    .append(EngineerMessageHelpers.formatSecondsPhrase(gapToLeaderMs / 1000.0))
                    .append(" from the lead.");
            }
        }

        lastFiredLapByUid.put(tick.sessionUid(), lap);
        return Optional.of(new EngineerMessage(
                Priority.NORMAL, text.toString(),
                tick.wallClockMs(), lap, 2));
    }

    private static JsonNode findCarAtPosition(List<JsonNode> sortedByPos, int position) {
        for (JsonNode car : sortedByPos) {
            int pos = car.has("pos") ? car.get("pos").asInt() : 0;
            if (pos == position) return car;
        }
        return null;
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        lastFiredLapByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        lastFiredLapByUid.remove(sessionUid);
    }
}
