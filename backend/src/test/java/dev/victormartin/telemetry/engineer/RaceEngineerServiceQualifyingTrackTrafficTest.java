package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServiceQualifyingTrackTrafficTest {

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
        // sessionType 6 = Q1 (qualifying)
        service.onSessionStarted("test-session", 0, 6, 1, 1);
    }

    /**
     * Player at lapDist 200 (Melbourne safe zone 0-330), pit lane (pitStatus=1).
     * Rival lapDist varies to drive the clear/hold decision.
     */
    private String stateJson(float playerLapDist, float rivalLapDist) {
        return "{\"trackId\":0,\"totalLaps\":50,\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":["
                + "{\"idx\":0,\"ai\":false,\"pos\":5,\"lap\":3,\"sector\":0"
                + ",\"lapDist\":" + playerLapDist + ",\"throttle\":0.0"
                + ",\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"S\",\"tyreAge\":1,\"fuel\":50.0,\"pitStatus\":1,\"pits\":0"
                + ",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0"
                + ",\"tyreWear\":[5.0,5.0,5.0,5.0]"
                + ",\"lastSectorMs\":[28000,61000,0],\"lastLapTimeMs\":0"
                + ",\"name\":\"Player\"}"
                + ",{\"idx\":1,\"ai\":true,\"pos\":4,\"lap\":3,\"sector\":0"
                + ",\"lapDist\":" + rivalLapDist + ",\"throttle\":0.9"
                + ",\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"S\",\"tyreAge\":1,\"fuel\":50.0,\"pitStatus\":0,\"pits\":0"
                + ",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0"
                + ",\"tyreWear\":[5.0,5.0,5.0,5.0]"
                + ",\"lastSectorMs\":[27000,59000,0],\"lastLapTimeMs\":0"
                + ",\"name\":\"Piastri\"}"
                + "]}";
    }

    private List<String> broadcastsContaining(String s) {
        return broadcasts.stream().filter(b -> b.contains(s)).toList();
    }

    @Test
    void clearMessageFiresInQualifyingPitLane() {
        // Rival far away on track: gap = (200 - 3000 + 5303)/55 ≈ 45.5s > 15 → "clear"
        service.onStateUpdate(stateJson(200.0f, 3000.0f));
        assertEquals(1, broadcastsContaining("Track is clear, go now.").size(),
                "Clear message should fire in qualifying pit lane, got: " + broadcasts);
    }

    @Test
    void holdMessageFiresInQualifyingPitLane() {
        // Rival close: gap = (200 - 5100 + 5303)/55 ≈ 7.3s < 8 → "hold"
        service.onStateUpdate(stateJson(200.0f, 5100.0f));
        assertEquals(1, broadcastsContaining("Hold position, Piastri about to pass.").size(),
                "Hold message should fire in qualifying pit lane, got: " + broadcasts);
    }
}
