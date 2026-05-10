package dev.victormartin.telemetry.simulation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Per-car rolling buffer of the last {@link #WINDOW} valid lap times. Powers
 * the simulator's per-car pace estimate (median of the buffer) under the
 * "use observed lap times instead of fitted base_pace" model — see Option C
 * in the strategy/calibration redesign.
 *
 * Updated centrally from {@code TelemetryTcpServer} once per state tick. The
 * tracker filters duplicate ticks (same lap, same time) and keeps only valid
 * lap times (positive, populated). Reset on session end.
 */
@Component
public class LapHistoryTracker {

    private static final int WINDOW = 3;

    private final Map<Integer, Deque<Long>> recentByCarIdx = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastSeenTimeByCarIdx = new ConcurrentHashMap<>();

    /** Returns up to {@link #WINDOW} most recent valid lap times for a car, newest first. */
    public List<Long> recent(int carIdx) {
        Deque<Long> buf = recentByCarIdx.get(carIdx);
        if (buf == null) return List.of();
        return new ArrayList<>(buf);
    }

    /** Inspect every car in the state JSON; record any newly completed lap times. */
    public synchronized void onStateUpdate(JsonNode state) {
        JsonNode cars = state == null ? null : state.get("cars");
        if (cars == null || !cars.isArray()) return;
        for (JsonNode car : cars) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx < 0) continue;
            long lastLap = car.has("lastLapTimeMs") ? car.get("lastLapTimeMs").asLong() : 0L;
            if (lastLap <= 0) continue;
            Long prev = lastSeenTimeByCarIdx.get(idx);
            if (prev != null && prev == lastLap) continue;
            lastSeenTimeByCarIdx.put(idx, lastLap);
            Deque<Long> buf = recentByCarIdx.computeIfAbsent(idx, k -> new ArrayDeque<>());
            buf.addFirst(lastLap);
            while (buf.size() > WINDOW) buf.removeLast();
        }
    }

    /** Drop all per-car state. Called when a session ends so the next session starts clean. */
    public synchronized void reset() {
        recentByCarIdx.clear();
        lastSeenTimeByCarIdx.clear();
    }
}
