package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServicePerCornerWearTest {

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

    /** tyreWear order is [RL, RR, FL, FR]. tyreAge stays low to avoid total-age alerts. */
    private String stateJson(int tyreAge, float wearRL, float wearRR, float wearFL, float wearFR) {
        return "{\"trackId\":0,\"totalLaps\":50,\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":["
                + "{\"ai\":false,\"pos\":5,\"lap\":3,\"lapDist\":200.0,\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"M\""
                + ",\"tyreAge\":" + tyreAge + ",\"fuel\":50.0,\"pitStatus\":0,\"pits\":0"
                + ",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0"
                + ",\"tyreWear\":[" + wearRL + "," + wearRR + "," + wearFL + "," + wearFR + "]"
                + ",\"name\":\"Player\"}"
                + ",{\"ai\":true,\"pos\":4,\"lap\":3,\"lapDist\":5000.0,\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"M\""
                + ",\"tyreAge\":5,\"fuel\":50.0,\"pitStatus\":0,\"pits\":0"
                + ",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0,\"name\":\"Norris\"}"
                + "]}";
    }

    private List<String> broadcastsContaining(String s) {
        return broadcasts.stream().filter(b -> b.contains(s)).toList();
    }

    /** Flush the queue by alternating safe zones. */
    private void flush(int tyreAge, float rl, float rr, float fl, float fr) {
        for (int i = 0; i < 6; i++) {
            service.onStateUpdate(stateJson(tyreAge, rl, rr, fl, fr));
            service.onStateUpdate(stateJson(tyreAge, rl, rr, fl, fr).replace("\"lapDist\":200.0", "\"lapDist\":1200.0"));
        }
    }

    // -- 25% threshold per corner ---------------------------------------------

    @Test
    void rearLeftCrosses25Percent() {
        flush(5, 26f, 0f, 0f, 0f);
        List<String> msgs = broadcastsContaining("Rear-left starting to degrade");
        assertEquals(1, msgs.size(), "RL 25% should fire once, got: " + broadcasts);
        assertTrue(msgs.get(0).contains("NORMAL"));
    }

    @Test
    void rearRightCrosses25Percent() {
        flush(5, 0f, 26f, 0f, 0f);
        assertEquals(1, broadcastsContaining("Rear-right starting to degrade").size(),
                "RR 25% should fire once, got: " + broadcasts);
    }

    @Test
    void frontLeftCrosses25Percent() {
        flush(5, 0f, 0f, 26f, 0f);
        assertEquals(1, broadcastsContaining("Front-left starting to degrade").size(),
                "FL 25% should fire once, got: " + broadcasts);
    }

    @Test
    void frontRightCrosses25Percent() {
        flush(5, 0f, 0f, 0f, 26f);
        assertEquals(1, broadcastsContaining("Front-right starting to degrade").size(),
                "FR 25% should fire once, got: " + broadcasts);
    }

    // -- 50% threshold per corner ---------------------------------------------

    @Test
    void rearLeftCrosses50Percent() {
        flush(5, 51f, 0f, 0f, 0f);
        List<String> msgs = broadcastsContaining("Rear-left is finished");
        assertEquals(1, msgs.size(), "RL 50% should fire once, got: " + broadcasts);
        assertTrue(msgs.get(0).contains("HIGH"));
    }

    @Test
    void rearRightCrosses50Percent() {
        flush(5, 0f, 51f, 0f, 0f);
        assertEquals(1, broadcastsContaining("Rear-right is finished").size(),
                "RR 50% should fire once, got: " + broadcasts);
    }

    @Test
    void frontLeftCrosses50Percent() {
        flush(5, 0f, 0f, 51f, 0f);
        assertEquals(1, broadcastsContaining("Front-left is finished").size(),
                "FL 50% should fire once, got: " + broadcasts);
    }

    @Test
    void frontRightCrosses50Percent() {
        flush(5, 0f, 0f, 0f, 51f);
        assertEquals(1, broadcastsContaining("Front-right is finished").size(),
                "FR 50% should fire once, got: " + broadcasts);
    }

    // -- dedup within a stint --------------------------------------------------

    @Test
    void doesNotRepeatSameThresholdWithinStint() {
        // Cross 25% multiple times: only one message.
        flush(5, 30f, 0f, 0f, 0f);
        flush(6, 35f, 0f, 0f, 0f);
        flush(7, 40f, 0f, 0f, 0f);
        assertEquals(1, broadcastsContaining("Rear-left starting to degrade").size(),
                "Should not repeat 25% alert, got: " + broadcasts);
    }

    // -- reset on tyre change --------------------------------------------------

    @Test
    void tyreChangeResetsCornerTracking() {
        // Build up wear and fire alert.
        flush(10, 30f, 0f, 0f, 0f);
        assertEquals(1, broadcastsContaining("Rear-left starting to degrade").size());

        // Tyre change: tyreAge drops (e.g., 10 → 1), wear resets to 0 in-game, then climbs again.
        flush(1, 5f, 0f, 0f, 0f);
        flush(3, 30f, 0f, 0f, 0f);
        assertEquals(2, broadcastsContaining("Rear-left starting to degrade").size(),
                "Alert should fire again for new stint, got: " + broadcasts);
    }
}
