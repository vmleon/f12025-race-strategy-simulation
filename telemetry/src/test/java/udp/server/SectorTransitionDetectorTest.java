package udp.server;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SectorTransitionDetectorTest {

    private final SessionHistoryBuffer historyBuffer = new SessionHistoryBuffer();

    private LapData[] buildLapData(int sector, int lapNum) {
        int totalSize = PacketHeader.HEADER_SIZE + LapData.NUM_CARS * LapData.SIZE + 2;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Header
        buf.putShort((short) 2025);
        buf.put((byte) 25);
        buf.put(new byte[4]); // versions + packetId
        buf.putLong(0x1234L);
        buf.putFloat(10.0f);
        buf.putInt(50);
        buf.putInt(50);
        buf.put((byte) 0);
        buf.put((byte) 255);

        // Write 22 cars all with same sector/lap
        for (int car = 0; car < 22; car++) {
            buf.putInt(85000);              // lastLapTimeInMS
            buf.putInt(30000);              // currentLapTimeInMS
            buf.putShort((short) 28000);    // sector1TimeMSPart
            buf.put((byte) 0);              // sector1TimeMinutesPart
            buf.putShort((short) 30000);    // sector2TimeMSPart
            buf.put((byte) 0);              // sector2TimeMinutesPart
            buf.putShort((short) 0);        // deltaToCarInFrontMSPart
            buf.put((byte) 0);              // deltaToCarInFrontMinutesPart
            buf.putShort((short) 0);        // deltaToRaceLeaderMSPart
            buf.put((byte) 0);              // deltaToRaceLeaderMinutesPart
            buf.putFloat(1500.0f);          // lapDistance
            buf.putFloat(5000.0f);          // totalDistance
            buf.putFloat(0.0f);             // safetyCarDelta
            buf.put((byte) (car + 1));      // carPosition
            buf.put((byte) lapNum);         // currentLapNum
            buf.put((byte) 0);              // pitStatus
            buf.put((byte) 0);              // numPitStops
            buf.put((byte) sector);         // sector
            buf.put((byte) 0);              // currentLapInvalid
            buf.put((byte) 0);              // penalties
            buf.put((byte) 0);              // totalWarnings
            buf.put((byte) 0);              // cornerCuttingWarnings
            buf.put((byte) 0);              // numUnservedDriveThroughPens
            buf.put((byte) 0);              // numUnservedStopGoPens
            buf.put((byte) (car + 1));      // gridPosition
            buf.put((byte) 4);              // driverStatus
            buf.put((byte) 2);              // resultStatus
            buf.put((byte) 0);              // pitLaneTimerActive
            buf.putShort((short) 0);        // pitLaneTimeInLaneInMS
            buf.putShort((short) 0);        // pitStopTimerInMS
            buf.put((byte) 0);              // pitStopShouldServePen
            buf.putFloat(310.0f);           // speedTrapFastestSpeed
            buf.put((byte) 1);              // speedTrapFastestLap
        }

        return LapData.parseAll(buf.array(), buf.array().length);
    }

    @Test
    void firstPacketProducesNoTransitions() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        var transitions = detector.detect(buildLapData(0, 1), historyBuffer);
        assertTrue(transitions.isEmpty());
    }

    @Test
    void sameSectorNoTransition() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(0, 1), historyBuffer);
        var transitions = detector.detect(buildLapData(0, 1), historyBuffer);
        assertTrue(transitions.isEmpty());
    }

    @Test
    void sector0To1TransitionDetected() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(0, 1), historyBuffer);
        var transitions = detector.detect(buildLapData(1, 1), historyBuffer);

        assertEquals(22, transitions.size());
        for (var t : transitions) {
            assertEquals(0, t.completedSector());
            assertEquals(1, t.lapNumber());
            assertEquals(SectorTransitionDetector.RECOVERED_PRIMARY, t.recovered());
        }
    }

    @Test
    void fullLapCycleProducesThreeTransitions() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(0, 1), historyBuffer);

        var t1 = detector.detect(buildLapData(1, 1), historyBuffer);
        assertEquals(22, t1.size());
        assertEquals(0, t1.get(0).completedSector());

        var t2 = detector.detect(buildLapData(2, 1), historyBuffer);
        assertEquals(22, t2.size());
        assertEquals(1, t2.get(0).completedSector());

        var t3 = detector.detect(buildLapData(0, 2), historyBuffer);
        assertEquals(22, t3.size());
        assertEquals(2, t3.get(0).completedSector());
        assertEquals(1, t3.get(0).lapNumber());
    }

    // ── Tier 1 Recovery: Sector jump detected, cumulative times available ──

    @Test
    void tier1RecoveryOnSkippedSector() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(0, 1), historyBuffer); // init at sector 0

        // Jump from sector 0 → sector 2 (skipped sector 1 transition)
        var transitions = detector.detect(buildLapData(2, 1), historyBuffer);

        // Should produce 2 transitions: sector 0 (primary) and sector 1 (recovered)
        assertEquals(44, transitions.size()); // 22 cars × 2 transitions each

        // Check car 0's transitions
        var car0Primary = transitions.stream()
                .filter(t -> t.carIndex() == 0 && t.completedSector() == 0).findFirst().orElseThrow();
        assertEquals(SectorTransitionDetector.RECOVERED_PRIMARY, car0Primary.recovered());

        var car0Recovered = transitions.stream()
                .filter(t -> t.carIndex() == 0 && t.completedSector() == 1).findFirst().orElseThrow();
        // Sector 1 has cumulative time (30000ms) available in LapData → Tier 1
        assertEquals(SectorTransitionDetector.RECOVERED_TIER1, car0Recovered.recovered());
    }

    // ── Tier 3 Recovery: No data available → gap flag ──

    @Test
    void tier3GapFlagWhenSector2SkippedAndNoHistory() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(1, 1), historyBuffer); // init at sector 1

        // Jump from sector 1 → sector 0 on next lap (skipped sector 2)
        // sector2 has no cumulative time in LapData and no SessionHistory → Tier 3
        var transitions = detector.detect(buildLapData(0, 2), historyBuffer);

        // Should produce 2 transitions per car: sector 1 (primary) and sector 2 (recovered)
        assertEquals(44, transitions.size());

        var car0Sector2 = transitions.stream()
                .filter(t -> t.carIndex() == 0 && t.completedSector() == 2).findFirst().orElseThrow();
        // Sector 2 has no cumulative LapData time (only sector1/sector2 times exist) → Tier 3
        assertEquals(SectorTransitionDetector.RECOVERED_TIER3, car0Sector2.recovered());
    }

    // ── Snapshot capture ──

    @Test
    void captureSnapshotPopulatesAllFields() {
        CarStateTracker state = new CarStateTracker();
        state.updateLapData(buildLapData(1, 5));

        int totalTelSize = PacketHeader.HEADER_SIZE + CarTelemetryData.NUM_CARS * CarTelemetryData.SIZE + 3;
        ByteBuffer telBuf = ByteBuffer.allocate(totalTelSize);
        telBuf.order(ByteOrder.LITTLE_ENDIAN);
        telBuf.position(PacketHeader.HEADER_SIZE);
        for (int c = 0; c < 22; c++) {
            telBuf.putShort((short) 285);
            telBuf.putFloat(0.8f);
            telBuf.putFloat(0.0f);
            telBuf.putFloat(0.0f);
            telBuf.put((byte) 0);
            telBuf.put((byte) 7);
            telBuf.putShort((short) 11500);
            telBuf.put((byte) 0);
            telBuf.put((byte) 75);
            telBuf.putShort((short) 0);
            for (int i = 0; i < 4; i++) telBuf.putShort((short) 800);
            for (int i = 0; i < 4; i++) telBuf.put((byte) 95);
            for (int i = 0; i < 4; i++) telBuf.put((byte) 100);
            telBuf.putShort((short) 110);
            for (int i = 0; i < 4; i++) telBuf.putFloat(23.0f);
            for (int i = 0; i < 4; i++) telBuf.put((byte) 0);
        }
        state.updateTelemetry(CarTelemetryData.parseAll(telBuf.array(), telBuf.array().length));

        var transition = new SectorTransitionDetector.Transition(0, 0, 5, SectorTransitionDetector.RECOVERED_PRIMARY);
        DbWriter.SectorSnapshot snapshot = SectorTransitionDetector.captureSnapshot(
                0x1234L, transition, state, historyBuffer, 500L);

        assertEquals(0x1234L, snapshot.sessionUid());
        assertEquals(0, snapshot.carIndex());
        assertEquals(5, snapshot.lapNumber());
        assertEquals(0, snapshot.sectorNumber());
        assertEquals(28000, snapshot.sectorTimeMs());
        assertEquals(95, snapshot.tyreSurfaceTempRl());
        assertEquals(100, snapshot.tyreInnerTempRl());
        assertEquals(800, snapshot.brakeTempRl());
        assertEquals(110, snapshot.engineTemp());
        assertEquals(0, snapshot.recovered());
        assertEquals(500L, snapshot.frameIdentifier());
    }

    @Test
    void tier3SnapshotHasZeroDataFields() {
        CarStateTracker state = new CarStateTracker();
        state.updateLapData(buildLapData(0, 3));

        var transition = new SectorTransitionDetector.Transition(0, 2, 3, SectorTransitionDetector.RECOVERED_TIER3);
        DbWriter.SectorSnapshot snapshot = SectorTransitionDetector.captureSnapshot(
                0x1234L, transition, state, historyBuffer, 100L);

        assertEquals(3, snapshot.recovered());
        // Tier 3 nullifies telemetry/status/damage fields
        assertEquals(0, snapshot.tyreSurfaceTempRl());
        assertEquals(0, snapshot.tyreInnerTempRl());
        assertEquals(0, snapshot.brakeTempRl());
    }

    @Test
    void resetClearsState() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(0, 1), historyBuffer);
        detector.reset();
        var transitions = detector.detect(buildLapData(1, 1), historyBuffer);
        assertTrue(transitions.isEmpty());
    }
}
