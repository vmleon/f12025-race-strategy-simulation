package dev.victormartin.telemetry.simulation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Per-car rolling buffer of the last {@link #WINDOW} valid lap times, keyed by
 * compound. Powers the simulator's per-car pace estimate (median of the buffer
 * for the car's current compound) under the Option-C model.
 *
 * Data carries across sessions of the same race weekend — Practice and
 * Qualifying laps feed into Race projections. The buffer is wiped only when
 * the {@code trackId} changes (different circuit ⇒ different weekend).
 *
 * Per-compound segmentation lets a Soft → Hard pit stop project a realistic
 * Hard-tyre pace instead of falling back to the Soft pace (or the 90 s
 * default). When the car's current compound has no observed laps yet, the
 * tracker falls back to laps on any compound to keep something on the board.
 */
@Component
public class LapHistoryTracker {

    private static final int WINDOW = 3;

    /**
     * Asymmetric outlier filter. Any lap slower than the current buffer min by
     * more than this factor is rejected (outlaps, off-tracks, incidents).
     * Faster laps are always accepted — the upper bound on "good lap pace"
     * comes from the fastest observed time, not a symmetric distribution.
     *
     * 1.10 = 10 % slower than best. Typical F1 in-stint variation is 1–2 %;
     * outlaps and incidents are 5–15 % slower.
     */
    private static final double OUTLIER_FACTOR = 1.10;

    /** Key is (carIdx << 8) | compound — compound codes fit in a byte (game uses 7,8,16,17,18). */
    private final Map<Integer, Deque<Long>> byCarCompound = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastSeenLapMsByCarIdx = new ConcurrentHashMap<>();

    private int currentTrackId = -1;

    /**
     * Most recent valid laps for a car on the given compound (newest first).
     * Falls back to all-compound laps for the car when the compound-specific
     * buffer is empty, so newly-fitted compounds without observed pace still
     * get a sensible (if generic) seed.
     */
    public List<Long> recentForCompound(int carIdx, int compound) {
        Deque<Long> specific = byCarCompound.get(key(carIdx, compound));
        if (specific != null && !specific.isEmpty()) return new ArrayList<>(specific);
        List<Long> all = new ArrayList<>();
        for (Map.Entry<Integer, Deque<Long>> e : byCarCompound.entrySet()) {
            if ((e.getKey() >>> 8) == carIdx) all.addAll(e.getValue());
        }
        return all;
    }

    /** Player-pace-gate helper — does this car have enough observed laps to inform strategy? */
    public int totalLapsRecorded(int carIdx) {
        int total = 0;
        for (Map.Entry<Integer, Deque<Long>> e : byCarCompound.entrySet()) {
            if ((e.getKey() >>> 8) == carIdx) total += e.getValue().size();
        }
        return total;
    }

    /** Inspect every car in the state JSON; record any newly completed lap times keyed by compound. */
    public synchronized void onStateUpdate(JsonNode state) {
        JsonNode cars = state == null ? null : state.get("cars");
        if (cars == null || !cars.isArray()) return;
        for (JsonNode car : cars) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx < 0) continue;
            long lastLap = car.has("lastLapTimeMs") ? car.get("lastLapTimeMs").asLong() : 0L;
            if (lastLap <= 0) continue;
            int compound = compoundOf(car);
            if (compound == 0) continue;

            Long prev = lastSeenLapMsByCarIdx.get(idx);
            if (prev != null && prev == lastLap) continue;
            lastSeenLapMsByCarIdx.put(idx, lastLap);

            Deque<Long> buf = byCarCompound.computeIfAbsent(key(idx, compound), k -> new ArrayDeque<>());

            // Asymmetric outlier filter: reject this lap if it's >10 % slower
            // than the buffer's current best. Catches outlaps and incidents
            // without dropping legitimate slow laps from late-stint tyre wear.
            if (isOutlier(buf, lastLap)) continue;

            buf.addFirst(lastLap);
            while (buf.size() > WINDOW) buf.removeLast();

            // A new fast lap may expose older entries as outliers retroactively
            // (e.g. an opening outlap that defined the initial min is now
            // dominated by a hot lap). Re-evaluate after each insert.
            pruneOutliers(buf);
        }
    }

    /**
     * Notify the tracker that a new session has started. Clears all per-car
     * state only when the track has changed — otherwise carries over and
     * prunes any slow outliers that survived from the prior session before
     * race-day strategy starts consuming them.
     */
    public synchronized void onSessionStarted(int trackId) {
        if (currentTrackId >= 0 && currentTrackId != trackId) {
            byCarCompound.clear();
            lastSeenLapMsByCarIdx.clear();
        } else if (currentTrackId == trackId) {
            for (Deque<Long> buf : byCarCompound.values()) pruneOutliers(buf);
        }
        currentTrackId = trackId;
    }

    /** Full reset — used by tests and by the cross-weekend boundary path. */
    public synchronized void reset() {
        byCarCompound.clear();
        lastSeenLapMsByCarIdx.clear();
        currentTrackId = -1;
    }

    private static boolean isOutlier(Deque<Long> buf, long lapMs) {
        if (buf.isEmpty()) return false;
        long best = Long.MAX_VALUE;
        for (long t : buf) if (t < best) best = t;
        return lapMs > (long) (best * OUTLIER_FACTOR);
    }

    private static void pruneOutliers(Deque<Long> buf) {
        if (buf.size() < 2) return;
        long best = Long.MAX_VALUE;
        for (long t : buf) if (t < best) best = t;
        long threshold = (long) (best * OUTLIER_FACTOR);
        buf.removeIf(t -> t > threshold);
    }

    private static int compoundOf(JsonNode car) {
        String tyre = car.has("tyre") ? car.get("tyre").asText() : "";
        return switch (tyre) {
            case "S" -> 16;
            case "M" -> 17;
            case "H" -> 18;
            case "I" -> 7;
            case "W" -> 8;
            default -> 0;
        };
    }

    private static int key(int carIdx, int compound) {
        return (carIdx << 8) | (compound & 0xFF);
    }
}
