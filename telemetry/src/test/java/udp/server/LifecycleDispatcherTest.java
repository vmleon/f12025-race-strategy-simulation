package udp.server;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleDispatcherTest {

    // ── Pit stop detection ───────────────────────────────────────────────

    @Test
    void detectPitStops_triggersOnTransitionFromZero() {
        var dispatcher = createDispatcher();
        LapData[] laps = buildLapDataWithPitStatus(new int[]{0, 0, 0});

        // First call — all zero, no triggers
        boolean[] result = dispatcher.detectPitStops(laps);
        assertFalse(result[0]);
        assertFalse(result[1]);
        assertFalse(result[2]);

        // Second call — car 1 enters pit (status 1)
        laps = buildLapDataWithPitStatus(new int[]{0, 1, 0});
        result = dispatcher.detectPitStops(laps);
        assertFalse(result[0]);
        assertTrue(result[1]);
        assertFalse(result[2]);
    }

    @Test
    void detectPitStops_doesNotRetriggerWhileStillInPit() {
        var dispatcher = createDispatcher();

        // Car 0 enters pit
        dispatcher.detectPitStops(buildLapDataWithPitStatus(new int[]{1, 0, 0}));

        // Car 0 still in pit — should not re-trigger
        boolean[] result = dispatcher.detectPitStops(buildLapDataWithPitStatus(new int[]{1, 0, 0}));
        assertFalse(result[0]);
    }

    @Test
    void detectPitStops_retriggersAfterLeavingAndReentering() {
        var dispatcher = createDispatcher();

        // Enter pit
        dispatcher.detectPitStops(buildLapDataWithPitStatus(new int[]{1, 0, 0}));
        // Leave pit
        dispatcher.detectPitStops(buildLapDataWithPitStatus(new int[]{0, 0, 0}));
        // Re-enter pit
        boolean[] result = dispatcher.detectPitStops(buildLapDataWithPitStatus(new int[]{2, 0, 0}));
        assertTrue(result[0]);
    }

    // ── Session deduplication ────────────────────────────────────────────

    @Test
    void seenSessions_deduplicates() {
        var dispatcher = createDispatcher();
        assertTrue(dispatcher.getSeenSessions().isEmpty());

        // Simulate marking a session as seen
        dispatcher.getSeenSessions().add(100L);
        assertFalse(dispatcher.getSeenSessions().add(100L)); // duplicate
        assertTrue(dispatcher.getSeenSessions().add(200L));   // new
    }

    @Test
    void seenParticipants_deduplicates() {
        var dispatcher = createDispatcher();
        assertTrue(dispatcher.getSeenParticipants().add(100L));
        assertFalse(dispatcher.getSeenParticipants().add(100L));
    }

    @Test
    void seenFinalClassifications_deduplicates() {
        var dispatcher = createDispatcher();
        assertTrue(dispatcher.getSeenFinalClassifications().add(100L));
        assertFalse(dispatcher.getSeenFinalClassifications().add(100L));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private LifecycleDispatcher createDispatcher() {
        // ConnectionFactory and DbWriter are not exercised in pure logic tests
        return new LifecycleDispatcher(null, null);
    }

    /**
     * Build minimal LapData array with specific pitStatus values for the first N cars.
     */
    private LapData[] buildLapDataWithPitStatus(int[] pitStatuses) {
        int numCars = 22;
        int totalSize = PacketHeader.HEADER_SIZE + numCars * LapData.SIZE + 2;
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

        // 22 cars × 57 bytes each
        for (int car = 0; car < numCars; car++) {
            int startPos = buf.position();
            buf.putInt(0);  // lastLapTimeInMS
            buf.putInt(0);  // currentLapTimeInMS
            buf.putShort((short) 0); // sector1TimeMSPart
            buf.put((byte) 0);      // sector1TimeMinutesPart
            buf.putShort((short) 0); // sector2TimeMSPart
            buf.put((byte) 0);      // sector2TimeMinutesPart
            buf.putShort((short) 0); // deltaToCarInFrontMSPart
            buf.put((byte) 0);      // deltaToCarInFrontMinutesPart
            buf.putShort((short) 0); // deltaToRaceLeaderMSPart
            buf.put((byte) 0);      // deltaToRaceLeaderMinutesPart
            buf.putFloat(0);  // lapDistance
            buf.putFloat(0);  // totalDistance
            buf.putFloat(0);  // safetyCarDelta
            buf.put((byte) (car + 1)); // carPosition
            buf.put((byte) 1);  // currentLapNum
            int pitStatus = car < pitStatuses.length ? pitStatuses[car] : 0;
            buf.put((byte) pitStatus); // pitStatus
            buf.put((byte) 0);  // numPitStops
            buf.put((byte) 0);  // sector
            buf.put((byte) 0);  // currentLapInvalid
            buf.put((byte) 0);  // penalties
            buf.put((byte) 0);  // totalWarnings
            buf.put((byte) 0);  // cornerCuttingWarnings
            buf.put((byte) 0);  // numUnservedDriveThroughPens
            buf.put((byte) 0);  // numUnservedStopGoPens
            buf.put((byte) (car + 1)); // gridPosition
            buf.put((byte) 0);  // driverStatus
            buf.put((byte) 0);  // resultStatus
            buf.put((byte) 0);  // pitLaneTimerActive
            buf.putShort((short) 0); // pitLaneTimeInLaneInMS
            buf.putShort((short) 0); // pitStopTimerInMS
            buf.put((byte) 0);  // pitStopShouldServePen
            buf.putFloat(0);    // speedTrapFastestSpeed
            buf.put((byte) 0);  // speedTrapFastestLap
            // Verify we wrote exactly 57 bytes
            assert buf.position() - startPos == LapData.SIZE;
        }

        // 2 bytes for time trial indices
        buf.putShort((short) 0);

        return LapData.parseAll(buf.array(), buf.position());
    }
}
