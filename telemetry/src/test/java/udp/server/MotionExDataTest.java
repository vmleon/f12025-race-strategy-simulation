package udp.server;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class MotionExDataTest {

    /** Build a 273-byte MotionEx packet with header + the fields we parse set. */
    private byte[] buildPacket() {
        ByteBuffer buf = ByteBuffer.allocate(MotionExData.SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        // Header (29 bytes)
        buf.putShort((short) 2025); // packetFormat
        buf.put((byte) 25);         // gameYear
        buf.put((byte) 1);          // gameMajorVersion
        buf.put((byte) 0);          // gameMinorVersion
        buf.put((byte) 1);          // packetVersion
        buf.put((byte) 13);         // packetId (MotionEx)
        buf.putLong(0x1234L);       // sessionUID
        buf.putFloat(10.0f);        // sessionTime
        buf.putInt(50);             // frameIdentifier
        buf.putInt(50);             // overallFrameIdentifier
        buf.put((byte) 0);          // playerCarIndex
        buf.put((byte) 255);        // secondaryPlayerCarIndex
        // suspensionPosition[4], suspensionVelocity[4], suspensionAcceleration[4] = 48 bytes (offset 29..77)
        for (int i = 0; i < 12; i++) buf.putFloat(0f);
        // wheelSpeed[4] @77 — RL, RR, FL, FR
        buf.putFloat(40f); buf.putFloat(41f); buf.putFloat(10f); buf.putFloat(42f);
        // wheelSlipRatio[4] @93
        buf.putFloat(0.1f); buf.putFloat(0.2f); buf.putFloat(-0.9f); buf.putFloat(0.3f);
        // wheelSlipAngle[4] @109
        buf.putFloat(0.01f); buf.putFloat(0.02f); buf.putFloat(0.5f); buf.putFloat(0.6f);
        // wheelLatForce[4], wheelLongForce[4], heightOfCOGAboveGround = 36 bytes (offset 125..161)
        for (int i = 0; i < 9; i++) buf.putFloat(0f);
        // localVelocityX/Y/Z @161
        buf.putFloat(45f); buf.putFloat(3f); buf.putFloat(0f);
        // angularVelocity(3), angularAcceleration(3), frontWheelsAngle(1), wheelVertForce(4),
        // frontAeroHeight, rearAeroHeight, frontRollAngle, rearRollAngle = 15 floats = 60 bytes (offset 173..233)
        for (int i = 0; i < 15; i++) buf.putFloat(0f);
        // chassisYaw @233
        buf.putFloat(0.25f);
        // chassisPitch, wheelCamber[4], wheelCamberGain[4] = 9 floats (offset 237..273)
        for (int i = 0; i < 9; i++) buf.putFloat(0f);
        return buf.array();
    }

    @Test
    void parsesPlayerDynamics() {
        byte[] data = buildPacket();
        MotionExData m = MotionExData.parse(data, data.length);

        assertNotNull(m);
        assertArrayEquals(new float[]{40f, 41f, 10f, 42f}, m.wheelSpeed, 0.001f);
        assertArrayEquals(new float[]{0.1f, 0.2f, -0.9f, 0.3f}, m.wheelSlipRatio, 0.001f);
        assertArrayEquals(new float[]{0.01f, 0.02f, 0.5f, 0.6f}, m.wheelSlipAngle, 0.001f);
        assertEquals(45f, m.localVelocityX, 0.001f);
        assertEquals(3f, m.localVelocityY, 0.001f);
        assertEquals(0.25f, m.chassisYaw, 0.001f);
    }

    @Test
    void tooSmallReturnsNull() {
        assertNull(MotionExData.parse(new byte[100], 100));
    }
}
