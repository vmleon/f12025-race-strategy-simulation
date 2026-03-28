package udp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DbReader record construction.
 * Integration tests requiring Oracle are in the verification section of todo 10.
 */
class DbReaderTest {

    @Test
    void sectorSnapshotRowFields() {
        var row = new DbReader.SectorSnapshotRow(
                123456L, 0, 5, 1,
                28500, 85000, 1,
                500, 0,
                0, 0, 0,
                0, 0, 4,
                310.5,
                16, 16, 3,
                5.0, 5.2, 4.8, 5.1,
                95, 97, 90, 92,
                100, 102, 98, 99,
                800, 810, 750, 760,
                110,
                45.5, 2.3, 1,
                0, 28, 22,
                10,
                0, 0, 0);
        assertEquals(123456L, row.sessionUid());
        assertEquals(28500, row.sectorTimeMs());
        assertEquals(0, row.outlier());
        assertEquals(0, row.aiControlled());
    }

    @Test
    void sessionRowFields() {
        var row = new DbReader.SessionRow(
                123456L, 5, 5303.0, 10, 57, 0, 95,
                java.sql.Timestamp.valueOf("2026-03-24 12:00:00"));
        assertEquals(5, row.trackId());
        assertEquals(57, row.totalLaps());
    }

    @Test
    void calibrationCoefficientRowWithNulls() {
        var row = new DbReader.CalibrationCoefficientRow(
                1L, 5, "tyre_deg_rate", "AI",
                null, "OLS", 0.00123, null, null, 0,
                5, 1200, "abc123",
                java.sql.Timestamp.valueOf("2026-03-24 12:00:00"));
        assertNull(row.sectorNumber());
        assertNull(row.confidence());
        assertNull(row.score());
    }

    @Test
    void finalClassificationRowFields() {
        var row = new DbReader.FinalClassificationRow(
                123456L, 0, 1, 3, 57, 25, 2, 3, 0,
                85000L, 5400.123, 5, 1, 3);
        assertEquals(1, row.position());
        assertEquals(3, row.gridPosition());
        assertEquals(85000L, row.bestLapTimeMs());
    }
}
