package udp.server;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for all packet parsers added in todo 11.
 */
class PacketParsersTest {

    private ByteBuffer allocateWithHeader(int totalSize, int packetId) {
        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        // Header
        buf.putShort((short) 2025);
        buf.put((byte) 25);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.put((byte) packetId);
        buf.putLong(0x1234L);
        buf.putFloat(10.0f);
        buf.putInt(50);
        buf.putInt(50);
        buf.put((byte) 0);
        buf.put((byte) 255);
        return buf;
    }

    // ── SessionData (packetId=1) ────────────────────────────────────────

    @Test
    void parseSessionData() {
        ByteBuffer buf = allocateWithHeader(753, 1);
        // weather=2, trackTemp=30, airTemp=22, totalLaps=57, trackLength=5303
        buf.put((byte) 2);       // weather
        buf.put((byte) 30);      // trackTemperature
        buf.put((byte) 22);      // airTemperature
        buf.put((byte) 57);      // totalLaps
        buf.putShort((short) 5303); // trackLength
        buf.put((byte) 10);      // sessionType (race)
        buf.put((byte) 5);       // trackId
        buf.put((byte) 0);       // formula
        // Fill remaining with zeros (enough for the parser to skip through)

        byte[] data = buf.array();
        SessionData session = SessionData.parse(data, data.length);

        assertNotNull(session);
        assertEquals(2, session.weather);
        assertEquals(30, session.trackTemperature);
        assertEquals(22, session.airTemperature);
        assertEquals(57, session.totalLaps);
        assertEquals(5303, session.trackLength);
        assertEquals(10, session.sessionType);
        assertEquals(5, session.trackId);
        assertEquals(0, session.formula);
    }

    @Test
    void sessionDataTooSmallReturnsNull() {
        assertNull(SessionData.parse(new byte[100], 100));
    }

    // ── CarStatusData (packetId=7) ──────────────────────────────────────

    @Test
    void parseCarStatusData() {
        int totalSize = PacketHeader.HEADER_SIZE + CarStatusData.NUM_CARS * CarStatusData.SIZE;
        ByteBuffer buf = allocateWithHeader(totalSize, 7);

        // Write car 0
        buf.put((byte) 0);       // tractionControl
        buf.put((byte) 1);       // antiLockBrakes
        buf.put((byte) 1);       // fuelMix
        buf.put((byte) 56);      // frontBrakeBias
        buf.put((byte) 0);       // pitLimiterStatus
        buf.putFloat(45.5f);     // fuelInTank
        buf.putFloat(110.0f);    // fuelCapacity
        buf.putFloat(2.3f);      // fuelRemainingLaps
        buf.putShort((short) 12500); // maxRPM
        buf.putShort((short) 4000);  // idleRPM
        buf.put((byte) 8);       // maxGears
        buf.put((byte) 1);       // drsAllowed
        buf.putShort((short) 150); // drsActivationDistance
        buf.put((byte) 16);      // actualTyreCompound (C5)
        buf.put((byte) 16);      // visualTyreCompound (soft)
        buf.put((byte) 5);       // tyresAgeLaps
        buf.put((byte) 0);       // vehicleFIAFlags
        buf.putFloat(500000.0f); // enginePowerICE
        buf.putFloat(120000.0f); // enginePowerMGUK
        buf.putFloat(4000000.0f); // ersStoreEnergy
        buf.put((byte) 1);       // ersDeployMode
        buf.putFloat(100000.0f); // ersHarvestedThisLapMGUK
        buf.putFloat(200000.0f); // ersHarvestedThisLapMGUH
        buf.putFloat(150000.0f); // ersDeployedThisLap
        buf.put((byte) 0);       // networkPaused

        byte[] data = buf.array();
        CarStatusData[] cars = CarStatusData.parseAll(data, data.length);

        assertNotNull(cars);
        assertEquals(22, cars.length);

        CarStatusData player = cars[0];
        assertEquals(45.5f, player.fuelInTank, 0.01f);
        assertEquals(2.3f, player.fuelRemainingLaps, 0.01f);
        assertEquals(1, player.drsAllowed);
        assertEquals(150, player.drsActivationDistance);
        assertEquals(16, player.actualTyreCompound);
        assertEquals(5, player.tyresAgeLaps);
        assertEquals(1, player.ersDeployMode);
    }

