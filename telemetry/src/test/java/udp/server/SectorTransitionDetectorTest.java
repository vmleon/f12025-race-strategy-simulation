package udp.server;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SectorTransitionDetectorTest {

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
        LapData[] laps = buildLapData(0, 1);
        List<SectorTransitionDetector.Transition> transitions = detector.detect(laps);
        assertTrue(transitions.isEmpty(), "First packet should not produce transitions");
    }

    @Test
    void sameSectorNoTransition() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(0, 1)); // init
        List<SectorTransitionDetector.Transition> transitions = detector.detect(buildLapData(0, 1));
        assertTrue(transitions.isEmpty(), "Same sector should not produce transitions");
    }

    @Test
    void sector0To1TransitionDetected() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(0, 1)); // init in sector 0
        List<SectorTransitionDetector.Transition> transitions = detector.detect(buildLapData(1, 1));

        assertEquals(22, transitions.size(), "All 22 cars should transition");
        for (var t : transitions) {
            assertEquals(0, t.completedSector(), "Completed sector should be 0");
            assertEquals(1, t.lapNumber(), "Should be on lap 1");
        }
    }

    @Test
    void fullLapCycleProducesThreeTransitions() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(0, 1)); // init

        // Sector 0 → 1
        var t1 = detector.detect(buildLapData(1, 1));
        assertEquals(22, t1.size());
        assertEquals(0, t1.get(0).completedSector());

        // Sector 1 → 2
        var t2 = detector.detect(buildLapData(2, 1));
        assertEquals(22, t2.size());
        assertEquals(1, t2.get(0).completedSector());

        // Sector 2 → 0 (new lap)
        var t3 = detector.detect(buildLapData(0, 2));
        assertEquals(22, t3.size());
        assertEquals(2, t3.get(0).completedSector());
        assertEquals(1, t3.get(0).lapNumber(), "Completed sector 2 belongs to previous lap");
    }

    @Test
    void captureSnapshotPopulatesAllFields() {
        CarStateTracker state = new CarStateTracker();

        // Set up state
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

        var transition = new SectorTransitionDetector.Transition(0, 0, 5);
        DbWriter.SectorSnapshot snapshot = SectorTransitionDetector.captureSnapshot(
                0x1234L, transition, state, 500L);

        assertEquals(0x1234L, snapshot.sessionUid());
        assertEquals(0, snapshot.carIndex());
        assertEquals(5, snapshot.lapNumber());
        assertEquals(0, snapshot.sectorNumber());
        assertEquals(28000, snapshot.sectorTimeMs()); // sector1 time
        assertEquals(95, snapshot.tyreSurfaceTempRl());
        assertEquals(100, snapshot.tyreInnerTempRl());
        assertEquals(800, snapshot.brakeTempRl());
        assertEquals(110, snapshot.engineTemp());
        assertEquals(0, snapshot.recovered());
        assertEquals(500L, snapshot.frameIdentifier());
    }

    @Test
    void resetClearsState() {
        SectorTransitionDetector detector = new SectorTransitionDetector();
        detector.detect(buildLapData(0, 1)); // init
        detector.reset();
        // After reset, first packet should produce no transitions again
        var transitions = detector.detect(buildLapData(1, 1));
        assertTrue(transitions.isEmpty());
    }
}
