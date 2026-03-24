package udp.server;

import java.nio.ByteBuffer;

/**
 * Per-car telemetry data from PacketCarTelemetryData (packetId=6).
 * 60 bytes per car, 22 cars per packet.
 */
public class CarTelemetryData {

    public static final int SIZE = 60;
    public static final int NUM_CARS = 22;

    public final int speed;                     // uint16 (km/h)
    public final float throttle;                // 0.0 to 1.0
    public final float steer;                   // -1.0 to 1.0
    public final float brake;                   // 0.0 to 1.0
    public final int clutch;                    // uint8 (0-100)
    public final int gear;                      // int8 (R=-1, N=0, 1-8)
    public final int engineRPM;                 // uint16
    public final int drs;                       // uint8 (0=off, 1=on)
    public final int revLightsPercent;          // uint8
    public final int revLightsBitValue;         // uint16
    public final int[] brakesTemperature;       // uint16[4] (celsius)
    public final int[] tyresSurfaceTemperature; // uint8[4] (celsius)
    public final int[] tyresInnerTemperature;   // uint8[4] (celsius)
    public final int engineTemperature;         // uint16 (celsius)
    public final float[] tyresPressure;         // float[4] (PSI)
    public final int[] surfaceType;             // uint8[4]

    private CarTelemetryData(ByteBuffer buf) {
        this.speed = Short.toUnsignedInt(buf.getShort());
        this.throttle = buf.getFloat();
        this.steer = buf.getFloat();
        this.brake = buf.getFloat();
        this.clutch = Byte.toUnsignedInt(buf.get());
        this.gear = buf.get(); // signed int8
        this.engineRPM = Short.toUnsignedInt(buf.getShort());
        this.drs = Byte.toUnsignedInt(buf.get());
        this.revLightsPercent = Byte.toUnsignedInt(buf.get());
        this.revLightsBitValue = Short.toUnsignedInt(buf.getShort());

        this.brakesTemperature = new int[4];
        for (int i = 0; i < 4; i++) {
            this.brakesTemperature[i] = Short.toUnsignedInt(buf.getShort());
        }

        this.tyresSurfaceTemperature = new int[4];
        for (int i = 0; i < 4; i++) {
            this.tyresSurfaceTemperature[i] = Byte.toUnsignedInt(buf.get());
        }

        this.tyresInnerTemperature = new int[4];
        for (int i = 0; i < 4; i++) {
            this.tyresInnerTemperature[i] = Byte.toUnsignedInt(buf.get());
        }

        this.engineTemperature = Short.toUnsignedInt(buf.getShort());

        this.tyresPressure = new float[4];
        for (int i = 0; i < 4; i++) {
            this.tyresPressure[i] = buf.getFloat();
        }

        this.surfaceType = new int[4];
        for (int i = 0; i < 4; i++) {
            this.surfaceType[i] = Byte.toUnsignedInt(buf.get());
        }
    }

    /**
     * Parse an array of 22 CarTelemetryData entries from the buffer (position must be at offset 29).
     * Returns null if the buffer is too small.
     */
    public static CarTelemetryData[] parseAll(byte[] data, int length) {
        int required = PacketHeader.HEADER_SIZE + NUM_CARS * SIZE + 3; // +3 for mfd/suggestedGear
        if (length < required) {
            return null;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data, 0, length);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);

        CarTelemetryData[] cars = new CarTelemetryData[NUM_CARS];
        for (int i = 0; i < NUM_CARS; i++) {
            cars[i] = new CarTelemetryData(buf);
        }
        return cars;
    }
}
