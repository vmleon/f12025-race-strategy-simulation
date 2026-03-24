package udp.server;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class CarTelemetryDataTest {

    private byte[] buildPacket(java.util.function.Consumer<ByteBuffer> carWriter) {
        int totalSize = PacketHeader.HEADER_SIZE + CarTelemetryData.NUM_CARS * CarTelemetryData.SIZE + 3;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Header
        buf.putShort((short) 2025);
        buf.put((byte) 25);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.put((byte) 6); // packetId = CarTelemetry
        buf.putLong(0x1234L);
        buf.putFloat(10.0f);
        buf.putInt(50);
        buf.putInt(50);
        buf.put((byte) 0); // playerCarIndex
        buf.put((byte) 255);

        // Write car 0
        carWriter.accept(buf);

        return buf.array();
    }

    @Test
    void parsePlayerTelemetry() {
        byte[] data = buildPacket(buf -> {
            buf.putShort((short) 285);   // speed
            buf.putFloat(0.8f);          // throttle
            buf.putFloat(-0.2f);         // steer
            buf.putFloat(0.0f);          // brake
            buf.put((byte) 0);           // clutch
            buf.put((byte) 7);           // gear
            buf.putShort((short) 11500); // engineRPM
            buf.put((byte) 1);           // drs
            buf.put((byte) 75);          // revLightsPercent
            buf.putShort((short) 0);     // revLightsBitValue
            // brakesTemperature[4]
            buf.putShort((short) 800);
            buf.putShort((short) 810);
            buf.putShort((short) 750);
            buf.putShort((short) 760);
            // tyresSurfaceTemperature[4] (uint8)
            buf.put((byte) 95);
            buf.put((byte) 97);
            buf.put((byte) 90);
            buf.put((byte) 92);
            // tyresInnerTemperature[4] (uint8)
            buf.put((byte) 100);
            buf.put((byte) 102);
            buf.put((byte) 98);
            buf.put((byte) 99);
            // engineTemperature
            buf.putShort((short) 110);
            // tyresPressure[4]
            buf.putFloat(23.5f);
            buf.putFloat(23.6f);
            buf.putFloat(22.8f);
            buf.putFloat(22.9f);
            // surfaceType[4]
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
        });

        CarTelemetryData[] telemetry = CarTelemetryData.parseAll(data, data.length);

        assertNotNull(telemetry);
        assertEquals(22, telemetry.length);

        CarTelemetryData player = telemetry[0];
        assertEquals(285, player.speed);
        assertEquals(0.8f, player.throttle, 0.001f);
        assertEquals(-0.2f, player.steer, 0.001f);
        assertEquals(0.0f, player.brake, 0.001f);
        assertEquals(7, player.gear);
        assertEquals(11500, player.engineRPM);
        assertEquals(1, player.drs);
        assertArrayEquals(new int[]{800, 810, 750, 760}, player.brakesTemperature);
        assertArrayEquals(new int[]{95, 97, 90, 92}, player.tyresSurfaceTemperature);
        assertArrayEquals(new int[]{100, 102, 98, 99}, player.tyresInnerTemperature);
        assertEquals(110, player.engineTemperature);
        assertEquals(23.5f, player.tyresPressure[0], 0.01f);
    }

    @Test
    void tooSmallReturnsNull() {
        byte[] data = new byte[100];
        assertNull(CarTelemetryData.parseAll(data, data.length));
    }
}