    @Test
    void carStatusTooSmallReturnsNull() {
        assertNull(CarStatusData.parseAll(new byte[100], 100));
    }

    // ── CarDamageData (packetId=10) ─────────────────────────────────────

    @Test
    void parseCarDamageData() {
        int totalSize = PacketHeader.HEADER_SIZE + CarDamageData.NUM_CARS * CarDamageData.SIZE;
        ByteBuffer buf = allocateWithHeader(totalSize, 10);

        // Write car 0
        buf.putFloat(5.0f); buf.putFloat(5.2f); buf.putFloat(4.8f); buf.putFloat(5.1f); // tyresWear
        buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0); // tyresDamage
        buf.put((byte) 2); buf.put((byte) 3); buf.put((byte) 1); buf.put((byte) 2); // brakesDamage
        buf.put((byte) 10); buf.put((byte) 12); buf.put((byte) 8); buf.put((byte) 11); // tyreBlisters
        buf.put((byte) 5);  // frontLeftWingDamage
        buf.put((byte) 3);  // frontRightWingDamage
        buf.put((byte) 0);  // rearWingDamage
        buf.put((byte) 0);  // floorDamage
        buf.put((byte) 0);  // diffuserDamage
        buf.put((byte) 0);  // sidepodDamage
        buf.put((byte) 0);  // drsFault
        buf.put((byte) 0);  // ersFault
        buf.put((byte) 10); // gearBoxDamage
        buf.put((byte) 5);  // engineDamage
        buf.put((byte) 15); buf.put((byte) 10); buf.put((byte) 8); // engineWear MGUH/ES/CE
        buf.put((byte) 12); buf.put((byte) 9); buf.put((byte) 7);  // engineWear ICE/MGUK/TC
        buf.put((byte) 0);  // engineBlown
        buf.put((byte) 0);  // engineSeized

        byte[] data = buf.array();
        CarDamageData[] cars = CarDamageData.parseAll(data, data.length);

