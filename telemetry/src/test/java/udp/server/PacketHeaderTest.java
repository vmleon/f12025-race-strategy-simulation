package udp.server;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class PacketHeaderTest {

    @Test
    void parseValidHeader() {
        ByteBuffer buf = ByteBuffer.allocate(29);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 2025);   // packetFormat
        buf.put((byte) 25);           // gameYear
        buf.put((byte) 1);            // gameMajorVersion
        buf.put((byte) 3);            // gameMinorVersion
        buf.put((byte) 1);            // packetVersion
        buf.put((byte) 6);            // packetId (CarTelemetry)
        buf.putLong(0xABCDL);         // sessionUID
        buf.putFloat(12.5f);          // sessionTime
        buf.putInt(100);              // frameIdentifier
        buf.putInt(200);              // overallFrameIdentifier
        buf.put((byte) 5);            // playerCarIndex
        buf.put((byte) 255);          // secondaryPlayerCarIndex

        byte[] data = buf.array();
        PacketHeader header = PacketHeader.parse(data, data.length);

        assertNotNull(header);
        assertEquals(2025, header.packetFormat);
        assertEquals(25, header.gameYear);
        assertEquals(1, header.gameMajorVersion);
        assertEquals(3, header.gameMinorVersion);
        assertEquals(1, header.packetVersion);
        assertEquals(6, header.packetId);
        assertEquals(0xABCDL, header.sessionUID);
        assertEquals(12.5f, header.sessionTime);
        assertEquals(100, header.frameIdentifier);
        assertEquals(200, header.overallFrameIdentifier);
        assertEquals(5, header.playerCarIndex);
        assertEquals(255, header.secondaryPlayerCarIndex);
        assertEquals("CarTelemetry", header.packetTypeName());
    }

    @Test
    void parseTooSmallReturnsNull() {
        byte[] data = new byte[10];
        assertNull(PacketHeader.parse(data, data.length));
    }

    @Test
    void packetTypeFromIdKnown() {
        assertEquals("Motion", PacketType.fromId(0));
        assertEquals("CarTelemetry", PacketType.fromId(6));
        assertEquals("LapPositions", PacketType.fromId(15));
    }

    @Test
    void packetTypeFromIdUnknown() {
        assertEquals("Unknown", PacketType.fromId(99));
        assertEquals("Unknown", PacketType.fromId(-1));
    }
}
