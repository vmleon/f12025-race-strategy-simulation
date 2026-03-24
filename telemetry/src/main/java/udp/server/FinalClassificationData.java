package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Per-car final classification from PacketFinalClassificationData (packetId=8).
 * 45 bytes per car, 22 cars per packet.
 */
public class FinalClassificationData {

    public static final int SIZE = 46; // 7 + 4 + 8 + 3 + 3*8
    public static final int NUM_CARS = 22;
    private static final int MAX_TYRE_STINTS = 8;

    public final int position;             // uint8
    public final int numLaps;              // uint8
    public final int gridPosition;         // uint8
    public final int points;               // uint8
    public final int numPitStops;          // uint8
    public final int resultStatus;         // uint8
    public final int resultReason;         // uint8
    public final long bestLapTimeInMS;     // uint32
    public final double totalRaceTime;     // double (8 bytes)
    public final int penaltiesTime;        // uint8
    public final int numPenalties;         // uint8
    public final int numTyreStints;        // uint8
    public final int[] tyreStintsActual;   // uint8[8]
    public final int[] tyreStintsVisual;   // uint8[8]
    public final int[] tyreStintsEndLaps;  // uint8[8]

    private FinalClassificationData(ByteBuffer buf) {
        this.position = Byte.toUnsignedInt(buf.get());
        this.numLaps = Byte.toUnsignedInt(buf.get());
        this.gridPosition = Byte.toUnsignedInt(buf.get());
        this.points = Byte.toUnsignedInt(buf.get());
        this.numPitStops = Byte.toUnsignedInt(buf.get());
        this.resultStatus = Byte.toUnsignedInt(buf.get());
        this.resultReason = Byte.toUnsignedInt(buf.get());
        this.bestLapTimeInMS = Integer.toUnsignedLong(buf.getInt());
        this.totalRaceTime = buf.getDouble();
        this.penaltiesTime = Byte.toUnsignedInt(buf.get());
        this.numPenalties = Byte.toUnsignedInt(buf.get());
        this.numTyreStints = Byte.toUnsignedInt(buf.get());
        this.tyreStintsActual = new int[MAX_TYRE_STINTS];
        for (int i = 0; i < MAX_TYRE_STINTS; i++) this.tyreStintsActual[i] = Byte.toUnsignedInt(buf.get());
        this.tyreStintsVisual = new int[MAX_TYRE_STINTS];
        for (int i = 0; i < MAX_TYRE_STINTS; i++) this.tyreStintsVisual[i] = Byte.toUnsignedInt(buf.get());
        this.tyreStintsEndLaps = new int[MAX_TYRE_STINTS];
        for (int i = 0; i < MAX_TYRE_STINTS; i++) this.tyreStintsEndLaps[i] = Byte.toUnsignedInt(buf.get());
    }

    public static FinalClassificationData[] parseAll(byte[] data, int length) {
        // header(29) + numCars(1) + 22 * 45
        int required = PacketHeader.HEADER_SIZE + 1 + NUM_CARS * SIZE;
        if (length < required) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);
        buf.get(); // numCars

        FinalClassificationData[] cars = new FinalClassificationData[NUM_CARS];
        for (int i = 0; i < NUM_CARS; i++) {
            cars[i] = new FinalClassificationData(buf);
        }
        return cars;
    }
}
