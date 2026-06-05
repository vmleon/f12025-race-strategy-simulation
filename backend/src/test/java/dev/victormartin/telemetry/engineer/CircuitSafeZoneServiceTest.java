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
    void gateOpensEarlierOnTheStraightAtRacingSpeed() {
        // Lakeside Drive straight starts at 1065m. At 250 km/h the gate should
        // open well before the zone start so a message clears before braking —
        // 900m is now deliverable (it was not under the previous shorter lag).
        assertTrue(service.isSafeToDeliver(0, 900f, 250));
    }

    @Test
    void lagOffsetDoesNotExpandZoneEnd() {
        // Melbourne Lakeside Drive zone ends at 1450m.
        // Offset only affects fromMetres, not toMetres.
        // 1500m should still be outside the zone regardless of speed.
        assertFalse(service.isSafeToDeliver(0, 1500f, 250));
    }

    @Test
    void currentZoneIndexReturnsCorrectZone() {
        // Melbourne: 200m is in zone 0 (0-300m)
        assertEquals(0, service.currentZoneIndex(0, 200f, 0));
        // Melbourne: 1200m is in zone 1 (1100-1450m)
        assertEquals(1, service.currentZoneIndex(0, 1200f, 0));
        // Melbourne: 2800m is in zone 2 (2700-3050m)
        assertEquals(2, service.currentZoneIndex(0, 2800f, 0));
        // Melbourne: 4500m is in zone 3 (4400-4700m)
        assertEquals(3, service.currentZoneIndex(0, 4500f, 0));
    }

    @Test
    void currentZoneIndexReturnsMinusOneOutsideZones() {
        // Melbourne: 500m is between zone 0 and 1
        assertEquals(-1, service.currentZoneIndex(0, 500f, 0));
    }

    @Test
    void currentZoneIndexAppliesLagOffset() {
        // Melbourne zone 1 starts at 1100m.
        // At 250 km/h, offset = 104.2m, effective from = 995.8m
        // 1000m should be in zone 1 with speed, but -1 without
        assertEquals(1, service.currentZoneIndex(0, 1000f, 250));
        assertEquals(-1, service.currentZoneIndex(0, 1000f, 0));
    }

    @Test
    void currentZoneIndexReturnsPermissiveFallbackForUnknownTrack() {
        // Unconfigured tracks should allow delivery (consistent with isSafeToDeliver)
        assertTrue(service.currentZoneIndex(999, 100f, 0) >= 0,
                "Unconfigured tracks should return a non-negative zone index");
    }

    @Test
    void lagOffsetScalesWithSpeed() {
        // Melbourne Lakeside Drive starts at 1065m.
        // At 50 km/h = 13.9 m/s, offset = 20.8m → effective from = 1044.2m
        // 1050m is in the offset zone
        assertTrue(service.isSafeToDeliver(0, 1050f, 50));
        // At 0 km/h: no offset → 1050m is outside zone (starts at 1065m)
        assertFalse(service.isSafeToDeliver(0, 1050f, 0));
    }
}
