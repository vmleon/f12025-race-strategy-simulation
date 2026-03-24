package udp.server;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class LapDataTest {

    private byte[] buildPacket(java.util.function.Consumer<ByteBuffer> carWriter) {
        int totalSize = PacketHeader.HEADER_SIZE + LapData.NUM_CARS * LapData.SIZE + 2;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Header
        buf.putShort((short) 2025);
        buf.put((byte) 25);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.put((byte) 2); // packetId = LapData
        buf.putLong(0x1234L);
        buf.putFloat(10.0f);
        buf.putInt(50);
        buf.putInt(50);
        buf.put((byte) 0); // playerCarIndex
        buf.put((byte) 255);

        // Write car 0 with provided data
        carWriter.accept(buf);

        return buf.array();
    }

    private void writeCar(ByteBuffer buf, long lastLap, long currentLap,
                          int s1ms, int s1min, int s2ms, int s2min,
                          int lapNum, int sector, int pitStatus, int driverStatus, int resultStatus) {
        buf.putInt((int) lastLap);        // lastLapTimeInMS
        buf.putInt((int) currentLap);     // currentLapTimeInMS
        buf.putShort((short) s1ms);       // sector1TimeMSPart
        buf.put((byte) s1min);            // sector1TimeMinutesPart
        buf.putShort((short) s2ms);       // sector2TimeMSPart
        buf.put((byte) s2min);            // sector2TimeMinutesPart
        buf.putShort((short) 0);          // deltaToCarInFrontMSPart
        buf.put((byte) 0);               // deltaToCarInFrontMinutesPart
        buf.putShort((short) 0);          // deltaToRaceLeaderMSPart
        buf.put((byte) 0);               // deltaToRaceLeaderMinutesPart
        buf.putFloat(1500.0f);            // lapDistance
        buf.putFloat(5000.0f);            // totalDistance
        buf.putFloat(0.0f);              // safetyCarDelta
        buf.put((byte) 1);               // carPosition
        buf.put((byte) lapNum);           // currentLapNum
        buf.put((byte) pitStatus);        // pitStatus
        buf.put((byte) 0);               // numPitStops
        buf.put((byte) sector);           // sector
        buf.put((byte) 0);               // currentLapInvalid
        buf.put((byte) 0);               // penalties
        buf.put((byte) 0);               // totalWarnings
        buf.put((byte) 0);               // cornerCuttingWarnings
        buf.put((byte) 0);               // numUnservedDriveThroughPens
        buf.put((byte) 0);               // numUnservedStopGoPens
        buf.put((byte) 1);               // gridPosition
        buf.put((byte) driverStatus);     // driverStatus
        buf.put((byte) resultStatus);     // resultStatus
        buf.put((byte) 0);               // pitLaneTimerActive
        buf.putShort((short) 0);          // pitLaneTimeInLaneInMS
        buf.putShort((short) 0);          // pitStopTimerInMS
        buf.put((byte) 0);               // pitStopShouldServePen
        buf.putFloat(310.5f);             // speedTrapFastestSpeed
        buf.put((byte) 3);               // speedTrapFastestLap
    }

    @Test
    void parsePlayerLapData() {
        byte[] data = buildPacket(buf ->
                writeCar(buf, 92000, 45000, 28500, 0, 31200, 0, 5, 1, 0, 4, 2));

        LapData[] laps = LapData.parseAll(data, data.length);

        assertNotNull(laps);
        assertEquals(22, laps.length);

        LapData player = laps[0];
        assertEquals(92000, player.lastLapTimeInMS);
        assertEquals(45000, player.currentLapTimeInMS);
        assertEquals(28500, player.sector1TimeMSPart);
        assertEquals(31200, player.sector2TimeMSPart);
        assertEquals(5, player.currentLapNum);
        assertEquals(1, player.sector);
        assertEquals(0, player.pitStatus);
        assertEquals(4, player.driverStatus);
        assertEquals(2, player.resultStatus);
        assertEquals(310.5f, player.speedTrapFastestSpeed);
        assertEquals(3, player.speedTrapFastestLap);
    }

    @Test
    void sectorTimeHelpers() {
        byte[] data = buildPacket(buf ->
                writeCar(buf, 0, 0, 35000, 1, 42000, 1, 1, 0, 0, 4, 2));

        LapData player = LapData.parseAll(data, data.length)[0];
        assertEquals(95000, player.sector1TimeInMS()); // 1*60000 + 35000
        assertEquals(102000, player.sector2TimeInMS()); // 1*60000 + 42000
    }

    @Test
    void tooSmallReturnsNull() {
        byte[] data = new byte[100];
        assertNull(LapData.parseAll(data, data.length));
    }
}
