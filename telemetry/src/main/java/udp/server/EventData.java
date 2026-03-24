package udp.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Event data from PacketEventData (packetId=3).
 * Variable-size union detail depending on event code.
 */
public class EventData {

    public final String eventCode;         // 4-char ASCII

    // Fastest Lap (FTLP)
    public final int vehicleIdx;           // used by multiple events
    public final float lapTime;            // FTLP only (seconds)

    // Retirement (RTMT)
    public final int reason;               // RTMT / DRSDisabled

    // Penalty (PENA)
    public final int penaltyType;
    public final int infringementType;
    public final int otherVehicleIdx;
    public final int time;
    public final int lapNum;
    public final int placesGained;

    // Speed Trap (SPTP)
    public final float speed;

    // Safety Car (SCAR)
    public final int safetyCarType;
    public final int eventType;

    // Collision (COLL)
    public final int vehicle1Idx;
    public final int vehicle2Idx;

    private EventData(String eventCode, ByteBuffer buf) {
        this.eventCode = eventCode;

        // Defaults for unused fields
        int vIdx = -1;
        float lt = 0;
        int rsn = 0;
        int pt = 0, it = 0, ovi = -1, tm = 0, ln = 0, pg = 0;
        float spd = 0;
        int sct = 0, et = 0;
        int v1 = -1, v2 = -1;

        switch (eventCode) {
            case "FTLP" -> {
                vIdx = Byte.toUnsignedInt(buf.get());
                lt = buf.getFloat();
            }
            case "RTMT" -> {
                vIdx = Byte.toUnsignedInt(buf.get());
                rsn = Byte.toUnsignedInt(buf.get());
            }
            case "DRSD" -> {
                rsn = Byte.toUnsignedInt(buf.get());
            }
            case "TMPT", "RCWN", "DTSV" -> {
                vIdx = Byte.toUnsignedInt(buf.get());
            }
            case "PENA" -> {
                pt = Byte.toUnsignedInt(buf.get());
                it = Byte.toUnsignedInt(buf.get());
                vIdx = Byte.toUnsignedInt(buf.get());
                ovi = Byte.toUnsignedInt(buf.get());
                tm = Byte.toUnsignedInt(buf.get());
                ln = Byte.toUnsignedInt(buf.get());
                pg = Byte.toUnsignedInt(buf.get());
            }
            case "SPTP" -> {
                vIdx = Byte.toUnsignedInt(buf.get());
                spd = buf.getFloat();
                // skip remaining SpeedTrap fields
            }
            case "SGSV" -> {
                vIdx = Byte.toUnsignedInt(buf.get());
                buf.getFloat(); // stopTime
            }
            case "STLG" -> {
                buf.get(); // numLights
            }
            case "FLBK" -> {
                buf.getInt();   // flashbackFrameIdentifier
                buf.getFloat(); // flashbackSessionTime
            }
            case "BUTN" -> {
                buf.getInt(); // buttonStatus
            }
            case "OVTK" -> {
                v1 = Byte.toUnsignedInt(buf.get());
                v2 = Byte.toUnsignedInt(buf.get());
            }
            case "SCAR" -> {
                sct = Byte.toUnsignedInt(buf.get());
                et = Byte.toUnsignedInt(buf.get());
            }
            case "COLL" -> {
                v1 = Byte.toUnsignedInt(buf.get());
                v2 = Byte.toUnsignedInt(buf.get());
            }
            // SSTA, SEND, CHQF, DRSE, LGOT, RDFL — no detail fields
            default -> {}
        }

        this.vehicleIdx = vIdx;
        this.lapTime = lt;
        this.reason = rsn;
        this.penaltyType = pt;
        this.infringementType = it;
        this.otherVehicleIdx = ovi;
        this.time = tm;
        this.lapNum = ln;
        this.placesGained = pg;
        this.speed = spd;
        this.safetyCarType = sct;
        this.eventType = et;
        this.vehicle1Idx = v1;
        this.vehicle2Idx = v2;
    }

    public static EventData parse(byte[] data, int length) {
        // header(29) + eventCode(4) + detail(variable, at least a few bytes)
        if (length < PacketHeader.HEADER_SIZE + 4) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);

        byte[] codeBytes = new byte[4];
        buf.get(codeBytes);
        String code = new String(codeBytes, StandardCharsets.US_ASCII);

        return new EventData(code, buf);
    }
}
