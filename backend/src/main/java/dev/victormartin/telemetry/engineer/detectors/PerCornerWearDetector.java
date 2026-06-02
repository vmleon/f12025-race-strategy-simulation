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
 * Per-corner tyre wear thresholds (24% / 37%). Wheel order from telemetry is
 * [RL, RR, FL, FR].
 *
 * Returns at most one message per tick
 * (first corner that crossed a new threshold wins).
 */
public class PerCornerWearDetector implements RadioDetector {

    private static final String[] CORNER_NAMES = {"Rear-left", "Rear-right", "Front-left", "Front-right"};

    private final Map<String, int[]> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "PerCornerWear"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        int[] alerts = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new int[4]);
        JsonNode wear = tick.playerCar().get("tyreWear");
        if (wear == null || !wear.isArray() || wear.size() < 4) return Optional.empty();

        // Reset thresholds when tyres are changed (any corner dropped substantially).
        boolean tyresChanged = false;
        for (int i = 0; i < 4; i++) {
            int w = (int) wear.get(i).asDouble();
            if (alerts[i] > 0 && w < alerts[i] - 5) { tyresChanged = true; break; }
        }
        if (tyresChanged) {
            for (int i = 0; i < 4; i++) alerts[i] = 0;
        }

        for (int i = 0; i < 4; i++) {
            int w = (int) wear.get(i).asDouble();
            if (w >= 37 && alerts[i] < 37) {
                alerts[i] = 37;
                return Optional.of(new EngineerMessage(
                        Priority.HIGH,
                        CORNER_NAMES[i] + " is finished, manage it.",
                        tick.wallClockMs(), tick.currentLap(), 2));
            }
            if (w >= 24 && alerts[i] < 24) {
                alerts[i] = 24;
                return Optional.of(new EngineerMessage(
                        Priority.NORMAL,
                        CORNER_NAMES[i] + " starting to degrade.",
                        tick.wallClockMs(), tick.currentLap(), 3));
            }
        }
        return Optional.empty();
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        stateByUid.put(sessionUid, new int[4]);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        stateByUid.remove(sessionUid);
    }
}
