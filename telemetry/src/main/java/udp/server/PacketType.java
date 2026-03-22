package udp.server;

public enum PacketType {
    MOTION(0, "Motion"),
    SESSION(1, "Session"),
    LAP_DATA(2, "LapData"),
    EVENT(3, "Event"),
    PARTICIPANTS(4, "Participants"),
    CAR_SETUPS(5, "CarSetups"),
    CAR_TELEMETRY(6, "CarTelemetry"),
    CAR_STATUS(7, "CarStatus"),
    FINAL_CLASSIFICATION(8, "FinalClassification"),
    LOBBY_INFO(9, "LobbyInfo"),
    CAR_DAMAGE(10, "CarDamage"),
    SESSION_HISTORY(11, "SessionHistory"),
    TYRE_SETS(12, "TyreSets"),
    MOTION_EX(13, "MotionEx"),
    TIME_TRIAL(14, "TimeTrial"),
    LAP_POSITIONS(15, "LapPositions");

    private final int id;
    private final String label;

    PacketType(int id, String label) {
        this.id = id;
        this.label = label;
    }

    public int getId() { return id; }
    public String getLabel() { return label; }

    public static String fromId(int id) {
        for (PacketType type : values()) {
            if (type.id == id) return type.label;
        }
        return "Unknown";
    }
}
