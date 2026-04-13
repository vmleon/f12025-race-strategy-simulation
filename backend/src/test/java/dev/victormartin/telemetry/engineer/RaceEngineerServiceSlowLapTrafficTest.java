package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServiceSlowLapTrafficTest {

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
    }

    private void startSession(int sessionType) {
        service.onSessionStarted("test-session", 0, sessionType, 1, 1);
    }

    /**
     * Player at lapDist 200 (Melbourne safe zone 0-330). Player pos=5,
     * rival at pos=6. Rival lapDist varies to simulate closing.
     */
    private String stateJson(float throttle, float rivalLapDist) {
        return "{\"trackId\":0,\"totalLaps\":50,\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":["
                + "{\"idx\":0,\"ai\":false,\"pos\":5,\"lap\":3,\"sector\":0"
                + ",\"lapDist\":200.0,\"throttle\":" + throttle
                + ",\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"M\",\"tyreAge\":5,\"fuel\":50.0,\"pitStatus\":0,\"pits\":0"
                + ",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0"
                + ",\"tyreWear\":[10.0,10.0,10.0,10.0]"
                + ",\"lastSectorMs\":[28000,61000,0],\"lastLapTimeMs\":0"
                + ",\"name\":\"Player\"}"
                + ",{\"idx\":1,\"ai\":true,\"pos\":6,\"lap\":3,\"sector\":0"
                + ",\"lapDist\":" + rivalLapDist + ",\"throttle\":0.9"
                + ",\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"M\",\"tyreAge\":5,\"fuel\":50.0,\"pitStatus\":0,\"pits\":0"
                + ",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0"
                + ",\"tyreWear\":[10.0,10.0,10.0,10.0]"
                + ",\"lastSectorMs\":[27000,59000,0],\"lastLapTimeMs\":0"
                + ",\"name\":\"Piastri\"}"
                + "]}";
    }

    private List<String> broadcastsContaining(String s) {
        return broadcasts.stream().filter(b -> b.contains(s)).toList();
    }

    /**
     * Feed 3 identical slow-throttle updates to fill the throttle buffer
     * and establish an initial previousSlowLapGapBehind baseline.
     */
    private void primeSlowBuffer(float rivalLapDist) {
        for (int i = 0; i < 3; i++) {
            service.onStateUpdate(stateJson(0.20f, rivalLapDist));
        }
    }

    @Test
    void firesWhenPlayerSlowAndCarBehindClosing() {
        startSession(2); // practice
        primeSlowBuffer(35.0f);
        // Rival closes to 40 (160m ≈ 2.9s) — gap decreased → closing → fire
        service.onStateUpdate(stateJson(0.20f, 40.0f));
        assertEquals(1, broadcastsContaining("Piastri closing fast behind, let them through.").size(),
                "Should fire slow-lap traffic warning, got: " + broadcasts);
    }

    @Test
    void doesNotFireOnPushLap() {
        startSession(2);
        for (int i = 0; i < 3; i++) {
            service.onStateUpdate(stateJson(0.95f, 35.0f));
        }
        service.onStateUpdate(stateJson(0.95f, 40.0f));
        assertEquals(0, broadcastsContaining("closing fast behind").size(),
                "Should NOT fire on push lap, got: " + broadcasts);
    }

    @Test
    void doesNotFireWhenRivalFarAway() {
        startSession(2);
        // Rival > 4s behind: gap > 220m. rivalDist=5280 wraps to 223m gap ≈ 4.05s
        primeSlowBuffer(5280.0f);
        service.onStateUpdate(stateJson(0.20f, 5285.0f));
        assertEquals(0, broadcastsContaining("closing fast behind").size(),
                "Should NOT fire when gap > 4s, got: " + broadcasts);
    }

    @Test
    void doesNotFireWhenRivalFallingBack() {
        startSession(2);
        primeSlowBuffer(40.0f);
        // Rival lapDist decreases → gap increases (falling back)
        service.onStateUpdate(stateJson(0.20f, 35.0f));
        assertEquals(0, broadcastsContaining("closing fast behind").size(),
                "Should NOT fire when gap is increasing, got: " + broadcasts);
    }

    @Test
    void suppressesDuringCooldown() {
        startSession(2);
        primeSlowBuffer(35.0f);
        service.onStateUpdate(stateJson(0.20f, 40.0f)); // fire #1
        int firstCount = broadcastsContaining("closing fast behind").size();
        assertEquals(1, firstCount);

        // Keep closing within cooldown window — should not fire again
        service.onStateUpdate(stateJson(0.20f, 45.0f));
        service.onStateUpdate(stateJson(0.20f, 50.0f));
        assertEquals(1, broadcastsContaining("closing fast behind").size(),
                "Should not fire again within 15s cooldown, got: " + broadcasts);
    }

    @Test
    void doesNotFireInRaceSession() {
        startSession(10); // race
        primeSlowBuffer(35.0f);
        service.onStateUpdate(stateJson(0.20f, 40.0f));
        assertEquals(0, broadcastsContaining("closing fast behind").size(),
                "Should NOT fire in race session, got: " + broadcasts);
    }
}
