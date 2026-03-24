package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Per-car session history from PacketSessionHistoryData (packetId=11).
 * Sent per-car, contains up to 100 laps of timing data.
 * Used in-memory for UDP loss recovery (not persisted).
 */
public class SessionHistoryData {

    public static final int MAX_LAPS = 100;
    public static final int LAP_HISTORY_SIZE = 14;
    public static final int TYRE_STINT_SIZE = 3;
    private static final int MAX_TYRE_STINTS = 8;

    public final int carIdx;
    public final int numLaps;
    public final int numTyreStints;
    public final int bestLapTimeLapNum;
    public final int bestSector1LapNum;
    public final int bestSector2LapNum;
    public final int bestSector3LapNum;
    public final LapHistory[] lapHistories;
    public final TyreStintHistory[] tyreStints;

    public record LapHistory(
            long lapTimeInMS, int sector1TimeMSPart, int sector1TimeMinutesPart,
            int sector2TimeMSPart, int sector2TimeMinutesPart,
            int sector3TimeMSPart, int sector3TimeMinutesPart,
            int lapValidBitFlags) {

        public long sector1TimeInMS() {
            return sector1TimeMinutesPart * 60_000L + sector1TimeMSPart;
        }

        public long sector2TimeInMS() {
            return sector2TimeMinutesPart * 60_000L + sector2TimeMSPart;
        }

        public long sector3TimeInMS() {
            return sector3TimeMinutesPart * 60_000L + sector3TimeMSPart;
        }

        public boolean isLapValid() { return (lapValidBitFlags & 0x01) != 0; }
        public boolean isSector1Valid() { return (lapValidBitFlags & 0x02) != 0; }
        public boolean isSector2Valid() { return (lapValidBitFlags & 0x04) != 0; }
        public boolean isSector3Valid() { return (lapValidBitFlags & 0x08) != 0; }
    }

    public record TyreStintHistory(int endLap, int tyreActualCompound, int tyreVisualCompound) {}

    private SessionHistoryData(ByteBuffer buf) {
        this.carIdx = Byte.toUnsignedInt(buf.get());
        this.numLaps = Byte.toUnsignedInt(buf.get());
        this.numTyreStints = Byte.toUnsignedInt(buf.get());
        this.bestLapTimeLapNum = Byte.toUnsignedInt(buf.get());
        this.bestSector1LapNum = Byte.toUnsignedInt(buf.get());
        this.bestSector2LapNum = Byte.toUnsignedInt(buf.get());
        this.bestSector3LapNum = Byte.toUnsignedInt(buf.get());

        this.lapHistories = new LapHistory[MAX_LAPS];
        for (int i = 0; i < MAX_LAPS; i++) {
            long lapTime = Integer.toUnsignedLong(buf.getInt());
            int s1ms = Short.toUnsignedInt(buf.getShort());
            int s1min = Byte.toUnsignedInt(buf.get());
            int s2ms = Short.toUnsignedInt(buf.getShort());
            int s2min = Byte.toUnsignedInt(buf.get());
            int s3ms = Short.toUnsignedInt(buf.getShort());
            int s3min = Byte.toUnsignedInt(buf.get());
            int flags = Byte.toUnsignedInt(buf.get());
            this.lapHistories[i] = new LapHistory(lapTime, s1ms, s1min, s2ms, s2min, s3ms, s3min, flags);
        }

        this.tyreStints = new TyreStintHistory[MAX_TYRE_STINTS];
        for (int i = 0; i < MAX_TYRE_STINTS; i++) {
            int endLap = Byte.toUnsignedInt(buf.get());
            int actual = Byte.toUnsignedInt(buf.get());
            int visual = Byte.toUnsignedInt(buf.get());
            this.tyreStints[i] = new TyreStintHistory(endLap, actual, visual);
        }
    }

    public static SessionHistoryData parse(byte[] data, int length) {
        // header(29) + carIdx(1) + meta(6) + 100*14 + 8*3 = 1460
        int required = PacketHeader.HEADER_SIZE + 7 + MAX_LAPS * LAP_HISTORY_SIZE + MAX_TYRE_STINTS * TYRE_STINT_SIZE;
        if (length < required) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);
        return new SessionHistoryData(buf);
    }
}
