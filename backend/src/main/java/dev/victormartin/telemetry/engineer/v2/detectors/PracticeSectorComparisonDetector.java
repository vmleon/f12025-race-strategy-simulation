package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * "X is Y seconds faster in Sector N" — fires after the player completes a
 * sector with a new personal best, comparing against the fastest AI sector.
 *
 * Replaces v1 detectPracticeSectorComparison. v1 used a 150ms deficit and
 * 3-lap cooldown — too coarse for a Free Practice driving session, so it
 * never fired in the captured run (Phase C bug 3.4).
 *
 * v2 retune:
 * - deficit threshold: 100ms (was 150)
 * - cooldown: 1 lap (was 3)
 */
public class PracticeSectorComparisonDetector implements RadioDetector {

    private static final int DEFICIT_MIN_MS = 100;
    private static final int COOLDOWN_LAPS = 1;
    private static final int MAX_CARS = 22;

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "PracticeSectorComparison"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.PRACTICE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());

        Optional<EngineerMessage> firedThisTick = Optional.empty();

        for (JsonNode car : tick.cars()) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx < 0 || idx >= MAX_CARS) continue;
            int sector = car.has("sector") ? car.get("sector").asInt() : 0;
            int prevSector = s.previousCarSectors[idx];
            s.previousCarSectors[idx] = sector;

            int completedSector = -1;
            long timeMs = 0;
            JsonNode sectorMs = car.get("lastSectorMs");
            if (sectorMs == null || !sectorMs.isArray() || sectorMs.size() < 2) continue;

            if (prevSector == 0 && sector == 1) {
                completedSector = 0;
                timeMs = sectorMs.get(0).asLong();
            } else if (prevSector == 1 && sector == 2) {
                completedSector = 1;
                timeMs = sectorMs.get(1).asLong();
            }
            if (completedSector < 0 || timeMs <= 0) continue;

            boolean isPlayer = car.has("ai") && !car.get("ai").asBoolean();
            if (isPlayer) {
                long playerBest = s.playerBestSectors[completedSector];
                if (playerBest != 0 && timeMs >= playerBest) continue; // no new best
                s.playerBestSectors[completedSector] = timeMs;
                if (firedThisTick.isPresent()) continue; // one msg per tick
                firedThisTick = compareAgainstAi(s, tick, completedSector, timeMs);
            } else {
                long[] bests = s.bestSectorTimesByCar.computeIfAbsent(idx, k -> new long[3]);
                if (bests[completedSector] == 0 || timeMs < bests[completedSector]) {
                    bests[completedSector] = timeMs;
                }
            }
        }

        return firedThisTick;
    }

    private Optional<EngineerMessage> compareAgainstAi(State s, EngineerTick tick,
                                                       int sector, long playerBestMs) {
        if (tick.currentLap() - s.lastFireLap[sector] < COOLDOWN_LAPS) return Optional.empty();

        long fastestAiMs = 0;
        int fastestAiIdx = -1;
        for (Map.Entry<Integer, long[]> e : s.bestSectorTimesByCar.entrySet()) {
            long ms = e.getValue()[sector];
            if (ms <= 0) continue;
            if (fastestAiMs == 0 || ms < fastestAiMs) {
                fastestAiMs = ms;
                fastestAiIdx = e.getKey();
            }
        }
        if (fastestAiIdx < 0 || fastestAiMs >= playerBestMs) return Optional.empty();

        long deltaMs = playerBestMs - fastestAiMs;
        if (deltaMs < DEFICIT_MIN_MS) return Optional.empty();

        String name = "Rival";
        for (JsonNode car : tick.cars()) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx == fastestAiIdx && car.has("name")) {
                name = car.get("name").asText();
                break;
            }
        }

        s.lastFireLap[sector] = tick.currentLap();
        return Optional.of(new EngineerMessage(
                Priority.NORMAL,
                name + " is " + formatTenths(deltaMs / 1000.0) + " seconds faster in Sector " + (sector + 1) + ".",
                tick.wallClockMs(), tick.currentLap(), 3));
    }

    private static String formatTenths(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.floor(rounded)) return String.format("%.0f", rounded);
        return String.format("%.1f", rounded);
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
        final int[] previousCarSectors = new int[MAX_CARS];
        final long[] playerBestSectors = new long[3];
        final Map<Integer, long[]> bestSectorTimesByCar = new HashMap<>();
        final int[] lastFireLap = new int[3];
    }
}
