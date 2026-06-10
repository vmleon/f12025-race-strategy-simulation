package dev.victormartin.telemetry.engineer.detectors;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Shared pace-ranking for the practice detectors. Cars only report
 * {@code lastLapTimeMs}, so callers fold each tick's last laps into a running
 * per-car session-best map ({@link #updateBests}) and read the player's rank off
 * it ({@link #rank}). Both {@code PracticeLapCompleteDetector} and
 * {@code PracticeTyreFuelSummaryDetector} use one implementation this way.
 */
public final class PaceRanking {

    private PaceRanking() {}

    /**
     * Fold each car's last lap into the running session-best map (keeping the minimum
     * per car index). Returns the player car index (ai == false), or -1 if absent.
     */
    public static int updateBests(Map<Integer, Long> bestByCarIdx, JsonNode cars) {
        int playerIdx = -1;
        for (JsonNode car : cars) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx < 0) continue;
            long ms = car.has("lastLapTimeMs") ? car.get("lastLapTimeMs").asLong() : 0;
            if (ms > 0) {
                Long prev = bestByCarIdx.get(idx);
                if (prev == null || ms < prev) bestByCarIdx.put(idx, ms);
            }
            if (car.has("ai") && !car.get("ai").asBoolean()) playerIdx = idx;
        }
        return playerIdx;
    }

    /** Player's 1-based pace rank by session-best lap, or 0 when it can't be determined. */
    public static int rank(Map<Integer, Long> bestByCarIdx, int playerIdx) {
        Long playerBest = bestByCarIdx.get(playerIdx);
        if (playerIdx < 0 || playerBest == null || playerBest <= 0) return 0;
        int rank = 1;
        for (Long best : bestByCarIdx.values()) {
            if (best > 0 && best < playerBest) rank++;
        }
        return rank;
    }
}
