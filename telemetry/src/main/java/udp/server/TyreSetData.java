package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Tyre set data from PacketTyreSetsData (packetId=12).
 * 9 bytes per tyre set, 20 sets per packet. Sent per-car.
 */
public class TyreSetData {

    public static final int SIZE = 10;
    public static final int MAX_TYRE_SETS = 20;

    public final int actualTyreCompound;   // uint8
    public final int visualTyreCompound;   // uint8
    public final int wear;                 // uint8 (percentage)
    public final int available;            // uint8
    public final int recommendedSession;   // uint8
    public final int lifeSpan;             // uint8 (laps)
    public final int usableLife;           // uint8 (laps)
    public final int lapDeltaTime;         // int16 (ms)
    public final int fitted;               // uint8

    private TyreSetData(ByteBuffer buf) {
        this.actualTyreCompound = Byte.toUnsignedInt(buf.get());
        this.visualTyreCompound = Byte.toUnsignedInt(buf.get());
        this.wear = Byte.toUnsignedInt(buf.get());
        this.available = Byte.toUnsignedInt(buf.get());
        this.recommendedSession = Byte.toUnsignedInt(buf.get());
        this.lifeSpan = Byte.toUnsignedInt(buf.get());
        this.usableLife = Byte.toUnsignedInt(buf.get());
        this.lapDeltaTime = buf.getShort(); // signed int16
        this.fitted = Byte.toUnsignedInt(buf.get());
    }

    public record TyreSetPacket(int carIdx, TyreSetData[] sets, int fittedIdx) {}

    public static TyreSetPacket parse(byte[] data, int length) {
        // header(29) + carIdx(1) + 20*9 + fittedIdx(1) = 211
        int required = PacketHeader.HEADER_SIZE + 1 + MAX_TYRE_SETS * SIZE + 1;
        if (length < required) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);

        int carIdx = Byte.toUnsignedInt(buf.get());
        TyreSetData[] sets = new TyreSetData[MAX_TYRE_SETS];
        for (int i = 0; i < MAX_TYRE_SETS; i++) {
            sets[i] = new TyreSetData(buf);
        }
        int fittedIdx = Byte.toUnsignedInt(buf.get());
        return new TyreSetPacket(carIdx, sets, fittedIdx);
    }
}