        assertNotNull(cars);
        CarDamageData player = cars[0];
        assertEquals(5.0f, player.tyresWear[0], 0.01f);
        assertArrayEquals(new int[]{10, 12, 8, 11}, player.tyreBlisters);
        assertEquals(5, player.frontLeftWingDamage);
        assertEquals(10, player.gearBoxDamage);
        assertEquals(5, player.engineDamage);
    }

    // ── ParticipantData (packetId=4) ────────────────────────────────────

    @Test
    void parseParticipantData() {
        int totalSize = PacketHeader.HEADER_SIZE + 1 + ParticipantData.NUM_CARS * ParticipantData.SIZE;
        ByteBuffer buf = allocateWithHeader(totalSize, 4);

        buf.put((byte) 20); // numActiveCars

        // Write participant 0
        buf.put((byte) 0);  // aiControlled (human)
        buf.put((byte) 1);  // driverId
        buf.put((byte) 0);  // networkId
        buf.put((byte) 1);  // teamId (Red Bull)
        buf.put((byte) 0);  // myTeam
        buf.put((byte) 1);  // raceNumber
        buf.put((byte) 5);  // nationality
        // name (32 bytes, null-terminated)
        byte[] name = "Max Verstappen".getBytes(StandardCharsets.UTF_8);
        buf.put(name);
        buf.position(buf.position() + (32 - name.length)); // pad to 32
        buf.put((byte) 1);  // yourTelemetry
        buf.put((byte) 1);  // showOnlineNames
        buf.putShort((short) 0); // techLevel
        buf.put((byte) 1);  // platform
        buf.put((byte) 0);  // numColours
        buf.position(buf.position() + 12); // liveryColours

        byte[] data = buf.array();
        ParticipantData[] participants = ParticipantData.parseAll(data, data.length);

        assertNotNull(participants);
        assertEquals(22, participants.length);
        assertEquals("Max Verstappen", participants[0].name);
        assertEquals(0, participants[0].aiControlled);
        assertEquals(1, participants[0].teamId);
        assertEquals(1, participants[0].raceNumber);
        assertEquals(5, participants[0].nationality);

        assertEquals(20, ParticipantData.parseNumActiveCars(data, data.length));
    }

    // ── EventData (packetId=3) ──────────────────────────────────────────

    @Test
    void parseEventFastestLap() {
        ByteBuffer buf = allocateWithHeader(45, 3);
        buf.put("FTLP".getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 5);     // vehicleIdx
        buf.putFloat(85.123f); // lapTime

        byte[] data = buf.array();
        EventData event = EventData.parse(data, data.length);

        assertNotNull(event);
        assertEquals("FTLP", event.eventCode);
        assertEquals(5, event.vehicleIdx);
        assertEquals(85.123f, event.lapTime, 0.001f);
    }

    @Test
    void parseEventPenalty() {
        ByteBuffer buf = allocateWithHeader(45, 3);
        buf.put("PENA".getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 1);  // penaltyType
        buf.put((byte) 3);  // infringementType
        buf.put((byte) 5);  // vehicleIdx
        buf.put((byte) 10); // otherVehicleIdx
        buf.put((byte) 5);  // time
        buf.put((byte) 12); // lapNum
        buf.put((byte) 0);  // placesGained

        byte[] data = buf.array();
        EventData event = EventData.parse(data, data.length);

        assertEquals("PENA", event.eventCode);
        assertEquals(1, event.penaltyType);
        assertEquals(5, event.vehicleIdx);
        assertEquals(10, event.otherVehicleIdx);
        assertEquals(5, event.time);
        assertEquals(12, event.lapNum);
    }

    @Test
    void parseEventSafetyCar() {
        ByteBuffer buf = allocateWithHeader(45, 3);
        buf.put("SCAR".getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 1); // safetyCarType (full)
        buf.put((byte) 0); // eventType (deployed)

        byte[] data = buf.array();
        EventData event = EventData.parse(data, data.length);

        assertEquals("SCAR", event.eventCode);
        assertEquals(1, event.safetyCarType);
        assertEquals(0, event.eventType);
    }

    @Test
    void parseEventSessionStarted() {
        ByteBuffer buf = allocateWithHeader(45, 3);
        buf.put("SSTA".getBytes(StandardCharsets.US_ASCII));

        byte[] data = buf.array();
        EventData event = EventData.parse(data, data.length);

        assertNotNull(event);
        assertEquals("SSTA", event.eventCode);
    }

    // ── FinalClassificationData (packetId=8) ────────────────────────────

    @Test
    void parseFinalClassificationData() {
        int totalSize = PacketHeader.HEADER_SIZE + 1 + FinalClassificationData.NUM_CARS * FinalClassificationData.SIZE;
        ByteBuffer buf = allocateWithHeader(totalSize, 8);

        buf.put((byte) 20); // numCars

        // Write car 0
        buf.put((byte) 1);      // position
        buf.put((byte) 57);     // numLaps
        buf.put((byte) 3);      // gridPosition
        buf.put((byte) 25);     // points
        buf.put((byte) 2);      // numPitStops
        buf.put((byte) 3);      // resultStatus (finished)
        buf.put((byte) 2);      // resultReason (finished)
        buf.putInt(85000);       // bestLapTimeInMS
        buf.putDouble(5400.123); // totalRaceTime
        buf.put((byte) 5);      // penaltiesTime
        buf.put((byte) 1);      // numPenalties
        buf.put((byte) 3);      // numTyreStints
        // tyreStintsActual[8]
        buf.put((byte) 16); buf.put((byte) 17); buf.put((byte) 18);
        buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0);
        // tyreStintsVisual[8]
        buf.put((byte) 16); buf.put((byte) 17); buf.put((byte) 18);
        buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0);
        // tyreStintsEndLaps[8]
        buf.put((byte) 20); buf.put((byte) 40); buf.put((byte) 57);
        buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0);

        byte[] data = buf.array();
        FinalClassificationData[] cars = FinalClassificationData.parseAll(data, data.length);

        assertNotNull(cars);
        FinalClassificationData winner = cars[0];
        assertEquals(1, winner.position);
        assertEquals(57, winner.numLaps);
        assertEquals(25, winner.points);
        assertEquals(3, winner.resultStatus);
        assertEquals(85000, winner.bestLapTimeInMS);
        assertEquals(5400.123, winner.totalRaceTime, 0.001);
        assertEquals(3, winner.numTyreStints);
        assertEquals(16, winner.tyreStintsActual[0]);
        assertEquals(40, winner.tyreStintsEndLaps[1]);
    }

    // ── TyreSetData (packetId=12) ──────────────────────────────────────

    @Test
    void parseTyreSetData() {
        int totalSize = PacketHeader.HEADER_SIZE + 1 + TyreSetData.MAX_TYRE_SETS * TyreSetData.SIZE + 1;
        ByteBuffer buf = allocateWithHeader(totalSize, 12);

        buf.put((byte) 0); // carIdx

        // Write set 0
        buf.put((byte) 16);     // actualTyreCompound (C5)
        buf.put((byte) 16);     // visualTyreCompound (soft)
        buf.put((byte) 15);     // wear
        buf.put((byte) 1);      // available
        buf.put((byte) 10);     // recommendedSession
        buf.put((byte) 20);     // lifeSpan
        buf.put((byte) 25);     // usableLife
        buf.putShort((short) -500); // lapDeltaTime
        buf.put((byte) 1);      // fitted

        // Fill remaining 19 sets
        for (int i = 1; i < 20; i++) {
            for (int j = 0; j < TyreSetData.SIZE; j++) buf.put((byte) 0);
        }
        buf.put((byte) 0); // fittedIdx

        byte[] data = buf.array();
        TyreSetData.TyreSetPacket packet = TyreSetData.parse(data, data.length);

        assertNotNull(packet);
        assertEquals(0, packet.carIdx());
        assertEquals(0, packet.fittedIdx());
        TyreSetData set0 = packet.sets()[0];
        assertEquals(16, set0.actualTyreCompound);
        assertEquals(15, set0.wear);
        assertEquals(1, set0.available);
        assertEquals(20, set0.lifeSpan);
        assertEquals(-500, set0.lapDeltaTime);
        assertEquals(1, set0.fitted);
    }

    // ── SessionHistoryData (packetId=11) ────────────────────────────────

    @Test
    void parseSessionHistoryData() {
        int totalSize = PacketHeader.HEADER_SIZE + 7 +
                SessionHistoryData.MAX_LAPS * SessionHistoryData.LAP_HISTORY_SIZE +
                8 * SessionHistoryData.TYRE_STINT_SIZE;
        ByteBuffer buf = allocateWithHeader(totalSize, 11);

        buf.put((byte) 3);  // carIdx
        buf.put((byte) 5);  // numLaps
        buf.put((byte) 2);  // numTyreStints
        buf.put((byte) 3);  // bestLapTimeLapNum
        buf.put((byte) 2);  // bestSector1LapNum
        buf.put((byte) 4);  // bestSector2LapNum
        buf.put((byte) 3);  // bestSector3LapNum

        // Write lap 0 history
        buf.putInt(85000);          // lapTimeInMS
        buf.putShort((short) 28000); // sector1TimeMSPart
        buf.put((byte) 0);          // sector1TimeMinutesPart
        buf.putShort((short) 30000); // sector2TimeMSPart
        buf.put((byte) 0);          // sector2TimeMinutesPart
        buf.putShort((short) 27000); // sector3TimeMSPart
        buf.put((byte) 0);          // sector3TimeMinutesPart
        buf.put((byte) 0x0F);       // lapValidBitFlags (all valid)

        byte[] data = buf.array();
        SessionHistoryData history = SessionHistoryData.parse(data, data.length);

        assertNotNull(history);
        assertEquals(3, history.carIdx);
        assertEquals(5, history.numLaps);
        assertEquals(3, history.bestLapTimeLapNum);

        SessionHistoryData.LapHistory lap0 = history.lapHistories[0];
        assertEquals(85000, lap0.lapTimeInMS());
        assertEquals(28000, lap0.sector1TimeInMS());
        assertEquals(30000, lap0.sector2TimeInMS());
        assertEquals(27000, lap0.sector3TimeInMS());
        assertTrue(lap0.isLapValid());
        assertTrue(lap0.isSector1Valid());
        assertTrue(lap0.isSector2Valid());
        assertTrue(lap0.isSector3Valid());
    }

    @Test
    void sessionHistoryTooSmallReturnsNull() {
        assertNull(SessionHistoryData.parse(new byte[100], 100));
    }
}
