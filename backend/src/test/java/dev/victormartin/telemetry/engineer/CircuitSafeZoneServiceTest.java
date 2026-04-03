package dev.victormartin.telemetry.engineer;

import dev.victormartin.telemetry.GameMappings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitSafeZoneServiceTest {

    private static CircuitSafeZoneService service;

    @BeforeAll
    static void setUp() {
        service = new CircuitSafeZoneService();
        service.loadCircuits();
    }

    @Test
    void everyTrackInGameMappingsHasSafeZoneConfig() {
        for (int trackId : GameMappings.trackIds()) {
            assertTrue(service.hasCircuit(trackId),
                    "Missing safe zone config for trackId=" + trackId
                            + " (" + GameMappings.trackName(trackId) + ")");
        }
    }

    @Test
    void safeZonesAreUsable() {
        // Verify a known circuit returns false outside safe zones
        // Melbourne (0): safe zone at 0-300m, so 500m should be outside
        assertFalse(service.isSafeToDeliver(0, 500f));
        // Melbourne: 200m should be inside the start/finish straight zone
        assertTrue(service.isSafeToDeliver(0, 200f));
    }
}
