package udp.server;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Mutable holder for the latest race snapshot.
 * Updated by the packet-processor thread, read by the tcp-sender thread.
 * All access is synchronized on this instance.
 */
public class RaceState {

    private static final int NUM_CARS = 22;

    // Session-level fields
    private long sessionUid;
    private int trackId;
    private int weather;
    private int trackTemp;
    private int airTemp;
    private int safetyCarStatus;
    private int totalLaps;
    private int trackLength;

    // Per-car state
    private final CarState[] cars = new CarState[NUM_CARS];

    // Session lifecycle tracking
    private boolean sessionActive;
    private boolean sessionStartSent;
    private boolean sessionEndSent;

    // Event queue for forwarding to backend
    private final Queue<String> eventQueue = new ArrayDeque<>();

    public RaceState() {
        for (int i = 0; i < NUM_CARS; i++) {
            cars[i] = new CarState();
        }
    }

    public static class CarState {
        int position;
        int lap;
        int sector;
        long[] lastSectorMs = new long[3];
        String tyreCompound = "M";
        int tyreAge;
        int pitStatus;
        float fuelInTank;
        int numPitStops;
        int frontWingDamage;
        int floorDamage;
        int engineDamage;
        String driverName = "";
        boolean aiControlled = true;
        int resultStatus;
        float lapDistance;
        int teamId;
    }

    public synchronized void updateFromSession(long sessionUid, SessionData session) {
        this.sessionUid = sessionUid;
        this.trackId = session.trackId;
        this.weather = session.weather;
        this.trackTemp = session.trackTemperature;
        this.airTemp = session.airTemperature;
        this.safetyCarStatus = session.safetyCarStatus;
        this.totalLaps = session.totalLaps;
        this.trackLength = session.trackLength;
        this.sessionActive = true;
    }

    public synchronized void updateFromLapData(LapData[] laps) {
        for (int i = 0; i < Math.min(laps.length, NUM_CARS); i++) {
            LapData lap = laps[i];
            cars[i].position = lap.carPosition;
            cars[i].lap = lap.currentLapNum;
            cars[i].sector = lap.sector;
            cars[i].lastSectorMs[0] = lap.sector1TimeInMS();
            cars[i].lastSectorMs[1] = lap.sector2TimeInMS();
            cars[i].lastSectorMs[2] = 0; // sector 3 not directly available
            cars[i].pitStatus = lap.pitStatus;
            cars[i].numPitStops = lap.numPitStops;
            cars[i].resultStatus = lap.resultStatus;
            cars[i].lapDistance = lap.lapDistance;
        }
    }

    public synchronized void updateFromStatus(CarStatusData[] status) {
        for (int i = 0; i < Math.min(status.length, NUM_CARS); i++) {
            cars[i].tyreCompound = mapTyreCompound(status[i].visualTyreCompound);
            cars[i].tyreAge = status[i].tyresAgeLaps;
            cars[i].fuelInTank = status[i].fuelInTank;
        }
    }

    public synchronized void updateFromDamage(CarDamageData[] damage) {
        for (int i = 0; i < Math.min(damage.length, NUM_CARS); i++) {
            cars[i].frontWingDamage = Math.max(damage[i].frontLeftWingDamage, damage[i].frontRightWingDamage);
            cars[i].floorDamage = damage[i].floorDamage;
            cars[i].engineDamage = damage[i].engineDamage;
        }
    }

    public synchronized void updateFromParticipants(ParticipantData[] participants) {
        for (int i = 0; i < Math.min(participants.length, NUM_CARS); i++) {
            cars[i].driverName = participants[i].name;
            cars[i].aiControlled = participants[i].aiControlled == 1;
            cars[i].teamId = participants[i].teamId;
        }
    }

    public synchronized void queueEvent(String eventJson) {
        eventQueue.add(eventJson);
    }

    public synchronized String pollEvent() {
        return eventQueue.poll();
    }

    public synchronized void markSessionEnded() {
        this.sessionActive = false;
    }

    /**
     * Returns a "sessionStarted" JSON line if a new session was detected, or null.
     * Only returns once per session.
     */
    public synchronized String pollSessionStarted() {
        if (sessionActive && !sessionStartSent) {
            sessionStartSent = true;
            sessionEndSent = false;
            return "{\"type\":\"sessionStarted\",\"sessionUid\":\"" + Long.toHexString(sessionUid)
                    + "\",\"trackId\":" + trackId + "}";
        }
        return null;
    }

