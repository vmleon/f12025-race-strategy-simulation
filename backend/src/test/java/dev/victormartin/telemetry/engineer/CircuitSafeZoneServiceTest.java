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

    @Test
    void lagOffsetExpandsZoneStartAtHighSpeed() {
        // Melbourne Lakeside Drive zone starts at 1100m.
        // At 250 km/h = 69.4 m/s, with 1.5s lag, offset = 104.2m.
        // So 1000m should now be in the zone (1100 - 104.2 = 995.8).
        assertTrue(service.isSafeToDeliver(0, 1000f, 250));
        // Without speed, 1000m is outside the zone
        assertFalse(service.isSafeToDeliver(0, 1000f, 0));
    }

    @Test
    void lagOffsetDoesNotExpandZoneEnd() {
        // Melbourne Lakeside Drive zone ends at 1450m.
        // Offset only affects fromMetres, not toMetres.
        // 1500m should still be outside the zone regardless of speed.
        assertFalse(service.isSafeToDeliver(0, 1500f, 250));
    }

    @Test
    void lagOffsetScalesWithSpeed() {
        // Melbourne Lakeside Drive starts at 1100m.
        // At 50 km/h = 13.9 m/s, offset = 20.8m → effective from = 1079.2m
        // 1085m is in the offset zone
        assertTrue(service.isSafeToDeliver(0, 1085f, 50));
        // At 0 km/h: no offset → 1085m is outside zone (starts at 1100m)
        assertFalse(service.isSafeToDeliver(0, 1085f, 0));
    }
}
