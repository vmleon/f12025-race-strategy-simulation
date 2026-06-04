package dev.victormartin.telemetry.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SectorHistoryLookupTest {

    @Test
    void nullJdbcReturnsEmptyAndZero() {
        SectorHistoryLookup lookup = new SectorHistoryLookup(null);
        assertTrue(lookup.recent(4, 0, 16, 0).isEmpty());
        List<List<Long>> all = lookup.recentBySector(4, 0, 16);
        assertEquals(3, all.size());
        assertTrue(all.get(0).isEmpty());
        assertEquals(0, lookup.lapsRecorded(4, 0));
    }
}
