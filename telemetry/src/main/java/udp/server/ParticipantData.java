package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Per-car participant data from PacketParticipantsData (packetId=4).
 * 57 bytes per participant, 22 participants per packet.
 */
public class ParticipantData {

    public static final int SIZE = 57;  // 7 + 32 + 2 + 2 + 1 + 1 + 12
    public static final int NUM_CARS = 22;
    private static final int NAME_LENGTH = 32;

    public final int aiControlled;     // uint8
    public final int driverId;         // uint8
    public final int networkId;        // uint8
    public final int teamId;           // uint8
    public final int myTeam;           // uint8
    public final int raceNumber;       // uint8
    public final int nationality;      // uint8
    public final String name;          // char[32]
    public final int yourTelemetry;    // uint8
    public final int showOnlineNames;  // uint8
    public final int techLevel;        // uint16
    public final int platform;         // uint8
    public final int numColours;       // uint8

    private ParticipantData(ByteBuffer buf) {
        this.aiControlled = Byte.toUnsignedInt(buf.get());
        this.driverId = Byte.toUnsignedInt(buf.get());
        this.networkId = Byte.toUnsignedInt(buf.get());
        this.teamId = Byte.toUnsignedInt(buf.get());
        this.myTeam = Byte.toUnsignedInt(buf.get());
        this.raceNumber = Byte.toUnsignedInt(buf.get());
        this.nationality = Byte.toUnsignedInt(buf.get());

        byte[] nameBytes = new byte[NAME_LENGTH];
        buf.get(nameBytes);
        int end = 0;
        while (end < nameBytes.length && nameBytes[end] != 0) end++;
        this.name = new String(nameBytes, 0, end, StandardCharsets.UTF_8);

        this.yourTelemetry = Byte.toUnsignedInt(buf.get());
        this.showOnlineNames = Byte.toUnsignedInt(buf.get());
        this.techLevel = Short.toUnsignedInt(buf.getShort());
        this.platform = Byte.toUnsignedInt(buf.get());
        this.numColours = Byte.toUnsignedInt(buf.get());
        buf.position(buf.position() + 12); // skip liveryColours (4 × 3 RGB)
    }

    /**
     * Parse participants from packet. Returns array of 22 + numActiveCars as index 0 metadata.
     */
    public static ParticipantData[] parseAll(byte[] data, int length) {
        // header(29) + numActiveCars(1) + 22 * 57 = 1284
        int required = PacketHeader.HEADER_SIZE + 1 + NUM_CARS * SIZE;
        if (length < required) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);
        buf.get(); // numActiveCars (skip, caller uses header.playerCarIndex)

        ParticipantData[] participants = new ParticipantData[NUM_CARS];
        for (int i = 0; i < NUM_CARS; i++) {
            participants[i] = new ParticipantData(buf);
        }
        return participants;
    }

    /**
     * Parse and also return the numActiveCars value.
     */
    public static int parseNumActiveCars(byte[] data, int length) {
        if (length < PacketHeader.HEADER_SIZE + 1) return 0;
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);
        return Byte.toUnsignedInt(buf.get());
    }
}
