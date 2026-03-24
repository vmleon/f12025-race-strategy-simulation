package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Per-car status data from PacketCarStatusData (packetId=7).
 * 55 bytes per car, 22 cars per packet.
 */
public class CarStatusData {

    public static final int SIZE = 55;
    public static final int NUM_CARS = 22;

    public final int tractionControl;          // uint8
    public final int antiLockBrakes;           // uint8
    public final int fuelMix;                  // uint8
    public final int frontBrakeBias;           // uint8
    public final int pitLimiterStatus;         // uint8
    public final float fuelInTank;
    public final float fuelCapacity;
    public final float fuelRemainingLaps;
    public final int maxRPM;                   // uint16
    public final int idleRPM;                  // uint16
    public final int maxGears;                 // uint8
    public final int drsAllowed;               // uint8
    public final int drsActivationDistance;     // uint16
    public final int actualTyreCompound;       // uint8
    public final int visualTyreCompound;       // uint8
    public final int tyresAgeLaps;             // uint8
    public final int vehicleFIAFlags;          // int8
    public final float enginePowerICE;
    public final float enginePowerMGUK;
    public final float ersStoreEnergy;
    public final int ersDeployMode;            // uint8
    public final float ersHarvestedThisLapMGUK;
    public final float ersHarvestedThisLapMGUH;
    public final float ersDeployedThisLap;
    public final int networkPaused;            // uint8

    private CarStatusData(ByteBuffer buf) {
        this.tractionControl = Byte.toUnsignedInt(buf.get());
        this.antiLockBrakes = Byte.toUnsignedInt(buf.get());
        this.fuelMix = Byte.toUnsignedInt(buf.get());
        this.frontBrakeBias = Byte.toUnsignedInt(buf.get());
        this.pitLimiterStatus = Byte.toUnsignedInt(buf.get());
        this.fuelInTank = buf.getFloat();
        this.fuelCapacity = buf.getFloat();
        this.fuelRemainingLaps = buf.getFloat();
        this.maxRPM = Short.toUnsignedInt(buf.getShort());
        this.idleRPM = Short.toUnsignedInt(buf.getShort());
        this.maxGears = Byte.toUnsignedInt(buf.get());
        this.drsAllowed = Byte.toUnsignedInt(buf.get());
        this.drsActivationDistance = Short.toUnsignedInt(buf.getShort());
        this.actualTyreCompound = Byte.toUnsignedInt(buf.get());
        this.visualTyreCompound = Byte.toUnsignedInt(buf.get());
        this.tyresAgeLaps = Byte.toUnsignedInt(buf.get());
        this.vehicleFIAFlags = buf.get(); // signed
        this.enginePowerICE = buf.getFloat();
        this.enginePowerMGUK = buf.getFloat();
        this.ersStoreEnergy = buf.getFloat();
        this.ersDeployMode = Byte.toUnsignedInt(buf.get());
        this.ersHarvestedThisLapMGUK = buf.getFloat();
        this.ersHarvestedThisLapMGUH = buf.getFloat();
        this.ersDeployedThisLap = buf.getFloat();
        this.networkPaused = Byte.toUnsignedInt(buf.get());
    }

    public static CarStatusData[] parseAll(byte[] data, int length) {
        int required = PacketHeader.HEADER_SIZE + NUM_CARS * SIZE;
        if (length < required) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);

        CarStatusData[] cars = new CarStatusData[NUM_CARS];
        for (int i = 0; i < NUM_CARS; i++) {
            cars[i] = new CarStatusData(buf);
        }
        return cars;
    }
}