    /**
     * Returns a "sessionEnded" JSON line if the session ended, or null.
     * Only returns once per session end.
     */
    public synchronized String pollSessionEnded() {
        if (!sessionActive && sessionStartSent && !sessionEndSent) {
            sessionEndSent = true;
            sessionStartSent = false;
            return "{\"type\":\"sessionEnded\",\"sessionUid\":\"" + Long.toHexString(sessionUid) + "\"}";
        }
        return null;
    }

    /**
     * Serialize the current race state as a JSON line (no trailing newline).
     */
    public synchronized String toJsonLine() {
        if (!sessionActive) return null;

        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"type\":\"state\",\"sessionUid\":\"").append(Long.toHexString(sessionUid))
          .append("\",\"trackId\":").append(trackId)
          .append(",\"totalLaps\":").append(totalLaps)
          .append(",\"weather\":").append(weather)
          .append(",\"trackTemp\":").append(trackTemp)
          .append(",\"airTemp\":").append(airTemp)
          .append(",\"safetyCarStatus\":").append(safetyCarStatus)
          .append(",\"trackLength\":").append(trackLength)
          .append(",\"cars\":[");

        for (int i = 0; i < NUM_CARS; i++) {
            if (i > 0) sb.append(',');
            CarState c = cars[i];
            sb.append("{\"idx\":").append(i)
              .append(",\"pos\":").append(c.position)
              .append(",\"lap\":").append(c.lap)
              .append(",\"sector\":").append(c.sector)
              .append(",\"lastSectorMs\":[").append(c.lastSectorMs[0])
              .append(',').append(c.lastSectorMs[1])
              .append(',').append(c.lastSectorMs[2])
              .append("],\"tyre\":\"").append(c.tyreCompound)
              .append("\",\"tyreAge\":").append(c.tyreAge)
              .append(",\"pitStatus\":").append(c.pitStatus)
              .append(",\"fuel\":").append(String.format("%.1f", c.fuelInTank))
              .append(",\"pits\":").append(c.numPitStops)
              .append(",\"fwDmg\":").append(c.frontWingDamage)
              .append(",\"flDmg\":").append(c.floorDamage)
              .append(",\"engDmg\":").append(c.engineDamage)
              .append(",\"name\":\"").append(escapeJson(c.driverName))
              .append("\",\"ai\":").append(c.aiControlled)
              .append(",\"resultStatus\":").append(c.resultStatus)
              .append(",\"lapDist\":").append(String.format("%.1f", c.lapDistance))
              .append(",\"teamId\":").append(c.teamId)
              .append('}');
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Fill with fake data for end-to-end testing.
     */
    public synchronized void fillTestData() {
        this.sessionUid = 0xDEADBEEFCAFEBABEL;
        this.trackId = 3;
        this.weather = 0;
        this.trackTemp = 32;
        this.airTemp = 24;
        this.safetyCarStatus = 0;
        this.totalLaps = 50;
        this.trackLength = 5303;
        this.sessionActive = true;

        String[] compounds = {"S", "M", "H", "S", "M"};
        int[] teams = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1};
        String[] names = {"Player", "Verstappen", "Hamilton", "Leclerc", "Norris",
                          "Sainz", "Russell", "Piastri", "Alonso", "Stroll",
                          "Gasly", "Ocon", "Albon", "Sargeant", "Bottas",
                          "Zhou", "Tsunoda", "Ricciardo", "Magnussen", "Hulkenberg",
                          "Perez", "Lawson"};
        for (int i = 0; i < NUM_CARS; i++) {
            cars[i].position = i + 1;
            cars[i].lap = 15;
            cars[i].sector = i % 3;
            cars[i].lastSectorMs[0] = 28000 + i * 100;
            cars[i].lastSectorMs[1] = 33000 + i * 100;
            cars[i].lastSectorMs[2] = 0;
            cars[i].tyreCompound = compounds[i % compounds.length];
            cars[i].tyreAge = 7 + i;
            cars[i].pitStatus = 0;
            cars[i].fuelInTank = 50.0f - i * 1.5f;
            cars[i].numPitStops = 0;
            cars[i].frontWingDamage = 0;
            cars[i].floorDamage = 0;
            cars[i].engineDamage = 0;
            cars[i].driverName = names[i];
            cars[i].aiControlled = i > 0;
            cars[i].resultStatus = 2; // active
            cars[i].lapDistance = (float) (5303.0 * i / NUM_CARS);
            cars[i].teamId = teams[i];
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String mapTyreCompound(int visual) {
        return switch (visual) {
            case 16 -> "S";
            case 17 -> "M";
            case 18 -> "H";
            case 7 -> "I";
            case 8 -> "W";
            default -> "?";
        };
    }
}
