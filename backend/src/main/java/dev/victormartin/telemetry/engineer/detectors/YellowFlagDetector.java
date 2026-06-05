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
 * "Yellow flag in sector N." Yellows are per marshal zone (no global field), so
 * telemetry forwards the set of sectors with a yellow zone in {@code yellowSectors}.
 * We only warn about the player's current sector or the one ahead — a yellow far
 * behind is not actionable — and announce each episode once until it clears.
 */
public class YellowFlagDetector implements RadioDetector {

    private final Map<String, boolean[]> announcedByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "YellowFlag"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        boolean[] yellow = readYellowSectors(tick.state());
        boolean[] announced = announcedByUid.computeIfAbsent(tick.sessionUid(), k -> new boolean[3]);

        // Reset the announced latch for any sector that is no longer yellow.
        for (int s = 0; s < 3; s++) {
            if (!yellow[s]) announced[s] = false;
        }

        int playerSector = readPlayerSector(tick.playerCar());
        // Current sector first, then the one ahead.
        int[] relevant = {playerSector, (playerSector + 1) % 3};
        for (int s : relevant) {
            if (yellow[s] && !announced[s]) {
                announced[s] = true;
                return Optional.of(new EngineerMessage(
                        Priority.HIGH,
                        "Yellow flag in sector " + (s + 1) + ".",
                        tick.wallClockMs(), tick.currentLap(), 2));
            }
        }
        return Optional.empty();
    }

    private static boolean[] readYellowSectors(JsonNode state) {
        boolean[] yellow = new boolean[3];
        JsonNode arr = state != null ? state.get("yellowSectors") : null;
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                int s = n.asInt(-1);
                if (s >= 0 && s < 3) yellow[s] = true;
            }
        }
        return yellow;
    }

    private static int readPlayerSector(JsonNode playerCar) {
        if (playerCar == null || !playerCar.has("sector")) return 0;
        int s = playerCar.get("sector").asInt(0);
        return (s < 0 || s > 2) ? 0 : s;
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        announcedByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        announcedByUid.remove(sessionUid);
    }
}
