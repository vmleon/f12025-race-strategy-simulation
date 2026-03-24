package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Per-car damage data from PacketCarDamageData (packetId=10).
 * 46 bytes per car, 22 cars per packet.
 */
public class CarDamageData {

    public static final int SIZE = 46;
    public static final int NUM_CARS = 22;

    public final float[] tyresWear;            // float[4] (percentage)
    public final int[] tyresDamage;            // uint8[4] (percentage)
    public final int[] brakesDamage;           // uint8[4] (percentage)
    public final int[] tyreBlisters;           // uint8[4] (percentage)
    public final int frontLeftWingDamage;      // uint8
    public final int frontRightWingDamage;     // uint8
    public final int rearWingDamage;           // uint8
    public final int floorDamage;              // uint8
    public final int diffuserDamage;           // uint8
    public final int sidepodDamage;            // uint8
    public final int drsFault;                 // uint8
    public final int ersFault;                 // uint8
    public final int gearBoxDamage;            // uint8
    public final int engineDamage;             // uint8
    public final int engineMGUHWear;           // uint8
    public final int engineESWear;             // uint8
    public final int engineCEWear;             // uint8
    public final int engineICEWear;            // uint8
    public final int engineMGUKWear;           // uint8
    public final int engineTCWear;             // uint8
    public final int engineBlown;              // uint8
    public final int engineSeized;             // uint8

    private CarDamageData(ByteBuffer buf) {
        this.tyresWear = new float[4];
        for (int i = 0; i < 4; i++) this.tyresWear[i] = buf.getFloat();
        this.tyresDamage = new int[4];
        for (int i = 0; i < 4; i++) this.tyresDamage[i] = Byte.toUnsignedInt(buf.get());
        this.brakesDamage = new int[4];
        for (int i = 0; i < 4; i++) this.brakesDamage[i] = Byte.toUnsignedInt(buf.get());
        this.tyreBlisters = new int[4];
        for (int i = 0; i < 4; i++) this.tyreBlisters[i] = Byte.toUnsignedInt(buf.get());
        this.frontLeftWingDamage = Byte.toUnsignedInt(buf.get());
        this.frontRightWingDamage = Byte.toUnsignedInt(buf.get());
        this.rearWingDamage = Byte.toUnsignedInt(buf.get());
        this.floorDamage = Byte.toUnsignedInt(buf.get());
        this.diffuserDamage = Byte.toUnsignedInt(buf.get());
        this.sidepodDamage = Byte.toUnsignedInt(buf.get());
        this.drsFault = Byte.toUnsignedInt(buf.get());
        this.ersFault = Byte.toUnsignedInt(buf.get());
        this.gearBoxDamage = Byte.toUnsignedInt(buf.get());
        this.engineDamage = Byte.toUnsignedInt(buf.get());
        this.engineMGUHWear = Byte.toUnsignedInt(buf.get());
        this.engineESWear = Byte.toUnsignedInt(buf.get());
        this.engineCEWear = Byte.toUnsignedInt(buf.get());
        this.engineICEWear = Byte.toUnsignedInt(buf.get());
        this.engineMGUKWear = Byte.toUnsignedInt(buf.get());
        this.engineTCWear = Byte.toUnsignedInt(buf.get());
        this.engineBlown = Byte.toUnsignedInt(buf.get());
        this.engineSeized = Byte.toUnsignedInt(buf.get());
    }

    public static CarDamageData[] parseAll(byte[] data, int length) {
        int required = PacketHeader.HEADER_SIZE + NUM_CARS * SIZE;
        if (length < required) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);

        CarDamageData[] cars = new CarDamageData[NUM_CARS];
        for (int i = 0; i < NUM_CARS; i++) {
            cars[i] = new CarDamageData(buf);
        }
        return cars;
    }
}
