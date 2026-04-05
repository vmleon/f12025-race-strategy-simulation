package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.simulation.RaceSnapshot;
import dev.victormartin.telemetry.simulation.StrategyEvaluation;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServicePitWindowTest {

    private List<String> broadcasts;
    private RaceEngineerService service;

    @BeforeEach
    void setUp() {
        CircuitSafeZoneService safeZoneService = new CircuitSafeZoneService();
        safeZoneService.loadCircuits();
        broadcasts = new ArrayList<>();
        RaceEngineerWebSocketHandler handler = new RaceEngineerWebSocketHandler() {
            @Override
            public void broadcast(String jsonLine) {
                broadcasts.add(jsonLine);
            }
        };
        service = new RaceEngineerService(safeZoneService, handler);
        service.onSessionStarted("test-session", 0, 10, 1, 1);
    }

    private String stateJson(int lap, float lapDist) {
        // Compound 17=M (Medium)
        return "{\"trackId\":0,\"totalLaps\":50,\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":["
                + "{\"ai\":false,\"pos\":5,\"lap\":" + lap + ",\"lapDist\":" + lapDist
                + ",\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"M\",\"tyreAge\":5,\"fuel\":50.0"
                + ",\"pitStatus\":0,\"pits\":0,\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0,\"name\":\"Player\"}"
                + ",{\"ai\":true,\"pos\":4,\"lap\":" + lap + ",\"lapDist\":5000.0"
                + ",\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"M\",\"tyreAge\":5,\"fuel\":50.0"
                + ",\"pitStatus\":0,\"pits\":0,\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0,\"name\":\"Norris\"}"
                + "]}";
    }

    /** Build a strategy evaluation recommending a pit on `pitLap` onto `compound`. */
    private StrategyEvaluation strategyEvaluation(int pitLap, int compound) {
        var stop = new RaceSnapshot.PitStrategy.PitStop(pitLap, compound);
        var candidate = new StrategyEvaluation.StrategyCandidate("1-stop", List.of(stop));
        var ranked = new StrategyEvaluation.RankedStrategy(
                1, candidate, 5.0, 1.0, 4.0, 6.0, 0.0, 0.0, 1.0, 10.0);
        return new StrategyEvaluation(0, List.of(ranked));
    }

    private List<String> broadcastsContaining(String s) {
        return broadcasts.stream().filter(b -> b.contains(s)).toList();
    }

    private void flush(int lap, float lapDist) {
        // Alternate lap distance to cycle safe zones and reset the NORMAL budget.
        // Melbourne zone 0 ≈ 0-330, zone 1 ≈ 1065-1485.
        for (int i = 0; i < 4; i++) {
            service.onStateUpdate(stateJson(lap, lapDist));
            service.onStateUpdate(stateJson(lap, 1200.0f));
        }
    }

    @Test
    void earlyRaceEmitsNothingAboutPitWindow() {
        // Strategy recommends lap 20 (Hards). We're at lap 3 — delta=17, should be silent.
        service.onStrategyEvaluation(3, strategyEvaluation(20, 18));
        flush(3, 200.0f);

        assertTrue(broadcastsContaining("Box window opens").isEmpty(),
                "No T-5 at delta=17, got: " + broadcasts);
        assertTrue(broadcastsContaining("Box next lap").isEmpty(),
                "No T-1 at delta=17, got: " + broadcasts);
        assertTrue(broadcastsContaining("Box, box, box").isEmpty(),
                "No box-box at delta=17, got: " + broadcasts);
    }

    @Test
    void emitsThreeMessagesAcrossWindow() {
        // Target lap 20, Hards.
        service.onStrategyEvaluation(10, strategyEvaluation(20, 18));

        // Lap 15 → delta=5: T-5 fires once.
        flush(15, 200.0f);
        assertEquals(1, broadcastsContaining("Box window opens in 5 laps").size(),
                "Expected exactly one T-5, got: " + broadcasts);
        assertTrue(broadcastsContaining("Hards").stream().anyMatch(b -> b.contains("Box window")),
                "T-5 should mention compound: " + broadcasts);

        // Laps 16-18 → delta 4,3,2: no *pit* messages fire (periodic awareness is unrelated).
        flush(16, 200.0f);
        flush(17, 200.0f);
        flush(18, 200.0f);
        assertEquals(1, broadcastsContaining("Box window opens").size(),
                "Mid-window laps must not re-emit T-5, got: " + broadcasts);
        assertTrue(broadcastsContaining("Box next lap").isEmpty(),
                "T-1 should only fire at delta=1, got: " + broadcasts);
        assertTrue(broadcastsContaining("Box, box, box").isEmpty(),
                "box-box should only fire on target lap, got: " + broadcasts);

        // Lap 19 → delta=1: T-1 fires once.
        flush(19, 200.0f);
        assertEquals(1, broadcastsContaining("Box next lap").size(),
                "Expected exactly one T-1, got: " + broadcasts);

        // Lap 20, lapDist well under 80% of 5303 (=4242): no box-box yet.
        int beforePit = broadcasts.size();
        flush(20, 200.0f);
        flush(20, 2000.0f);
        assertEquals(beforePit, broadcasts.size(),
                "box-box should wait for ~80% of lap, got: " + broadcasts);

        // Lap 20, lapDist past 80%: box-box fires once.
        service.onStateUpdate(stateJson(20, 4500.0f));
        assertEquals(1, broadcastsContaining("Box, box, box").size(),
                "Expected exactly one box-box, got: " + broadcasts);

        // Extra ticks should not re-emit.
        flush(20, 4800.0f);
        assertEquals(1, broadcastsContaining("Box, box, box").size(),
                "box-box should not repeat, got: " + broadcasts);
        assertEquals(1, broadcastsContaining("Box window opens in 5 laps").size());
        assertEquals(1, broadcastsContaining("Box next lap").size());
    }

    @Test
    void newTargetResetsEmissionState() {
        // T-5 fired for target 20 at lap 15.
        service.onStrategyEvaluation(10, strategyEvaluation(20, 18));
        flush(15, 200.0f);
        assertEquals(1, broadcastsContaining("Box window opens in 5 laps").size());

        // Strategy re-recommends lap 25 instead — we're at lap 20, delta=5 for new target.
        service.onStrategyEvaluation(20, strategyEvaluation(25, 17));
        flush(20, 200.0f);

        // A fresh T-5 fires for the new target, with the new compound name.
        assertEquals(2, broadcastsContaining("Box window opens in 5 laps").size(),
                "Fresh T-5 should fire for new target, got: " + broadcasts);
        assertFalse(broadcastsContaining("Mediums").stream().noneMatch(b -> b.contains("Box window")),
                "New T-5 should mention Mediums: " + broadcasts);
    }
}
