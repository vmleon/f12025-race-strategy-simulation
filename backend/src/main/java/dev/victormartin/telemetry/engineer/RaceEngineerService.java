package dev.victormartin.telemetry.engineer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;

/**
 * Orchestrates the race engineer message queue.
 * Reacts to live race state, generates messages, and delivers them
 * to clients via WebSocket when the player is in a safe zone.
 */
@Component
public class RaceEngineerService {

    private final CircuitSafeZoneService safeZoneService;
    private final RaceEngineerWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, SessionEngineerState> sessions = new ConcurrentHashMap<>();

    public RaceEngineerService(CircuitSafeZoneService safeZoneService,
                               RaceEngineerWebSocketHandler webSocketHandler) {
        this.safeZoneService = safeZoneService;
        this.webSocketHandler = webSocketHandler;
    }

    // ── session lifecycle ─────────────────────────────────────────────

    public void onSessionStarted(String sessionUid, int trackId) {
        sessions.put(sessionUid, new SessionEngineerState(sessionUid, trackId));
        System.out.println("RaceEngineerService: queue created for session " + sessionUid);
    }

    public void onSessionEnded(String sessionUid) {
        SessionEngineerState removed = sessions.remove(sessionUid);
        if (removed != null) {
            removed.queue.clear();
            System.out.println("RaceEngineerService: queue destroyed for session " + sessionUid);
        }
    }

    // ── state update (called ~1Hz) ────────────────────────────────────

    public void onStateUpdate(String json) {
        try {
            JsonNode state = objectMapper.readTree(json);
            JsonNode carsNode = state.get("cars");
            if (carsNode == null || !carsNode.isArray()) return;

            // Find the active session for this state update
            // State messages don't include sessionUid, so match by trackId against active sessions
            int trackId = state.has("trackId") ? state.get("trackId").asInt() : -1;
            SessionEngineerState session = findSessionByTrackId(trackId);
            if (session == null) return;

            // Find the player car (ai=false)
            JsonNode playerCar = null;
            for (JsonNode car : carsNode) {
                if (car.has("ai") && !car.get("ai").asBoolean()) {
                    playerCar = car;
                    break;
                }
            }
            if (playerCar == null) return;

            int currentLap = playerCar.has("lap") ? playerCar.get("lap").asInt() : 1;
            float lapDistance = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;
            int totalLaps = state.has("totalLaps") ? state.get("totalLaps").asInt() : 0;

            // Detect changes and generate messages
            detectFlagChanges(session, state, currentLap);
            detectPenalties(session, playerCar, currentLap);
            detectCarBehind(session, carsNode, playerCar, currentLap);
            detectLapCountdown(session, currentLap, totalLaps);
            detectTyreCondition(session, playerCar, currentLap);

            // Try to deliver a message
            EngineerMessage message = session.queue.pollForDelivery(
                    lapDistance, session.trackId, currentLap, safeZoneService);
            if (message != null) {
                deliverMessage(session.sessionUid, message);
            }

        } catch (Exception e) {
            System.err.println("RaceEngineerService: error processing state: " + e.getMessage());
        }
    }

    // ── event handling ────────────────────────────────────────────────

    public void onEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String event = node.has("event") ? node.get("event").asText() : "";
            int trackId = node.has("trackId") ? node.get("trackId").asInt() : -1;

            SessionEngineerState session = findSessionByTrackId(trackId);
            if (session == null) return;

