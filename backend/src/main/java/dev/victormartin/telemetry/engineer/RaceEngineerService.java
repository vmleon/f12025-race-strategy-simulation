package dev.victormartin.telemetry.engineer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.simulation.RaceSnapshot;
import dev.victormartin.telemetry.simulation.StrategyEvaluation;

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

    // -- session lifecycle -----------------------------------------------------

    public void onSessionStarted(String sessionUid, int trackId) {
        SessionEngineerState session = new SessionEngineerState(sessionUid, trackId);
        sessions.put(sessionUid, session);
        session.queue.enqueue(new EngineerMessage(
                Priority.NORMAL,
                "Radio check. All systems nominal.",
                System.currentTimeMillis(), 0, 5));
        System.out.println("RaceEngineerService: queue created for session " + sessionUid);
    }

    public void onSessionEnded(String sessionUid) {
        SessionEngineerState removed = sessions.remove(sessionUid);
        if (removed != null) {
            removed.queue.clear();
            System.out.println("RaceEngineerService: queue destroyed for session " + sessionUid);
        }
    }

    // -- state update (called ~1Hz) --------------------------------------------

    public void onStateUpdate(String json) {
        try {
            JsonNode state = objectMapper.readTree(json);
            JsonNode carsNode = state.get("cars");
            if (carsNode == null || !carsNode.isArray()) return;

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
            int trackLength = state.has("trackLength") ? state.get("trackLength").asInt() : 5000;

            // Detect changes and generate messages
            detectFlagChanges(session, state, currentLap);
            detectPenalties(session, playerCar, currentLap);
            detectCarBehind(session, carsNode, playerCar, currentLap, trackLength);
            detectCarAhead(session, carsNode, playerCar, currentLap, trackLength);
            detectLapCountdown(session, currentLap, totalLaps);
            detectTyreCondition(session, playerCar, currentLap);
            detectPositionChange(session, playerCar, currentLap);
            detectPitStopCompleted(session, playerCar, currentLap);
            detectDrs(session, playerCar, currentLap);
            detectFuelLevel(session, playerCar, currentLap, totalLaps);
            detectErsMode(session, playerCar, currentLap);
            detectWeatherChange(session, state, currentLap);
            detectRaceFinish(session, playerCar, currentLap);

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

    // -- event handling --------------------------------------------------------

    public void onEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String event = node.has("event") ? node.get("event").asText() : "";
            int trackId = node.has("trackId") ? node.get("trackId").asInt() : -1;

            // CHQF is session-wide (no trackId) -- find any active session
            SessionEngineerState session = trackId >= 0
                    ? findSessionByTrackId(trackId)
                    : findAnyActiveSession();
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
                case "CHQF" -> session.chequeredFlag = true;
            }
        } catch (Exception e) {
            System.err.println("RaceEngineerService: error processing event: " + e.getMessage());
        }
    }

    // -- strategy evaluation callback ------------------------------------------

    public void onStrategyEvaluation(int evaluatedAtLap, StrategyEvaluation evaluation) {
        if (evaluation == null || evaluation.strategies() == null || evaluation.strategies().isEmpty()) return;

        StrategyEvaluation.RankedStrategy best = evaluation.strategies().getFirst();
        List<RaceSnapshot.PitStrategy.PitStop> stops = best.candidate().stops();
        if (stops == null || stops.isEmpty()) return;

        for (SessionEngineerState session : sessions.values()) {
            int currentLap = session.lastPlayerLap;
            for (RaceSnapshot.PitStrategy.PitStop stop : stops) {
                if (stop.onLap() > currentLap) {
                    int lapsUntil = stop.onLap() - currentLap;
                    String compound = mapCompoundName(stop.newCompound());
                    session.queue.enqueue(new EngineerMessage(
                            Priority.NORMAL,
                            "Box window opens in " + lapsUntil + " laps. " + compound + " ready.",
                            System.currentTimeMillis(), currentLap, 3));
                    break;
                }
            }
        }
    }

    // -- message generation ----------------------------------------------------

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
                                  JsonNode playerCar, int currentLap, int trackLength) {
        int playerPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
        float playerLapDist = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;
        int playerLap = playerCar.has("lap") ? playerCar.get("lap").asInt() : 0;

        JsonNode carBehind = null;
        for (JsonNode car : carsNode) {
            int pos = car.has("pos") ? car.get("pos").asInt() : 0;
            if (pos == playerPos + 1) {
                carBehind = car;
                break;
            }
        }

        if (carBehind != null) {
            float behindLapDist = carBehind.has("lapDist") ? (float) carBehind.get("lapDist").asDouble() : 0f;
            int behindLap = carBehind.has("lap") ? carBehind.get("lap").asInt() : 0;
            String behindName = carBehind.has("name") ? carBehind.get("name").asText() : "Car behind";

            if (behindLap == playerLap) {
                float gap = playerLapDist - behindLapDist;
                if (gap < 0) gap += trackLength;

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

    private void detectCarAhead(SessionEngineerState session, JsonNode carsNode,
                                 JsonNode playerCar, int currentLap, int trackLength) {
        int playerPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
        if (playerPos <= 1) {
            session.previousGapAheadSeconds = -1f;
            return; // Already in P1, no car ahead
        }

        float playerLapDist = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;
        int playerLap = playerCar.has("lap") ? playerCar.get("lap").asInt() : 0;

        JsonNode carAhead = null;
        for (JsonNode car : carsNode) {
            int pos = car.has("pos") ? car.get("pos").asInt() : 0;
            if (pos == playerPos - 1) {
                carAhead = car;
                break;
            }
        }

        if (carAhead != null) {
            float aheadLapDist = carAhead.has("lapDist") ? (float) carAhead.get("lapDist").asDouble() : 0f;
            int aheadLap = carAhead.has("lap") ? carAhead.get("lap").asInt() : 0;

            if (aheadLap == playerLap) {
                float gap = aheadLapDist - playerLapDist;
                if (gap < 0) gap += trackLength;

                float gapSeconds = gap / 55f;

                boolean isInDrsRange = gapSeconds < 1.0f;
                boolean wasInDrsRange = session.previousGapAheadSeconds >= 0 && session.previousGapAheadSeconds < 1.0f;

                if (isInDrsRange && !wasInDrsRange) {
                    session.queue.enqueue(new EngineerMessage(
                            Priority.NORMAL,
                            "You have DRS. Attack.",
                            System.currentTimeMillis(), currentLap, 1));
                }
                session.previousGapAheadSeconds = gapSeconds;
            }
        } else {
            session.previousGapAheadSeconds = -1f;
        }
    }

    private void detectLapCountdown(SessionEngineerState session, int currentLap, int totalLaps) {
        if (totalLaps <= 0) return;
        int lapsRemaining = totalLaps - currentLap;

        if (currentLap > session.lastPlayerLap) {
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

    private void detectPositionChange(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        int currentPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;

        if (session.previousPlayerPosition > 0 && currentPos < session.previousPlayerPosition) {
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "Good move. P" + currentPos + ". Keep it clean.",
                    System.currentTimeMillis(), currentLap, 1));
        }
        session.previousPlayerPosition = currentPos;
    }

    private void detectPitStopCompleted(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        int pitStatus = playerCar.has("pitStatus") ? playerCar.get("pitStatus").asInt() : 0;
        int pitCount = playerCar.has("pits") ? playerCar.get("pits").asInt() : 0;

        // Track pit entry time
        if (pitStatus > 0 && session.previousPitStatus == 0) {
            session.pitEnteredAt = System.currentTimeMillis();
        }

        // Pit exit detected: pitStatus back to 0, pit count increased
        if (pitStatus == 0 && session.previousPitStatus > 0 && pitCount > session.previousPitCount) {
            if (session.pitEnteredAt > 0) {
                float durationSeconds = (System.currentTimeMillis() - session.pitEnteredAt) / 1000f;
                session.queue.enqueue(new EngineerMessage(
                        Priority.NORMAL,
                        "Good stop. " + String.format("%.1f", durationSeconds) + " seconds. Push now.",
                        System.currentTimeMillis(), currentLap, 1));
                session.pitEnteredAt = 0;
            }
        }
        session.previousPitStatus = pitStatus;
        session.previousPitCount = pitCount;
    }

    private void detectDrs(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        int drsAllowed = playerCar.has("drsAllowed") ? playerCar.get("drsAllowed").asInt() : 0;

        if (drsAllowed > 0 && session.previousDrsAllowed == 0) {
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "DRS enabled.",
                    System.currentTimeMillis(), currentLap, 1));
        }
        session.previousDrsAllowed = drsAllowed;
    }

    private void detectFuelLevel(SessionEngineerState session, JsonNode playerCar,
                                  int currentLap, int totalLaps) {
        float fuel = playerCar.has("fuel") ? (float) playerCar.get("fuel").asDouble() : -1f;
        if (fuel < 0 || totalLaps <= 0) return;

        int lapsRemaining = totalLaps - currentLap;
        if (lapsRemaining <= 0) return;

        // Track initial fuel to estimate burn rate
        if (session.initialFuel < 0 && currentLap >= 2) {
            session.initialFuel = fuel;
            session.initialFuelLap = currentLap;
        }

        if (session.initialFuel > 0 && currentLap > session.initialFuelLap && !session.fuelAlertSent) {
            float lapsElapsed = currentLap - session.initialFuelLap;
            float fuelPerLap = (session.initialFuel - fuel) / lapsElapsed;
            if (fuelPerLap > 0) {
                float fuelNeeded = fuelPerLap * lapsRemaining;
                if (fuel < fuelNeeded) {
                    session.queue.enqueue(new EngineerMessage(
                            Priority.NORMAL,
                            "Fuel is critical. Lift and coast through the slow corners.",
                            System.currentTimeMillis(), currentLap, 3));
                    session.fuelAlertSent = true;
                }
            }
        }
    }

    private void detectErsMode(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        int ersMode = playerCar.has("ersMode") ? playerCar.get("ersMode").asInt() : -1;
        if (ersMode < 0) return;

        if (session.previousErsMode >= 0 && ersMode != session.previousErsMode) {
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "ERS mode " + ersMode + ". Go to strat " + ersMode + ".",
                    System.currentTimeMillis(), currentLap, 1));
        }
        session.previousErsMode = ersMode;
    }

    private void detectWeatherChange(SessionEngineerState session, JsonNode state, int currentLap) {
        int weather = state.has("weather") ? state.get("weather").asInt() : 0;

        // Check forecast for incoming rain when currently dry
        if (weather == 0 && !session.weatherAlertSent) {
            JsonNode forecast = state.get("forecast");
            if (forecast != null && forecast.isArray()) {
                for (JsonNode sample : forecast) {
                    int rain = sample.has("rain") ? sample.get("rain").asInt() : 0;
                    int offset = sample.has("offset") ? sample.get("offset").asInt() : 0;
                    if (rain > 30 && offset > 0) {
                        session.queue.enqueue(new EngineerMessage(
                                Priority.NORMAL,
                                "Rain expected in " + offset + " minutes. Stay out for now.",
                                System.currentTimeMillis(), currentLap, 3));
                        session.weatherAlertSent = true;
                        break;
                    }
                }
            }
        }

        // Reset alert if weather changed (rain started, may clear later)
        if (weather != session.previousWeather && session.previousWeather >= 0) {
            session.weatherAlertSent = false;
        }
        session.previousWeather = weather;
    }

    private void detectRaceFinish(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        if (session.raceFinished) return;

        int resultStatus = playerCar.has("resultStatus") ? playerCar.get("resultStatus").asInt() : 2;
        // resultStatus 3 = finished
        if (resultStatus == 3 || session.chequeredFlag) {
            session.raceFinished = true;
            int pos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "That's P" + pos + ". Good job today.",
                    System.currentTimeMillis(), currentLap, 5));
        }
    }

    // -- delivery --------------------------------------------------------------

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

    // -- helpers ---------------------------------------------------------------

    private SessionEngineerState findSessionByTrackId(int trackId) {
        for (SessionEngineerState s : sessions.values()) {
            if (s.trackId == trackId) return s;
        }
        return null;
    }

    private SessionEngineerState findAnyActiveSession() {
        return sessions.values().stream().findFirst().orElse(null);
    }

    private static String mapCompoundName(int compound) {
        return switch (compound) {
            case 16 -> "Softs";
            case 17 -> "Mediums";
            case 18 -> "Hards";
            case 7 -> "Inters";
            case 8 -> "Wets";
            default -> "Tyres";
        };
    }

    // -- per-session mutable state ---------------------------------------------

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

        // Position gained
        int previousPlayerPosition = 0;

        // Pit stop completed
        int previousPitStatus = 0;
        int previousPitCount = 0;
        long pitEnteredAt = 0;

        // DRS
        int previousDrsAllowed = 0;

        // DRS range (car ahead)
        float previousGapAheadSeconds = -1f;

        // Fuel management
        float initialFuel = -1f;
        int initialFuelLap = 0;
        boolean fuelAlertSent = false;

        // ERS mode
        int previousErsMode = -1;

        // Weather
        int previousWeather = -1;
        boolean weatherAlertSent = false;

        // Race finish
        boolean chequeredFlag = false;
        boolean raceFinished = false;

        SessionEngineerState(String sessionUid, int trackId) {
            this.sessionUid = sessionUid;
            this.trackId = trackId;
        }
    }
}
