package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServicePositionAwarenessTest {

    private CircuitSafeZoneService safeZoneService;
    private List<String> broadcasts;
    private RaceEngineerService service;

    @BeforeEach
    void setUp() {
        safeZoneService = new CircuitSafeZoneService();
        safeZoneService.loadCircuits();
        broadcasts = new ArrayList<>();
        RaceEngineerWebSocketHandler handler = new RaceEngineerWebSocketHandler() {
            @Override
            public void broadcast(String jsonLine) {
                broadcasts.add(jsonLine);
            }
        };
        service = new RaceEngineerService(safeZoneService, handler);
        service.onSessionStarted("test-session", 0, 10, 1, 1); // sessionType=10 (Race), assists on
    }

    /** Build a state tick with player + one other car. playerLapDist picks a safe zone on Melbourne (track 0). */
    private String stateJson(int playerPos, int playerLap, float playerLapDist,
                             int totalLaps, int otherPos, float otherLapDist, String otherName) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"trackId\":0,\"totalLaps\":").append(totalLaps)
          .append(",\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":[");
        sb.append("{\"ai\":false,\"pos\":").append(playerPos);
        sb.append(",\"lap\":").append(playerLap);
        sb.append(",\"lapDist\":").append(playerLapDist);
        sb.append(",\"drsAllowed\":0,\"ersMode\":0,\"compound\":16,\"tyreAge\":5,\"fuel\":50.0");
        sb.append(",\"pitStatus\":0,\"pitCount\":0,\"penaltySeconds\":0");
        sb.append(",\"unservedDT\":0,\"unservedSG\":0,\"unservedPenalties\":0,\"warnings\":0}");
        sb.append(",{\"ai\":true,\"pos\":").append(otherPos);
        sb.append(",\"lap\":").append(playerLap);
        sb.append(",\"lapDist\":").append(otherLapDist);
        sb.append(",\"drsAllowed\":0,\"ersMode\":0,\"compound\":16,\"tyreAge\":5,\"fuel\":50.0");
        sb.append(",\"pitStatus\":0,\"pitCount\":0,\"penaltySeconds\":0");
        sb.append(",\"unservedDT\":0,\"unservedSG\":0,\"unservedPenalties\":0,\"warnings\":0");
        sb.append(",\"name\":\"").append(otherName).append("\"}");
        sb.append("]}");
        return sb.toString();
    }

    /** Tick several times at the same state, alternating safe zones, to flush the queue. */
    private void pollQueue(int playerPos, int playerLap, int totalLaps,
                            int otherPos, float otherLapDist, String otherName) {
        // Melbourne zone 0 is 0-330, zone 1 is 1065-1485 (after widening). Alternate to reset budget.
        for (int i = 0; i < 4; i++) {
            service.onStateUpdate(stateJson(playerPos, playerLap, 200.0f, totalLaps,
                    otherPos, otherLapDist, otherName));
            service.onStateUpdate(stateJson(playerPos, playerLap, 1200.0f, totalLaps,
                    otherPos, otherLapDist, otherName));
        }
    }

    private List<String> broadcastsContaining(String s) {
        return broadcasts.stream().filter(b -> b.contains(s)).toList();
    }

    // -- position gained -------------------------------------------------------

    @Test
    void positionGainEnrichesMessageWithCarAheadAndGap() {
        // Baseline: player P5, AI P4 far ahead (no DRS/close messages)
        service.onStateUpdate(stateJson(5, 3, 200.0f, 50, 4, 5000.0f, "Norris"));
        broadcasts.clear();

        // Player gains to P4, so P3 is now the "next" ahead (Verstappen, ~2s = 110m up)
        service.onStateUpdate(stateJson(4, 3, 200.0f, 50, 3, 310.0f, "Verstappen"));

        List<String> gains = broadcastsContaining("is next");
        assertFalse(gains.isEmpty(), "Expected enriched gain message, got: " + broadcasts);
        String msg = gains.get(0);
        assertTrue(msg.contains("P4"), "Gain should announce new position P4: " + msg);
        assertTrue(msg.contains("Verstappen"), "Gain should name car now ahead: " + msg);
        assertTrue(msg.contains("up the road"), "Gain should mention the gap: " + msg);
        assertTrue(msg.contains("IMMEDIATE"), "Gain should be IMMEDIATE: " + msg);
    }

    @Test
    void positionGainToFirstSaysLeadingNow() {
        service.onStateUpdate(stateJson(2, 3, 200.0f, 50, 1, 5000.0f, "Verstappen"));
        broadcasts.clear();

        service.onStateUpdate(stateJson(1, 3, 200.0f, 50, 2, 4800.0f, "Verstappen"));

        assertFalse(broadcastsContaining("Leading now").isEmpty(),
                "P1 gain should say 'Leading now', got: " + broadcasts);
    }

    // -- position lost ---------------------------------------------------------

    @Test
    void positionLossGeneratesHighPriorityMessage() {
        // Baseline: player P4, AI P5 far behind (no reactive close-behind message)
        service.onStateUpdate(stateJson(4, 3, 200.0f, 50, 5, 100.0f, "Tsunoda"));
        broadcasts.clear();

        // Player drops to P5; AI now at P4 (car now ahead)
        // AI far away (5000m) so detectCarAhead doesn't interfere with DRS range (DRS gated by drsAssist anyway)
        service.onStateUpdate(stateJson(5, 3, 200.0f, 50, 4, 5000.0f, "Tsunoda"));
        // Flush HIGH message from queue (needs safe zone)
        pollQueue(5, 3, 50, 4, 5000.0f, "Tsunoda");

        List<String> losses = broadcastsContaining("Lost a place");
        assertFalse(losses.isEmpty(), "Expected position-loss message, got: " + broadcasts);
        String msg = losses.get(0);
        assertTrue(msg.contains("P5"), "Loss should announce new position P5: " + msg);
        assertTrue(msg.contains("Tsunoda"), "Loss should name driver now ahead: " + msg);
        assertTrue(msg.contains("HIGH"), "Loss should be HIGH priority: " + msg);
    }

    // -- periodic situational awareness ----------------------------------------

    @Test
    void periodicAwarenessFiresOnLapThree() {
        // Lap 3: periodic fires (3 % 3 == 0, >1, <50). Car ahead comfortable gap (2s).
        service.onStateUpdate(stateJson(5, 3, 200.0f, 50, 4, 310.0f, "Norris"));
        pollQueue(5, 3, 50, 4, 310.0f, "Norris");

        List<String> periodic = broadcastsContaining("s to Norris");
        assertFalse(periodic.isEmpty(),
                "Periodic awareness should fire on lap 3, got: " + broadcasts);
        String msg = periodic.get(0);
        assertTrue(msg.contains("NORMAL"), "Periodic should be NORMAL: " + msg);
        assertTrue(msg.contains("P5"), "Periodic should mention player pos: " + msg);
    }

    @Test
    void periodicAwarenessSkippedOnLapOne() {
        service.onStateUpdate(stateJson(5, 1, 200.0f, 50, 4, 310.0f, "Norris"));
        pollQueue(5, 1, 50, 4, 310.0f, "Norris");

        assertTrue(broadcastsContaining("s to Norris").isEmpty(),
                "Periodic awareness should be skipped on lap 1, got: " + broadcasts);
    }

    @Test
    void periodicAwarenessSkippedOnFinalLap() {
        // totalLaps=3, currentLap=3 → final lap, periodic skipped even though 3 % 3 == 0
        service.onStateUpdate(stateJson(5, 3, 200.0f, 3, 4, 310.0f, "Norris"));
        pollQueue(5, 3, 3, 4, 310.0f, "Norris");

        assertTrue(broadcastsContaining("s to Norris").isEmpty(),
                "Periodic awareness should be skipped on final lap, got: " + broadcasts);
    }

    @Test
    void periodicAwarenessSkippedOnNonMultipleLaps() {
        // Lap 4: not a multiple of 3, periodic skipped
        service.onStateUpdate(stateJson(5, 4, 200.0f, 50, 4, 310.0f, "Norris"));
        pollQueue(5, 4, 50, 4, 310.0f, "Norris");

        assertTrue(broadcastsContaining("s to Norris").isEmpty(),
                "Periodic awareness should only fire every 3 laps, got: " + broadcasts);
    }

    @Test
    void periodicAwarenessDedupedByReactiveBehindMessage() {
        // Baseline on lap 2 (not a multiple of 3, so no periodic enqueued) with behind far away.
        // Player at 800m, AI P6 at 200m → gap 600m ≈ 10.9s behind.
        service.onStateUpdate(stateJson(5, 2, 800.0f, 50, 6, 200.0f, "Albon"));
        broadcasts.clear();

        // Lap 3: reactive "closing from behind" fires (gap crosses 2s) and should dedup periodic same tick.
        service.onStateUpdate(stateJson(5, 3, 800.0f, 50, 6, 780.0f, "Albon"));
        pollQueue(5, 3, 50, 6, 780.0f, "Albon");

        assertFalse(broadcastsContaining("closing from behind").isEmpty(),
                "Reactive behind should fire, got: " + broadcasts);
        assertTrue(broadcastsContaining("s to Albon").isEmpty(),
                "Periodic should be deduped when reactive fired same lap, got: " + broadcasts);
    }
}
