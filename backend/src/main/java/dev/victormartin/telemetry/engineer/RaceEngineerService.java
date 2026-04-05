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

    public void onSessionStarted(String sessionUid, int trackId, int sessionType, int ersAssist, int drsAssist) {
        SessionEngineerState session = new SessionEngineerState(sessionUid, trackId, sessionType, ersAssist, drsAssist);
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
            int speedKmh = playerCar.has("speed") ? playerCar.get("speed").asInt() : 0;

            // Keep assist flags in sync with telemetry (session packet may update them)
            if (state.has("ersAssist")) session.ersAssist = state.get("ersAssist").asInt();
            if (state.has("drsAssist")) session.drsAssist = state.get("drsAssist").asInt();

            // Detect changes and generate messages.
            // Always-on detectors (apply to practice/quali/race): flags, penalties, tyre condition,
            // per-corner wear, DRS/ERS mode changes, weather.
            detectFlagChanges(session, state, currentLap);
            detectPenalties(session, playerCar, currentLap);
            detectTyreCondition(session, playerCar, currentLap);
            detectPerCornerTyreWear(session, playerCar, currentLap);
            if (session.drsAssist == 0) {
                detectDrs(session, playerCar, currentLap);
            }
            if (session.ersAssist == 0) {
                detectErsMode(session, playerCar, currentLap);
            }
            detectWeatherChange(session, state, currentLap);

            // Race-only detectors.
            if (session.isRace()) {
                detectCarBehind(session, carsNode, playerCar, currentLap, trackLength);
                detectLapCountdown(session, currentLap, totalLaps);
                detectPositionChange(session, carsNode, playerCar, currentLap, trackLength);
                detectPitStopCompleted(session, carsNode, playerCar, currentLap, trackLength);
                detectPitWindowMessages(session, playerCar, currentLap, trackLength);
                if (session.drsAssist == 0) {
                    detectCarAhead(session, carsNode, playerCar, currentLap, trackLength);
                }
                detectFuelLevel(session, playerCar, currentLap, totalLaps);
                detectPeriodicSituationalAwareness(session, carsNode, playerCar, currentLap, totalLaps, trackLength);
                detectRaceFinish(session, carsNode, playerCar, currentLap);
            }

            // Qualifying-only detectors.
            if (session.isQualifying()) {
                detectQualifyingSectorDelta(session, playerCar, currentLap);
                detectQualifyingLapComplete(session, carsNode, playerCar, currentLap);
            }

            // Try to deliver a message
            EngineerMessage message = session.queue.pollForDelivery(
                    lapDistance, session.trackId, currentLap, speedKmh, safeZoneService);
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

        // Stash the next recommended pit on every session. The throttled T-5/T-1/box
        // messages are emitted from detectPitWindowMessages during state updates.
        for (SessionEngineerState session : sessions.values()) {
            int currentLap = session.lastPlayerLap;
            for (RaceSnapshot.PitStrategy.PitStop stop : stops) {
                if (stop.onLap() > currentLap) {
                    session.recommendedPitLap = stop.onLap();
                    session.recommendedPitCompound = stop.newCompound();
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
                    Priority.IMMEDIATE,
                    "You have an unserved " + type + " penalty. Box this lap.",
                    System.currentTimeMillis(), currentLap, 2));
        }
        session.previousUnservedDT = unservedDT;
        session.previousUnservedSG = unservedSG;
        session.previousUnservedPenalties = totalUnserved;

        if (warnings > session.previousWarnings) {
            session.queue.enqueue(new EngineerMessage(
                    Priority.IMMEDIATE,
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
                            Priority.HIGH,
                            behindName + " closing from behind. " + String.format("%.1f", gapSeconds) + " seconds back. Defend your position.",
                            System.currentTimeMillis(), currentLap, 1));
                    session.lastReactiveAwarenessLap = currentLap;
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
                            Priority.HIGH,
                            "You have DRS. Attack.",
                            System.currentTimeMillis(), currentLap, 1));
                    session.lastReactiveAwarenessLap = currentLap;
                }
                session.previousGapAheadSeconds = gapSeconds;
            }
        } else {
            session.previousGapAheadSeconds = -1f;
        }
    }

    private void detectLapCountdown(SessionEngineerState session, int currentLap, int totalLaps) {
        if (totalLaps <= 0) return;
        int lapsRemaining = totalLaps - currentLap + 1;

        if (currentLap > session.lastPlayerLap) {
            if (lapsRemaining == 10) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.IMMEDIATE,
                        "10 laps remaining. Keep it clean, manage your tyres.",
                        System.currentTimeMillis(), currentLap, 1));
            } else if (lapsRemaining == 5) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.IMMEDIATE,
                        "5 laps to go. Bring it home.",
                        System.currentTimeMillis(), currentLap, 1));
            } else if (lapsRemaining == 1) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.IMMEDIATE,
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
            for (int i = 0; i < session.lastWearAlertPct.length; i++) session.lastWearAlertPct[i] = 0;
            String newCompound = playerCar.has("tyre") ? playerCar.get("tyre").asText() : "new";
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "Copy, new " + newCompound + " tyres on. Take it easy for the out lap.",
                    System.currentTimeMillis(), currentLap, 1));
        }
        session.previousTyreAge = tyreAge;
    }

    // -- qualifying detectors --------------------------------------------------

    private void detectQualifyingSectorDelta(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        int sector = playerCar.has("sector") ? playerCar.get("sector").asInt() : 0;
        if (sector == session.previousPlayerSector) return;

        // Sector rolled forward; identify which one was just finished. Sector 3 finish
        // shows up as 2→0 together with a lap change — lap-complete handler covers that.
        int completedSector = -1;
        if (sector == 1 && session.previousPlayerSector == 0) completedSector = 0;
        else if (sector == 2 && session.previousPlayerSector == 1) completedSector = 1;
        session.previousPlayerSector = sector;
        if (completedSector < 0) return;

        JsonNode sectorMs = playerCar.get("lastSectorMs");
        if (sectorMs == null || !sectorMs.isArray() || sectorMs.size() <= completedSector) return;
        long timeMs = sectorMs.get(completedSector).asLong();
        if (timeMs <= 0) return;

        long best = session.bestSectorMs[completedSector];
        if (best <= 0 || timeMs < best) {
            session.bestSectorMs[completedSector] = timeMs;
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "Purple sector " + (completedSector + 1) + ". " + formatLapTime(timeMs) + ".",
                    System.currentTimeMillis(), currentLap, 2));
        } else {
            long delta = timeMs - best;
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "Sector " + (completedSector + 1) + " down "
                            + String.format("%.3f", delta / 1000f) + "s.",
                    System.currentTimeMillis(), currentLap, 2));
        }
    }

    private void detectQualifyingLapComplete(SessionEngineerState session, JsonNode carsNode,
                                              JsonNode playerCar, int currentLap) {
        if (currentLap <= session.lastPlayerLap) return;

        long playerLapMs = playerCar.has("lastLapTimeMs") ? playerCar.get("lastLapTimeMs").asLong() : 0;
        if (playerLapMs <= 0) {
            session.lastPlayerLap = currentLap;
            return;
        }

        long bestLapMs = Long.MAX_VALUE;
        for (JsonNode car : carsNode) {
            long ms = car.has("lastLapTimeMs") ? car.get("lastLapTimeMs").asLong() : 0;
            if (ms > 0 && ms < bestLapMs) bestLapMs = ms;
        }

        int pos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
        String text;
        if (bestLapMs == Long.MAX_VALUE || playerLapMs <= bestLapMs) {
            text = "Provisional pole. " + formatLapTime(playerLapMs) + ".";
        } else {
            long delta = playerLapMs - bestLapMs;
            text = "P" + pos + ", " + String.format("%.3f", delta / 1000f) + "s off pole. "
                    + formatLapTime(playerLapMs) + ".";
        }
        session.queue.enqueue(new EngineerMessage(
                Priority.NORMAL, text,
                System.currentTimeMillis(), currentLap, 2));
        session.lastPlayerLap = currentLap;
    }

    private static String formatLapTime(long ms) {
        long minutes = ms / 60000;
        long seconds = (ms % 60000) / 1000;
        long millis = ms % 1000;
        if (minutes > 0) return String.format("%d:%02d.%03d", minutes, seconds, millis);
        return String.format("%d.%03d", seconds, millis);
    }

    private static final String[] CORNER_NAMES = {"Rear-left", "Rear-right", "Front-left", "Front-right"};

    private void detectPerCornerTyreWear(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        JsonNode wearNode = playerCar.get("tyreWear");
        if (wearNode == null || !wearNode.isArray() || wearNode.size() < 4) return;

        for (int i = 0; i < 4; i++) {
            int wear = (int) wearNode.get(i).asDouble();
            int lastAlert = session.lastWearAlertPct[i];

            if (wear >= 50 && lastAlert < 50) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.HIGH,
                        CORNER_NAMES[i] + " is finished, manage it.",
                        System.currentTimeMillis(), currentLap, 2));
                session.lastWearAlertPct[i] = 50;
            } else if (wear >= 25 && lastAlert < 25) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.NORMAL,
                        CORNER_NAMES[i] + " starting to degrade.",
                        System.currentTimeMillis(), currentLap, 3));
                session.lastWearAlertPct[i] = 25;
            }
        }
    }

    private void detectPositionChange(SessionEngineerState session, JsonNode carsNode,
                                       JsonNode playerCar, int currentLap, int trackLength) {
        int pitStatus = playerCar.has("pitStatus") ? playerCar.get("pitStatus").asInt() : 0;
        // Suppress gain/loss messages while in the pit cycle. We also skip the exit
        // frame (pitStatus==0 but previousPitStatus>0) so the reshuffled grid doesn't
        // trigger a spurious message; the pit-exit recap in detectPitStopCompleted
        // covers that transition and resets previousPlayerPosition.
        if (pitStatus > 0 || session.previousPitStatus > 0) {
            return;
        }

        int currentPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;

        if (session.previousPlayerPosition > 0 && currentPos < session.previousPlayerPosition) {
            String text;
            if (currentPos <= 1) {
                text = "P1. Leading now.";
            } else {
                JsonNode carAhead = findCarAtPosition(carsNode, currentPos - 1);
                String name = carAhead != null && carAhead.has("name")
                        ? carAhead.get("name").asText() : "Car ahead";
                float gapSeconds = gapToCarSeconds(playerCar, carAhead, trackLength);
                text = gapSeconds >= 0
                        ? "P" + currentPos + ". " + name + " is next, "
                                + String.format("%.1f", gapSeconds) + "s up the road."
                        : "P" + currentPos + ". " + name + " is next.";
            }
            session.queue.enqueue(new EngineerMessage(
                    Priority.IMMEDIATE, text,
                    System.currentTimeMillis(), currentLap, 1));
        } else if (session.previousPlayerPosition > 0 && currentPos > session.previousPlayerPosition) {
            JsonNode carAhead = findCarAtPosition(carsNode, currentPos - 1);
            String name = carAhead != null && carAhead.has("name")
                    ? carAhead.get("name").asText() : "Car ahead";
            session.queue.enqueue(new EngineerMessage(
                    Priority.HIGH,
                    "Lost a place. P" + currentPos + ". " + name + " is now ahead.",
                    System.currentTimeMillis(), currentLap, 1));
        }
        session.previousPlayerPosition = currentPos;
    }

    private static JsonNode findCarAtPosition(JsonNode carsNode, int position) {
        for (JsonNode car : carsNode) {
            int pos = car.has("pos") ? car.get("pos").asInt() : 0;
            if (pos == position) return car;
        }
        return null;
    }

    /** Returns gap in seconds between player and the given car (car ahead on track), or -1 if not computable. */
    private static float gapToCarSeconds(JsonNode playerCar, JsonNode otherCar, int trackLength) {
        if (otherCar == null) return -1f;
        int playerLap = playerCar.has("lap") ? playerCar.get("lap").asInt() : 0;
        int otherLap = otherCar.has("lap") ? otherCar.get("lap").asInt() : 0;
        if (playerLap != otherLap) return -1f;
        float playerDist = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;
        float otherDist = otherCar.has("lapDist") ? (float) otherCar.get("lapDist").asDouble() : 0f;
        float gap = otherDist - playerDist;
        if (gap < 0) gap += trackLength;
        return gap / 55f;
    }

    private void detectPitStopCompleted(SessionEngineerState session, JsonNode carsNode,
                                         JsonNode playerCar, int currentLap, int trackLength) {
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
                        Priority.HIGH,
                        "Good stop. " + String.format("%.1f", durationSeconds) + " seconds. Push now.",
                        System.currentTimeMillis(), currentLap, 1));
                session.pitEnteredAt = 0;
            }

            // Pit-exit recap: announce re-entry position with ahead/behind gaps.
            int exitPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
            session.queue.enqueue(new EngineerMessage(
                    Priority.HIGH,
                    buildPitExitRecap(carsNode, playerCar, exitPos, trackLength),
                    System.currentTimeMillis(), currentLap, 2));

            // Reset position baseline so the next on-track change fires from the exit position.
            session.previousPlayerPosition = exitPos;
        }
        session.previousPitStatus = pitStatus;
        session.previousPitCount = pitCount;
    }

    private static String buildPitExitRecap(JsonNode carsNode, JsonNode playerCar,
                                             int exitPos, int trackLength) {
        StringBuilder sb = new StringBuilder("Out of the pits in P").append(exitPos).append(".");
        JsonNode carAhead = exitPos > 1 ? findCarAtPosition(carsNode, exitPos - 1) : null;
        JsonNode carBehind = findCarAtPosition(carsNode, exitPos + 1);

        if (carAhead != null) {
            float gap = gapToCarSeconds(playerCar, carAhead, trackLength);
            String name = carAhead.has("name") ? carAhead.get("name").asText() : "car ahead";
            if (gap >= 0) {
                sb.append(" ").append(String.format("%.1f", gap)).append("s to ").append(name).append(".");
            }
        }
        if (carBehind != null) {
            float gap = gapToCarSeconds(carBehind, playerCar, trackLength);
            String name = carBehind.has("name") ? carBehind.get("name").asText() : "car behind";
            if (gap >= 0) {
                sb.append(" ").append(String.format("%.1f", gap)).append("s to ").append(name).append(".");
            }
        }
        return sb.toString();
    }

    private static final float BOX_BOX_LAP_FRACTION = 0.8f;

    private void detectPitWindowMessages(SessionEngineerState session, JsonNode playerCar,
                                          int currentLap, int trackLength) {
        int target = session.recommendedPitLap;
        if (target <= 0 || target < currentLap) return;

        // Strategy picked a new target lap — reset the emission state for the new target.
        if (target != session.lastAnnouncedPitTargetLap) {
            session.lastAnnouncedPitTargetLap = target;
            session.lastPitMessageKind = PitMsgKind.NONE;
        }

        int delta = target - currentLap;
        String compound = mapCompoundName(session.recommendedPitCompound);

        if (delta == 5 && session.lastPitMessageKind == PitMsgKind.NONE) {
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "Box window opens in 5 laps. " + compound + " ready.",
                    System.currentTimeMillis(), currentLap, 2));
            session.lastPitMessageKind = PitMsgKind.T_MINUS_5;
        } else if (delta == 1 && session.lastPitMessageKind.ordinal() < PitMsgKind.T_MINUS_1.ordinal()) {
            session.queue.enqueue(new EngineerMessage(
                    Priority.HIGH,
                    "Box next lap. " + compound + " ready.",
                    System.currentTimeMillis(), currentLap, 1));
            session.lastPitMessageKind = PitMsgKind.T_MINUS_1;
        } else if (delta == 0 && session.lastPitMessageKind.ordinal() < PitMsgKind.BOX.ordinal()) {
            float lapDistance = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;
            if (trackLength > 0 && lapDistance >= trackLength * BOX_BOX_LAP_FRACTION) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.IMMEDIATE,
                        "Box, box, box.",
                        System.currentTimeMillis(), currentLap, 1));
                session.lastPitMessageKind = PitMsgKind.BOX;
            }
        }
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

        int lapsRemaining = totalLaps - currentLap + 1;
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
                            Priority.HIGH,
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

    private static final int PERIODIC_AWARENESS_EVERY_N_LAPS = 3;

    private void detectPeriodicSituationalAwareness(SessionEngineerState session, JsonNode carsNode,
                                                     JsonNode playerCar, int currentLap, int totalLaps,
                                                     int trackLength) {
        if (currentLap <= 1) return;
        if (totalLaps > 0 && currentLap >= totalLaps) return;
        if (currentLap % PERIODIC_AWARENESS_EVERY_N_LAPS != 0) return;
        if (session.lastPeriodicAwarenessLap == currentLap) return;
        if (session.lastReactiveAwarenessLap == currentLap) return;

        int playerPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
        JsonNode carAhead = playerPos > 1 ? findCarAtPosition(carsNode, playerPos - 1) : null;
        JsonNode carBehind = findCarAtPosition(carsNode, playerPos + 1);
        if (carAhead == null && carBehind == null) return;

        StringBuilder sb = new StringBuilder("P").append(playerPos).append(".");
        if (carAhead != null) {
            float gapAhead = gapToCarSeconds(playerCar, carAhead, trackLength);
            String nameAhead = carAhead.has("name") ? carAhead.get("name").asText() : "car ahead";
            if (gapAhead >= 0) {
                sb.append(" ").append(String.format("%.1f", gapAhead)).append("s to ").append(nameAhead).append(".");
            }
        }
        if (carBehind != null) {
            // Swap args so gap is carBehind-to-player (positive).
            float gapBehind = gapToCarSeconds(carBehind, playerCar, trackLength);
            String nameBehind = carBehind.has("name") ? carBehind.get("name").asText() : "car behind";
            if (gapBehind >= 0) {
                sb.append(" ").append(String.format("%.1f", gapBehind)).append("s to ").append(nameBehind).append(".");
            }
        }

        session.queue.enqueue(new EngineerMessage(
                Priority.NORMAL, sb.toString(),
                System.currentTimeMillis(), currentLap, 2));
        session.lastPeriodicAwarenessLap = currentLap;
    }

    private void detectRaceFinish(SessionEngineerState session, JsonNode carsNode, JsonNode playerCar, int currentLap) {
        if (session.raceFinished) return;

        int resultStatus = playerCar.has("resultStatus") ? playerCar.get("resultStatus").asInt() : 2;

        // resultStatus: 2=active, 3=finished, 4=DNF, 5=DSQ, 6=not classified, 7=retired
        String dnfMessage = switch (resultStatus) {
            case 4, 7 -> "That's our day done. Engine off, come back to the pits.";
            case 5 -> "Disqualified. We'll review and figure out what happened.";
            case 6 -> "Didn't make the classified distance. Tough one.";
            default -> null;
        };
        if (dnfMessage != null) {
            session.raceFinished = true;
            session.queue.enqueue(new EngineerMessage(
                    Priority.IMMEDIATE, dnfMessage,
                    System.currentTimeMillis(), currentLap, 5));
            return;
        }

        if (resultStatus == 3 || session.chequeredFlag) {
            session.raceFinished = true;
            int pos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
            int gridSize = carsNode.size();
            session.queue.enqueue(new EngineerMessage(
                    Priority.IMMEDIATE,
                    buildFinishMessage(pos, gridSize),
                    System.currentTimeMillis(), currentLap, 5));
        }
    }

    static String buildFinishMessage(int pos, int gridSize) {
        if (pos <= 0) {
            return "Chequered flag. Box this lap.";
        }
        if (pos <= 3) {
            return "P" + pos + "! Brilliant drive. Cooldown lap, bring it home.";
        }
        if (pos <= 10) {
            return "P" + pos + ". Solid points today. Good job.";
        }
        int midfieldEnd = Math.max(10, gridSize * 3 / 4);
        if (pos <= midfieldEnd) {
            return "P" + pos + ". We'll take it. Plenty to review.";
        }
        if (pos < gridSize) {
            return "P" + pos + ". Tough one. We'll debrief and come back stronger.";
        }
        return "P" + pos + ". Rough day at the office. Box this lap and we regroup.";
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

    enum PitMsgKind { NONE, T_MINUS_5, T_MINUS_1, BOX }

    // -- per-session mutable state ---------------------------------------------

    static class SessionEngineerState {
        final String sessionUid;
        final int trackId;
        final int sessionType; // 1-4 practice, 5-9 qualifying, 10-12 race, 13 time trial
        int ersAssist;
        int drsAssist;

        boolean isRace() { return sessionType >= 10 && sessionType <= 12; }
        boolean isQualifying() { return sessionType >= 5 && sessionType <= 9; }
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
        // Per-corner wear: highest threshold already announced per corner (0/25/50).
        // Index order matches telemetry wheel layout: 0=RL, 1=RR, 2=FL, 3=FR.
        final int[] lastWearAlertPct = new int[4];

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

        // Periodic situational awareness dedup
        int lastReactiveAwarenessLap = 0;
        int lastPeriodicAwarenessLap = 0;

        // Pit window messaging (throttled T-5 / T-1 / box-box-box)
        int recommendedPitLap = -1;
        int recommendedPitCompound = 0;
        int lastAnnouncedPitTargetLap = -1;
        PitMsgKind lastPitMessageKind = PitMsgKind.NONE;

        // Qualifying: player session-best sector times (ms) for sectors 1 and 2.
        // Sector 3 not tracked separately — reported as part of lap-complete.
        final long[] bestSectorMs = new long[3];
        int previousPlayerSector = -1;

        SessionEngineerState(String sessionUid, int trackId, int sessionType, int ersAssist, int drsAssist) {
            this.sessionUid = sessionUid;
            this.trackId = trackId;
            this.sessionType = sessionType;
            this.ersAssist = ersAssist;
            this.drsAssist = drsAssist;
        }
    }
}
