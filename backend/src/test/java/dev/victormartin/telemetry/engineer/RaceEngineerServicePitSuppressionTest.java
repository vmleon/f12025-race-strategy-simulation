package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServicePitSuppressionTest {

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
        service.onSessionStarted("test-session", 0, 1, 1);
    }

    /** State JSON with configurable player pit status and pit count. */
    private String stateJson(int playerPos, int pitStatus, int pitCount,
                             int otherPos, float otherLapDist, String otherName) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"trackId\":0,\"totalLaps\":50,\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":[");
        sb.append("{\"ai\":false,\"pos\":").append(playerPos);
        sb.append(",\"lap\":3,\"lapDist\":200.0,\"drsAllowed\":0,\"ersMode\":0,\"compound\":16,\"tyreAge\":5,\"fuel\":50.0");
        sb.append(",\"pitStatus\":").append(pitStatus);
        sb.append(",\"pits\":").append(pitCount);
        sb.append(",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0,\"name\":\"Player\"}");
        sb.append(",{\"ai\":true,\"pos\":").append(otherPos);
        sb.append(",\"lap\":3,\"lapDist\":").append(otherLapDist);
        sb.append(",\"drsAllowed\":0,\"ersMode\":0,\"compound\":16,\"tyreAge\":5,\"fuel\":50.0");
        sb.append(",\"pitStatus\":0,\"pits\":0,\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0");
        sb.append(",\"name\":\"").append(otherName).append("\"}");
        sb.append("]}");
        return sb.toString();
    }

    private List<String> broadcastsContaining(String s) {
        return broadcasts.stream().filter(b -> b.contains(s)).toList();
    }

    /** Emit extra safe-zone ticks so all queued messages get a chance to flush (one per tick). */
    private void flushQueue(int playerPos, int pitStatus, int pitCount, int otherPos, float otherLapDist, String otherName) {
        for (int i = 0; i < 4; i++) {
            service.onStateUpdate(stateJson(playerPos, pitStatus, pitCount, otherPos, otherLapDist, otherName));
        }
    }

    @Test
    void suppressesPositionChangesWhilePlayerInPitLane() {
        // Baseline lap 3 — establish previousPlayerPosition=5.
        service.onStateUpdate(stateJson(5, 0, 0, 4, 5000.0f, "Norris"));
        broadcasts.clear();

        // Enter pit lane at P5.
        service.onStateUpdate(stateJson(5, 1, 0, 4, 5000.0f, "Norris"));
        // While in pit lane, cars pass and player falls to P14 — no messages should fire.
        service.onStateUpdate(stateJson(10, 2, 0, 9, 5000.0f, "Norris"));
        service.onStateUpdate(stateJson(12, 2, 0, 11, 5000.0f, "Norris"));
        service.onStateUpdate(stateJson(14, 2, 0, 13, 5000.0f, "Norris"));

        assertTrue(broadcastsContaining("Lost a place").isEmpty(),
                "No loss messages should fire in pit lane, got: " + broadcasts);
        assertTrue(broadcastsContaining("is next").isEmpty(),
                "No gain messages should fire in pit lane, got: " + broadcasts);
    }

    @Test
    void emitsRecapOnPitExitAndNoSpuriousGainMessage() {
        // Baseline P5.
        service.onStateUpdate(stateJson(5, 0, 0, 4, 5000.0f, "Norris"));
        // Enter pit lane.
        service.onStateUpdate(stateJson(5, 1, 0, 4, 5000.0f, "Norris"));
        // Fall to P14 while in pit lane.
        service.onStateUpdate(stateJson(14, 2, 0, 13, 310.0f, "Tsunoda"));
        broadcasts.clear();

        // Exit: pitStatus back to 0, pit count incremented, now at P14 with Tsunoda at P13 ahead.
        service.onStateUpdate(stateJson(14, 0, 1, 13, 310.0f, "Tsunoda"));
        flushQueue(14, 0, 1, 13, 310.0f, "Tsunoda");

        List<String> recaps = broadcastsContaining("Out of the pits in P14");
        assertFalse(recaps.isEmpty(), "Expected pit-exit recap, got: " + broadcasts);
        assertTrue(recaps.get(0).contains("Tsunoda"),
                "Recap should name the car now ahead: " + recaps.get(0));
        assertTrue(broadcastsContaining("is next").isEmpty(),
                "Should NOT fire a spurious gain message on exit: " + broadcasts);
        assertTrue(broadcastsContaining("Lost a place").isEmpty(),
                "Should NOT fire a loss message on exit: " + broadcasts);
    }

    @Test
    void resumesNormalPositionTrackingAfterPitExit() {
        // Baseline P5 then pit cycle, exit at P14.
        service.onStateUpdate(stateJson(5, 0, 0, 4, 5000.0f, "Norris"));
        service.onStateUpdate(stateJson(5, 1, 0, 4, 5000.0f, "Norris"));
        service.onStateUpdate(stateJson(14, 2, 0, 13, 310.0f, "Tsunoda"));
        service.onStateUpdate(stateJson(14, 0, 1, 13, 310.0f, "Tsunoda"));
        broadcasts.clear();

        // One more on-track tick settles state (pitStatus=0, prevPitStatus=0).
        service.onStateUpdate(stateJson(14, 0, 1, 13, 310.0f, "Tsunoda"));
        // Now the player gains a place back to P13 — this SHOULD fire normally.
        service.onStateUpdate(stateJson(13, 0, 1, 14, 100.0f, "Tsunoda"));
        flushQueue(13, 0, 1, 14, 100.0f, "Tsunoda");

        assertFalse(broadcastsContaining("is next").isEmpty(),
                "Normal position tracking should resume after pit exit, got: " + broadcasts);
    }
}
