package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServiceAssistGuardTest {

    private CircuitSafeZoneService safeZoneService;
    private List<String> broadcasts;
    private RaceEngineerService service;

    @BeforeEach
    void setUp() {
        safeZoneService = new CircuitSafeZoneService();
        safeZoneService.loadCircuits();
        broadcasts = new ArrayList<>();
    }

    private void createService(int ersAssist, int drsAssist) {
        RaceEngineerWebSocketHandler handler = new RaceEngineerWebSocketHandler() {
            @Override
            public void broadcast(String jsonLine) {
                broadcasts.add(jsonLine);
            }
        };
        service = new RaceEngineerService(safeZoneService, handler);
        service.onSessionStarted("test-session", 0, ersAssist, drsAssist);
    }

    /**
     * Build a state JSON with a player car and optionally an AI car ahead/behind.
     */
    private String stateJson(int playerPos, float playerLapDist, int drsAllowed, int ersMode,
                             Integer aiPos, Float aiLapDist) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"trackId\":0,\"totalLaps\":50,\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":[");
        // Player car
        sb.append("{\"ai\":false,\"pos\":").append(playerPos);
        sb.append(",\"lap\":3,\"lapDist\":").append(playerLapDist);
        sb.append(",\"drsAllowed\":").append(drsAllowed);
        sb.append(",\"ersMode\":").append(ersMode);
        sb.append(",\"compound\":16,\"tyreAge\":5,\"fuel\":50.0");
        sb.append(",\"pitStatus\":0,\"pitCount\":0");
        sb.append(",\"penaltySeconds\":0,\"unservedDT\":0,\"unservedSG\":0,\"unservedPenalties\":0,\"warnings\":0}");
        // AI car
        if (aiPos != null && aiLapDist != null) {
            sb.append(",{\"ai\":true,\"pos\":").append(aiPos);
            sb.append(",\"lap\":3,\"lapDist\":").append(aiLapDist);
            sb.append(",\"drsAllowed\":0,\"ersMode\":0");
            sb.append(",\"compound\":16,\"tyreAge\":5,\"fuel\":50.0");
            sb.append(",\"pitStatus\":0,\"pitCount\":0");
            sb.append(",\"penaltySeconds\":0,\"unservedDT\":0,\"unservedSG\":0,\"unservedPenalties\":0,\"warnings\":0");
            sb.append(",\"name\":\"Verstappen\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private void drainAllMessages() {
        // Send enough updates from safe zone to drain the queue (radio check + any generated messages)
        for (int i = 0; i < 10; i++) {
            service.onStateUpdate(stateJson(2, 200.0f, 0, 0, 1, 250.0f));
        }
    }

    private List<String> drsRelatedBroadcasts() {
        return broadcasts.stream().filter(b -> b.contains("DRS")).toList();
    }

    private List<String> ersRelatedBroadcasts() {
        return broadcasts.stream().filter(b -> b.contains("ERS mode")).toList();
    }

    // -------------------------------------------------------------------------

    @Test
    void drsAssistSuppressesDrsMessages() {
        createService(0, 1); // drsAssist=1
        // Drain the initial "Radio check" message
        drainAllMessages();
        broadcasts.clear();

        // First update: AI car ahead, not in DRS range yet (gap > 1s => ~55m)
        service.onStateUpdate(stateJson(2, 200.0f, 0, 0, 1, 300.0f));
        // Second update: AI car now within DRS range (< 55m gap ≈ < 1s)
        service.onStateUpdate(stateJson(2, 200.0f, 0, 0, 1, 220.0f));
        // Drain any enqueued messages
        drainAllMessages();

        assertTrue(drsRelatedBroadcasts().isEmpty(),
                "Expected no DRS messages when drsAssist=1, but got: " + drsRelatedBroadcasts());
    }

    @Test
    void ersAssistSuppressesErsModeMessages() {
        createService(1, 0); // ersAssist=1
        drainAllMessages();
        broadcasts.clear();

        // First update establishes baseline ERS mode 0
        service.onStateUpdate(stateJson(2, 200.0f, 0, 0, 1, 300.0f));
        // Second update changes ERS mode to 3
        service.onStateUpdate(stateJson(2, 200.0f, 0, 3, 1, 300.0f));
        // Drain
        drainAllMessages();

        assertTrue(ersRelatedBroadcasts().isEmpty(),
                "Expected no ERS mode messages when ersAssist=1, but got: " + ersRelatedBroadcasts());
    }

    @Test
    void manualModeAllowsDrsMessages() {
        createService(0, 0); // drsAssist=0
        drainAllMessages();
        broadcasts.clear();

        // First update: AI car ahead, not in DRS range
        service.onStateUpdate(stateJson(2, 200.0f, 0, 0, 1, 300.0f));
        // Second update: AI car within DRS range
        service.onStateUpdate(stateJson(2, 200.0f, 0, 0, 1, 220.0f));
        // Drain
        drainAllMessages();

        assertFalse(drsRelatedBroadcasts().isEmpty(),
                "Expected DRS messages when drsAssist=0, but got none");
    }

    @Test
    void manualModeAllowsErsModeMessages() {
        createService(0, 0); // ersAssist=0
        drainAllMessages();
        broadcasts.clear();

        // Establish baseline ERS mode 0
        service.onStateUpdate(stateJson(2, 200.0f, 0, 0, 1, 300.0f));
        // Change ERS mode to 3
        service.onStateUpdate(stateJson(2, 200.0f, 0, 3, 1, 300.0f));
        // Drain
        drainAllMessages();

        assertFalse(ersRelatedBroadcasts().isEmpty(),
                "Expected ERS mode messages when ersAssist=0, but got none");
    }
}
