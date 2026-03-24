package udp.server;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class SessionHistoryBufferTest {

    private SessionHistoryData buildHistory(int carIdx, int numLaps, long[][] sectorTimes) {
        int totalSize = PacketHeader.HEADER_SIZE + 7 +
                SessionHistoryData.MAX_LAPS * SessionHistoryData.LAP_HISTORY_SIZE +
                8 * SessionHistoryData.TYRE_STINT_SIZE;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);

        buf.put((byte) carIdx);
        buf.put((byte) numLaps);
        buf.put((byte) 1);  // numTyreStints
        buf.put((byte) 0);  // bestLapTimeLapNum
        buf.put((byte) 0);  // bestSector1LapNum
        buf.put((byte) 0);  // bestSector2LapNum
        buf.put((byte) 0);  // bestSector3LapNum

        for (int lap = 0; lap < SessionHistoryData.MAX_LAPS; lap++) {
            if (lap < sectorTimes.length) {
                long lapTime = sectorTimes[lap][0] + sectorTimes[lap][1] + sectorTimes[lap][2];
                buf.putInt((int) lapTime);
                buf.putShort((short) sectorTimes[lap][0]); buf.put((byte) 0); // s1
                buf.putShort((short) sectorTimes[lap][1]); buf.put((byte) 0); // s2
                buf.putShort((short) sectorTimes[lap][2]); buf.put((byte) 0); // s3
                buf.put((byte) 0x0F); // all valid
            } else {
                for (int i = 0; i < SessionHistoryData.LAP_HISTORY_SIZE; i++) buf.put((byte) 0);
            }
        }

        return SessionHistoryData.parse(buf.array(), buf.array().length);
    }

    @Test
    void lookupSectorTimeAfterUpdate() {
        SessionHistoryBuffer buffer = new SessionHistoryBuffer();

        long[][] times = {{28000, 30000, 27000}, {29000, 31000, 28000}};
        buffer.update(buildHistory(0, 2, times));

        // Lap 1 (1-based), sector 0
        assertEquals(28000, buffer.getSectorTime(0, 1, 0));
        // Lap 1, sector 1
        assertEquals(30000, buffer.getSectorTime(0, 1, 1));
        // Lap 1, sector 2
        assertEquals(27000, buffer.getSectorTime(0, 1, 2));
        // Lap 2, sector 0
        assertEquals(29000, buffer.getSectorTime(0, 2, 0));
    }

    @Test
    void missingLapReturnsNegative() {
        SessionHistoryBuffer buffer = new SessionHistoryBuffer();
        assertEquals(-1, buffer.getSectorTime(0, 1, 0)); // no data yet
    }

    @Test
    void outOfRangeLapReturnsNegative() {
        SessionHistoryBuffer buffer = new SessionHistoryBuffer();
        long[][] times = {{28000, 30000, 27000}};
        buffer.update(buildHistory(0, 1, times));

        // Lap too far back (beyond buffer window)
        assertEquals(-1, buffer.getSectorTime(0, 0, 0)); // lap 0 doesn't exist
    }

    @Test
    void resetClearsBuffer() {
        SessionHistoryBuffer buffer = new SessionHistoryBuffer();
        long[][] times = {{28000, 30000, 27000}};
        buffer.update(buildHistory(0, 1, times));
        assertEquals(28000, buffer.getSectorTime(0, 1, 0));

        buffer.reset();
        assertEquals(-1, buffer.getSectorTime(0, 1, 0));
    }

    @Test
    void bufferWrapsAtFiveLaps() {
        SessionHistoryBuffer buffer = new SessionHistoryBuffer();
        long[][] times = new long[7][];
        for (int i = 0; i < 7; i++) {
            times[i] = new long[]{28000 + i * 100, 30000 + i * 100, 27000 + i * 100};
        }
        buffer.update(buildHistory(0, 7, times));

        // Lap 7 (latest) should be available
        assertEquals(28600, buffer.getSectorTime(0, 7, 0));
        // Lap 3 (oldest in 5-lap window) should be available
        assertEquals(28200, buffer.getSectorTime(0, 3, 0));
        // Lap 2 should be out of window
        assertEquals(-1, buffer.getSectorTime(0, 2, 0));
    }
}
