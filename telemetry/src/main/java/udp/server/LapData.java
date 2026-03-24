package udp.server;

import java.nio.ByteBuffer;

/**
 * Per-car lap data from PacketLapData (packetId=2).
 * 57 bytes per car, 22 cars per packet.
 */
public class LapData {

    public static final int SIZE = 57;
    public static final int NUM_CARS = 22;

    public final long lastLapTimeInMS;          // uint32
    public final long currentLapTimeInMS;       // uint32
    public final int sector1TimeMSPart;         // uint16
    public final int sector1TimeMinutesPart;    // uint8
    public final int sector2TimeMSPart;         // uint16
    public final int sector2TimeMinutesPart;    // uint8
    public final int deltaToCarInFrontMSPart;   // uint16
    public final int deltaToCarInFrontMinutesPart; // uint8
    public final int deltaToRaceLeaderMSPart;   // uint16
    public final int deltaToRaceLeaderMinutesPart; // uint8
    public final float lapDistance;
    public final float totalDistance;
    public final float safetyCarDelta;
    public final int carPosition;               // uint8
    public final int currentLapNum;             // uint8
    public final int pitStatus;                 // uint8
    public final int numPitStops;               // uint8
    public final int sector;                    // uint8 (0/1/2)
    public final int currentLapInvalid;         // uint8
    public final int penalties;                 // uint8
    public final int totalWarnings;             // uint8
    public final int cornerCuttingWarnings;     // uint8
    public final int numUnservedDriveThroughPens; // uint8
    public final int numUnservedStopGoPens;     // uint8
    public final int gridPosition;              // uint8
    public final int driverStatus;              // uint8
    public final int resultStatus;              // uint8
    public final int pitLaneTimerActive;        // uint8
    public final int pitLaneTimeInLaneInMS;     // uint16
    public final int pitStopTimerInMS;          // uint16
    public final int pitStopShouldServePen;     // uint8
    public final float speedTrapFastestSpeed;
    public final int speedTrapFastestLap;       // uint8

    private LapData(ByteBuffer buf) {
        this.lastLapTimeInMS = Integer.toUnsignedLong(buf.getInt());
        this.currentLapTimeInMS = Integer.toUnsignedLong(buf.getInt());
        this.sector1TimeMSPart = Short.toUnsignedInt(buf.getShort());
        this.sector1TimeMinutesPart = Byte.toUnsignedInt(buf.get());
        this.sector2TimeMSPart = Short.toUnsignedInt(buf.getShort());
        this.sector2TimeMinutesPart = Byte.toUnsignedInt(buf.get());
        this.deltaToCarInFrontMSPart = Short.toUnsignedInt(buf.getShort());
        this.deltaToCarInFrontMinutesPart = Byte.toUnsignedInt(buf.get());
        this.deltaToRaceLeaderMSPart = Short.toUnsignedInt(buf.getShort());
        this.deltaToRaceLeaderMinutesPart = Byte.toUnsignedInt(buf.get());
        this.lapDistance = buf.getFloat();
        this.totalDistance = buf.getFloat();
        this.safetyCarDelta = buf.getFloat();
        this.carPosition = Byte.toUnsignedInt(buf.get());
        this.currentLapNum = Byte.toUnsignedInt(buf.get());
        this.pitStatus = Byte.toUnsignedInt(buf.get());
        this.numPitStops = Byte.toUnsignedInt(buf.get());
        this.sector = Byte.toUnsignedInt(buf.get());
        this.currentLapInvalid = Byte.toUnsignedInt(buf.get());
        this.penalties = Byte.toUnsignedInt(buf.get());
        this.totalWarnings = Byte.toUnsignedInt(buf.get());
        this.cornerCuttingWarnings = Byte.toUnsignedInt(buf.get());
        this.numUnservedDriveThroughPens = Byte.toUnsignedInt(buf.get());
        this.numUnservedStopGoPens = Byte.toUnsignedInt(buf.get());
        this.gridPosition = Byte.toUnsignedInt(buf.get());
        this.driverStatus = Byte.toUnsignedInt(buf.get());
        this.resultStatus = Byte.toUnsignedInt(buf.get());
        this.pitLaneTimerActive = Byte.toUnsignedInt(buf.get());
        this.pitLaneTimeInLaneInMS = Short.toUnsignedInt(buf.getShort());
        this.pitStopTimerInMS = Short.toUnsignedInt(buf.getShort());
        this.pitStopShouldServePen = Byte.toUnsignedInt(buf.get());
        this.speedTrapFastestSpeed = buf.getFloat();
        this.speedTrapFastestLap = Byte.toUnsignedInt(buf.get());
    }

    /** Compute sector 1 time in milliseconds (minutes*60000 + ms part). */
    public long sector1TimeInMS() {
        return sector1TimeMinutesPart * 60_000L + sector1TimeMSPart;
    }

    /** Compute sector 2 time in milliseconds (minutes*60000 + ms part). */
    public long sector2TimeInMS() {
        return sector2TimeMinutesPart * 60_000L + sector2TimeMSPart;
    }

    /**
     * Parse an array of 22 LapData entries from the buffer (position must be at offset 29).
     * Returns null if the buffer is too small.
     */
    public static LapData[] parseAll(byte[] data, int length) {
        int required = PacketHeader.HEADER_SIZE + NUM_CARS * SIZE + 2; // +2 for time trial indices
        if (length < required) {
            return null;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data, 0, length);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);

        LapData[] cars = new LapData[NUM_CARS];
        for (int i = 0; i < NUM_CARS; i++) {
            cars[i] = new LapData(buf);
        }
        return cars;
    }
}
