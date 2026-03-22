package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PacketHeader {

    public static final int HEADER_SIZE = 29;

    public final int packetFormat;       // uint16
    public final int gameYear;           // uint8
    public final int gameMajorVersion;   // uint8
    public final int gameMinorVersion;   // uint8
    public final int packetVersion;      // uint8
    public final int packetId;           // uint8
    public final long sessionUID;        // uint64
    public final float sessionTime;      // float
    public final long frameIdentifier;   // uint32
    public final long overallFrameIdentifier; // uint32
    public final int playerCarIndex;     // uint8
    public final int secondaryPlayerCarIndex; // uint8

    private PacketHeader(ByteBuffer buf) {
        this.packetFormat = Short.toUnsignedInt(buf.getShort());
        this.gameYear = Byte.toUnsignedInt(buf.get());
        this.gameMajorVersion = Byte.toUnsignedInt(buf.get());
        this.gameMinorVersion = Byte.toUnsignedInt(buf.get());
        this.packetVersion = Byte.toUnsignedInt(buf.get());
        this.packetId = Byte.toUnsignedInt(buf.get());
        this.sessionUID = buf.getLong();
        this.sessionTime = buf.getFloat();
        this.frameIdentifier = Integer.toUnsignedLong(buf.getInt());
        this.overallFrameIdentifier = Integer.toUnsignedLong(buf.getInt());
        this.playerCarIndex = Byte.toUnsignedInt(buf.get());
        this.secondaryPlayerCarIndex = Byte.toUnsignedInt(buf.get());
    }

    public static PacketHeader parse(byte[] data, int length) {
        if (length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return new PacketHeader(buf);
    }

    public String packetTypeName() {
        return PacketType.fromId(packetId);
    }
}
