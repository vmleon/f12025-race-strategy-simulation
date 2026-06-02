package dev.victormartin.telemetry.engineer.detectors;

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
 * "Provisional pole. 1:23.4." / "P3, 0.4 seconds off pole. 1:23.8."
 */
public class QualifyingLapCompleteDetector implements RadioDetector {

    private final Map<String, Integer> lastLapByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "QualifyingLapComplete"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.QUALIFYING, SessionKind.SPRINT_QUALIFYING); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        Integer last = lastLapByUid.get(tick.sessionUid());
        if (last != null && tick.currentLap() <= last) return Optional.empty();
        lastLapByUid.put(tick.sessionUid(), tick.currentLap());

        long playerLapMs = tick.playerCar().has("lastLapTimeMs") ? tick.playerCar().get("lastLapTimeMs").asLong() : 0;
        if (playerLapMs <= 0) return Optional.empty();

        long bestLapMs = Long.MAX_VALUE;
        for (JsonNode car : tick.cars()) {
            long ms = car.has("lastLapTimeMs") ? car.get("lastLapTimeMs").asLong() : 0;
            if (ms > 0 && ms < bestLapMs) bestLapMs = ms;
        }

        String text;
        if (bestLapMs == Long.MAX_VALUE || playerLapMs <= bestLapMs) {
            text = "Provisional pole. " + EngineerMessageHelpers.formatLapTime(playerLapMs) + ".";
        } else {
            long delta = playerLapMs - bestLapMs;
            text = "P" + tick.playerPos() + ", " + EngineerMessageHelpers.formatTenths(delta / 1000.0)
                    + " seconds off pole. " + EngineerMessageHelpers.formatLapTime(playerLapMs) + ".";
        }
        int timeLeft = tick.state().has("sessionTimeLeft")
                ? tick.state().get("sessionTimeLeft").asInt() : 0;
        String timeLeftStr = EngineerMessageHelpers.formatSessionTimeLeft(timeLeft);
        if (!timeLeftStr.isEmpty()) {
            text += " " + EngineerMessageHelpers.capitalize(timeLeftStr) + ".";
        }
        return Optional.of(new EngineerMessage(
                Priority.NORMAL, text,
                tick.wallClockMs(), tick.currentLap(), 2));
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        lastLapByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        lastLapByUid.remove(sessionUid);
    }
}