            switch (event) {
                case "SCAR" -> session.queue.enqueue(new EngineerMessage(
                        Priority.IMMEDIATE,
                        "Safety car deployed. Bunch up, stay within ten car lengths. We'll talk strategy.",
                        System.currentTimeMillis(), session.lastPlayerLap, 3));
                case "RTMT" -> {
                    int carIdx = node.has("carIndex") ? node.get("carIndex").asInt() : -1;
                    String name = node.has("driverName") ? node.get("driverName").asText() : "A car";
                    session.queue.enqueue(new EngineerMessage(
                            Priority.NORMAL,
                            name + " has retired. Watch for debris on track.",
                            System.currentTimeMillis(), session.lastPlayerLap, 2));
                }
                case "COLL" -> session.queue.enqueue(new EngineerMessage(
                        Priority.HIGH,
                        "Collision ahead. Stay alert, watch for yellow flags.",
                        System.currentTimeMillis(), session.lastPlayerLap, 1));
            }
        } catch (Exception e) {
            System.err.println("RaceEngineerService: error processing event: " + e.getMessage());
        }
    }

    // ── message generation ────────────────────────────────────────────

    private void detectFlagChanges(SessionEngineerState session, JsonNode state, int currentLap) {
        int safetyCarStatus = state.has("safetyCarStatus") ? state.get("safetyCarStatus").asInt() : 0;

        if (safetyCarStatus != session.previousSafetyCarStatus) {
            if (safetyCarStatus == 0 && session.previousSafetyCarStatus > 0) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.IMMEDIATE,
                        "Safety car coming in. Green flag next lap. Push now, push now.",
                        System.currentTimeMillis(), currentLap, 2));
            }
            // Safety car deployment is handled via onEvent("SCAR")
            session.previousSafetyCarStatus = safetyCarStatus;
        }
    }

    private void detectPenalties(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        int penalties = playerCar.has("pen") ? playerCar.get("pen").asInt() : 0;
        int unservedDT = playerCar.has("unservedDT") ? playerCar.get("unservedDT").asInt() : 0;
        int unservedSG = playerCar.has("unservedSG") ? playerCar.get("unservedSG").asInt() : 0;
        int warnings = playerCar.has("warnings") ? playerCar.get("warnings").asInt() : 0;

        if (penalties > session.previousPenaltySeconds) {
            int added = penalties - session.previousPenaltySeconds;
            session.queue.enqueue(new EngineerMessage(
                    Priority.HIGH,
                    "Penalty received. " + added + " seconds added. We'll talk strategy.",
                    System.currentTimeMillis(), currentLap, 3));
        }
        session.previousPenaltySeconds = penalties;

        int totalUnserved = unservedDT + unservedSG;
        if (totalUnserved > session.previousUnservedPenalties) {
            String type = unservedDT > session.previousUnservedDT ? "drive-through" : "stop-go";
            session.queue.enqueue(new EngineerMessage(
                    Priority.HIGH,
                    "You have an unserved " + type + " penalty. Box this lap.",
                    System.currentTimeMillis(), currentLap, 2));
        }
        session.previousUnservedDT = unservedDT;
        session.previousUnservedSG = unservedSG;
        session.previousUnservedPenalties = totalUnserved;

        if (warnings > session.previousWarnings) {
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "Track limits warning. That's warning number " + warnings + ". Be careful.",
                    System.currentTimeMillis(), currentLap, 2));
        }
        session.previousWarnings = warnings;
    }

    private void detectCarBehind(SessionEngineerState session, JsonNode carsNode,
                                  JsonNode playerCar, int currentLap) {
        int playerPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
        float playerLapDist = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;
        int playerLap = playerCar.has("lap") ? playerCar.get("lap").asInt() : 0;

        // Find the car directly behind
        JsonNode carBehind = null;
        for (JsonNode car : carsNode) {
            int pos = car.has("pos") ? car.get("pos").asInt() : 0;
            if (pos == playerPos + 1) {
                carBehind = car;
                break;
            }
        }

        if (carBehind != null) {
            // Estimate gap using lap distance difference (rough approximation)
            float behindLapDist = carBehind.has("lapDist") ? (float) carBehind.get("lapDist").asDouble() : 0f;
            int behindLap = carBehind.has("lap") ? carBehind.get("lap").asInt() : 0;
            String behindName = carBehind.has("name") ? carBehind.get("name").asText() : "Car behind";

            // Only alert if same lap and gap is closing
            if (behindLap == playerLap) {
                float gap = playerLapDist - behindLapDist;
                if (gap < 0) gap += 5000; // wrap around (rough track length estimate)

                // Convert distance to rough time gap (assume ~200 km/h average = ~55 m/s)
                float gapSeconds = gap / 55f;

                boolean isClose = gapSeconds < 2.0f;
                boolean wasClose = session.previousGapBehindSeconds < 2.0f && session.previousGapBehindSeconds > 0;

                if (isClose && !wasClose) {
                    session.queue.enqueue(new EngineerMessage(
                            Priority.NORMAL,
                            behindName + " closing from behind. " + String.format("%.1f", gapSeconds) + " seconds back. Defend your position.",
                            System.currentTimeMillis(), currentLap, 1));
                }
                session.previousGapBehindSeconds = gapSeconds;
            }
        }
    }

    private void detectLapCountdown(SessionEngineerState session, int currentLap, int totalLaps) {
        if (totalLaps <= 0) return;
        int lapsRemaining = totalLaps - currentLap;

        if (currentLap > session.lastPlayerLap) {
            // Lap changed
            if (lapsRemaining == 10) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.NORMAL,
                        "10 laps remaining. Keep it clean, manage your tyres.",
                        System.currentTimeMillis(), currentLap, 1));
            } else if (lapsRemaining == 5) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.NORMAL,
                        "5 laps to go. Bring it home.",
                        System.currentTimeMillis(), currentLap, 1));
            } else if (lapsRemaining == 1) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.HIGH,
                        "Last lap. Give it everything you've got.",
                        System.currentTimeMillis(), currentLap, 1));
            }
            session.lastPlayerLap = currentLap;
        }
    }

    private void detectTyreCondition(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        int tyreAge = playerCar.has("tyreAge") ? playerCar.get("tyreAge").asInt() : 0;

        // Alert at certain tyre age thresholds (only once per threshold)
        if (tyreAge >= 20 && session.lastTyreAgeAlert < 20) {
            String compound = playerCar.has("tyre") ? playerCar.get("tyre").asText() : "tyres";
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    compound + " tyres are " + tyreAge + " laps old. Consider a pit stop.",
                    System.currentTimeMillis(), currentLap, 3));
            session.lastTyreAgeAlert = tyreAge;
        } else if (tyreAge >= 30 && session.lastTyreAgeAlert < 30) {
            session.queue.enqueue(new EngineerMessage(
                    Priority.HIGH,
                    "Tyres are " + tyreAge + " laps old and degrading. Box soon.",
                    System.currentTimeMillis(), currentLap, 2));
            session.lastTyreAgeAlert = tyreAge;
        }

        // Reset alert tracking on tyre change (age dropped)
        if (tyreAge < session.previousTyreAge) {
            session.lastTyreAgeAlert = 0;
            String newCompound = playerCar.has("tyre") ? playerCar.get("tyre").asText() : "new";
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "Copy, new " + newCompound + " tyres on. Take it easy for the out lap.",
                    System.currentTimeMillis(), currentLap, 1));
        }
        session.previousTyreAge = tyreAge;
    }

    // ── delivery ──────────────────────────────────────────────────────

    private void deliverMessage(String sessionUid, EngineerMessage message) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "raceEngineer",
                    "sessionUid", sessionUid,
                    "priority", message.priority().name(),
                    "text", message.text(),
                    "timestamp", message.createdAt()));
            webSocketHandler.broadcast(json);
            System.out.println("RaceEngineer [" + message.priority() + "]: " + message.text());
        } catch (Exception e) {
            System.err.println("RaceEngineerService: failed to deliver message: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────

    private SessionEngineerState findSessionByTrackId(int trackId) {
        for (SessionEngineerState s : sessions.values()) {
            if (s.trackId == trackId) return s;
        }
        return null;
    }

    // ── per-session mutable state ─────────────────────────────────────

    static class SessionEngineerState {
        final String sessionUid;
        final int trackId;
        final RaceEngineerQueue queue = new RaceEngineerQueue();

        int lastPlayerLap = 0;
        int previousSafetyCarStatus = 0;
        int previousPenaltySeconds = 0;
        int previousUnservedDT = 0;
        int previousUnservedSG = 0;
        int previousUnservedPenalties = 0;
        int previousWarnings = 0;
        float previousGapBehindSeconds = -1f;
        int previousTyreAge = 0;
        int lastTyreAgeAlert = 0;

        SessionEngineerState(String sessionUid, int trackId) {
            this.sessionUid = sessionUid;
            this.trackId = trackId;
        }
    }
}
