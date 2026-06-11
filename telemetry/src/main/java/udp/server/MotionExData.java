package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Player-car-only extended motion data from PacketMotionExData (packetId=13).
 * Total packet is 273 bytes. Only the fields used for driving-event detection
 * are parsed; the rest are skipped via absolute offsets. Wheel order: RL, RR, FL, FR.
 */
public class MotionExData {

    public static final int SIZE = 273;

    // Absolute byte offsets from the start of the packet (header = 29 bytes).
    private static final int OFF_WHEEL_SPEED = 77;
    private static final int OFF_SLIP_RATIO = 93;
    private static final int OFF_SLIP_ANGLE = 109;
    private static final int OFF_LOCAL_VELOCITY = 161;
    private static final int OFF_CHASSIS_YAW = 233;

    public final float[] wheelSpeed;     // m/s, per wheel
    public final float[] wheelSlipRatio; // raw (units undocumented)
    public final float[] wheelSlipAngle; // raw (units undocumented)
    public final float localVelocityX;   // forward speed, m/s
    public final float localVelocityY;   // lateral speed, m/s
    public final float chassisYaw;       // radians, yaw vs direction of motion (sideslip)

    private MotionExData(ByteBuffer buf) {
        this.wheelSpeed = new float[4];
        buf.position(OFF_WHEEL_SPEED);
        for (int i = 0; i < 4; i++) this.wheelSpeed[i] = buf.getFloat();

        this.wheelSlipRatio = new float[4];
        buf.position(OFF_SLIP_RATIO);
        for (int i = 0; i < 4; i++) this.wheelSlipRatio[i] = buf.getFloat();

        this.wheelSlipAngle = new float[4];
        buf.position(OFF_SLIP_ANGLE);
        for (int i = 0; i < 4; i++) this.wheelSlipAngle[i] = buf.getFloat();

        buf.position(OFF_LOCAL_VELOCITY);
        this.localVelocityX = buf.getFloat();
        this.localVelocityY = buf.getFloat();
        buf.getFloat(); // localVelocityZ (unused)

        buf.position(OFF_CHASSIS_YAW);
        this.chassisYaw = buf.getFloat();
    }

    /** Parse a MotionEx packet. Returns null if the buffer is too small. */
    public static MotionExData parse(byte[] data, int length) {
        if (length < SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return new MotionExData(buf);
    }
}
