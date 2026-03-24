package udp.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DbWriter record construction and batch logic.
 * Integration tests requiring Oracle are in the verification section of todo 10.
 */
class DbWriterTest {

    @Test
    void sessionRecordFields() {
        var s = new DbWriter.Session(
                123456L, 5, 5303.0, 10, 57, 0, 1800.0, 3600.0, 95, 1, 1, 1, 0);
        assertEquals(123456L, s.sessionUid());
        assertEquals(5, s.trackId());
        assertEquals(5303.0, s.trackLengthM());
        assertEquals(57, s.totalLaps());
    }

    @Test
    void participantRecordFields() {
        var p = new DbWriter.Participant(123456L, 0, "Max Verstappen", 1, 1, 5, 0);
        assertEquals("Max Verstappen", p.driverName());
        assertEquals(0, p.aiControlled());
    }

    @Test
    void sectorSnapshotRecordFields() {
        var ss = new DbWriter.SectorSnapshot(
                123456L, 0, 5, 1,
                28500, 85000, 1,
                500, 0,
                0, 0, 0,
                0, 0, 4,
                310.5,
                16, 16, 3,
                5.0, 5.2, 4.8, 5.1,
                0, 0, 0, 0,
                0, 0, 0, 0,
                2, 3, 10,
                0, 0, 0,
                0, 0,
                95, 97, 90, 92,
                100, 102, 98, 99,
                800, 810, 750, 760,
                110,
                45.5, 2.3, 1,
                1, 100.0,
                0, 28, 22, 0,
                0, 500L);
        assertEquals(123456L, ss.sessionUid());
        assertEquals(5, ss.lapNumber());
        assertEquals(1, ss.sectorNumber());
        assertEquals(28500, ss.sectorTimeMs());
        assertEquals(95, ss.tyreSurfaceTempRl());
    }

    @Test
    void eventRecordNullableFields() {
        var e = new DbWriter.Event(123456L, 100L, "SPTP", null, null, null, null);
        assertNull(e.carIndex());
        assertEquals("SPTP", e.eventCode());

        var e2 = new DbWriter.Event(123456L, 200L, "PENA", 5, 10, 3, 12);
        assertEquals(5, e2.carIndex());
        assertEquals(10, e2.penaltySeconds());
    }

    @Test
    void tyreSetRecordFields() {
        var t = new DbWriter.TyreSet(123456L, 0, 2, 16, 16, 15.5, 1, 10, 8, 500, 0);
        assertEquals(2, t.setIndex());
        assertEquals(15.5, t.wear());
        assertEquals(0, t.fitted());
    }

    @Test
    void finalClassificationStintArrays() {
        int[] actual = {16, 17, 18};
        int[] visual = {16, 17, 18};
        int[] endLap = {20, 40, 57};
        var fc = new DbWriter.FinalClassification(
                123456L, 0, 1, 3, 57, 25, 2, 3, 0,
                85000L, 5400.123, 5, 1, 3, actual, visual, endLap);
        assertEquals(3, fc.numTyreStints());
        assertEquals(3, fc.stintActual().length);
        assertEquals(17, fc.stintActual()[1]);
    }

    @Test
    void calibrationCoefficientRecordFields() {
        var cc = new DbWriter.CalibrationCoefficient(
                5, "tyre_deg_rate", "AI", 1, "OLS",
                0.00123, 0.95, 0.87, 0,
                5, 1200, "abc123",
                java.sql.Timestamp.valueOf("2026-03-24 12:00:00"));
        assertEquals("tyre_deg_rate", cc.knobName());
        assertEquals("AI", cc.calibrationRegime());
        assertEquals(0.00123, cc.value(), 0.00001);
    }

    @Test
    void batchListCreation() {
        List<DbWriter.Participant> batch = List.of(
                new DbWriter.Participant(1L, 0, "Driver A", 1, 1, 5, 0),
                new DbWriter.Participant(1L, 1, "Driver B", 2, 2, 10, 1));
        assertEquals(2, batch.size());
    }
}
