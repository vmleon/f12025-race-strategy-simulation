package dev.victormartin.telemetry.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LapHistoryTrackerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Compound codes
    private static final int SOFT = 16;
    private static final int MEDIUM = 17;

    private LapHistoryTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new LapHistoryTracker();
    }

    private ObjectNode car(int idx, String tyre, long lastLapMs) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("idx", idx);
        n.put("tyre", tyre);
        n.put("lastLapTimeMs", lastLapMs);
        return n;
    }

    private void push(int idx, String tyre, long lastLapMs) {
        ObjectNode state = MAPPER.createObjectNode();
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(idx, tyre, lastLapMs));
        state.set("cars", cars);
        tracker.onStateUpdate(state);
    }

    @Test
    void recordsFastLapsAndExposesThemByCompound() {
        push(0, "S", 80_000);
        push(0, "S", 81_000);
        List<Long> recent = tracker.recentForCompound(0, SOFT);
        assertEquals(List.of(81_000L, 80_000L), recent);
    }

    @Test
    void compoundSegmentation() {
        push(0, "S", 80_000);
        push(0, "M", 83_000);
        assertEquals(List.of(80_000L), tracker.recentForCompound(0, SOFT));
        assertEquals(List.of(83_000L), tracker.recentForCompound(0, MEDIUM));
    }

    @Test
    void rejectsLapMoreThan10PercentSlowerThanBufferBest() {
        // Two clean hot laps establish the floor at 80 s.
        push(0, "S", 80_000);
        push(0, "S", 80_500);
        // An outlap at 95 s is >10 % slower than 80 s → rejected.
        push(0, "S", 95_000);
        List<Long> recent = tracker.recentForCompound(0, SOFT);
        assertEquals(2, recent.size());
        assertTrue(recent.stream().allMatch(t -> t <= 85_000));
    }

    @Test
    void acceptsLapWithinTenPercentEvenIfSlower() {
        // Within-threshold slow laps (e.g. tyre wear) are kept.
        push(0, "S", 80_000);
        push(0, "S", 82_000);
        push(0, "S", 87_000); // 8.75 % slower — accepted
        List<Long> recent = tracker.recentForCompound(0, SOFT);
        assertEquals(3, recent.size());
    }

    @Test
    void retroactivelyPrunesOldOutlapWhenFasterLapArrives() {
        // Opening lap is an outlap (slow). Buffer has only that lap, so it's
        // accepted as the initial baseline.
        push(0, "S", 100_000);
        assertEquals(List.of(100_000L), tracker.recentForCompound(0, SOFT));

        // A real hot lap arrives. Outlap is now revealed as an outlier and pruned.
        push(0, "S", 80_000);
        List<Long> recent = tracker.recentForCompound(0, SOFT);
        assertEquals(List.of(80_000L), recent);
    }

    @Test
    void sessionTransitionDropsClearOutliersWhenTrackUnchanged() {
        // Two outlaps, then a hot lap. Mid-session prune-on-insert should drop
        // most of them already, but verify session-boundary cleanup is idempotent.
        push(0, "S", 100_000);
        push(0, "S", 99_000);
        push(0, "S", 80_000); // prune-on-insert evicts the 100k and 99k

        tracker.onSessionStarted(7);            // first session boundary (was -1)
        tracker.onSessionStarted(7);            // same track => prune pass
        List<Long> recent = tracker.recentForCompound(0, SOFT);
        assertTrue(recent.stream().allMatch(t -> t <= 88_000),
                "no laps above 10 % over min should survive: " + recent);
    }

    @Test
    void differentTrackClearsBuffer() {
        push(0, "S", 80_000);
        tracker.onSessionStarted(7);     // first known track
        tracker.onSessionStarted(12);    // new circuit ⇒ wipe
        assertTrue(tracker.recentForCompound(0, SOFT).isEmpty());
    }

    @Test
    void duplicateLapsAreIgnored() {
        push(0, "S", 80_000);
        push(0, "S", 80_000); // same lastLapTimeMs ⇒ not re-pushed
        assertEquals(List.of(80_000L), tracker.recentForCompound(0, SOFT));
    }
}
