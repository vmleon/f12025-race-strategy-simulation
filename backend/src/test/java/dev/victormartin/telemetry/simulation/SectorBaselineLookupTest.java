package dev.victormartin.telemetry.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class SectorBaselineLookupTest {

    @Test
    void nullJdbcReturnsZeroedTriples() {
        SectorBaselineLookup lookup = new SectorBaselineLookup(null);
        SectorBaselineLookup.SectorBaselines b =
                lookup.lookup(4, 16, false, 40.0, 0, 30);
        assertEquals(List.of(0L, 0L, 0L), b.mean());
        assertEquals(List.of(0L, 0L, 0L), b.perfect());
    }
}
