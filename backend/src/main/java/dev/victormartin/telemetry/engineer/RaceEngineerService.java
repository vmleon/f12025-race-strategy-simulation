package dev.victormartin.telemetry.engineer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.simulation.RaceSnapshot;
import dev.victormartin.telemetry.simulation.StrategyEvaluation;

/**
 * v1 race engineer orchestrator. Cut-over to {@code engineer.v2.RaceEngineerServiceV2}
 * happened on 2026-04-18. This class is no longer a Spring component and is
 * not on the production dispatch path; tests in this package still construct
 * it directly. Kept as a reference until v2 is confirmed in a live playtest,
 * then will be deleted.
 */
public class RaceEngineerService {

    private static final Logger TRACE = LoggerFactory.getLogger("engineer.trace");

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
        // Remove any stale session for the same track (handles missed sessionEnded events)
        sessions.values().removeIf(s -> s.trackId == trackId);
        SessionEngineerState session = new SessionEngineerState(sessionUid, trackId, sessionType, ersAssist, drsAssist);
        sessions.put(sessionUid, session);
        session.queue.enqueue(new EngineerMessage(
                Priority.NORMAL,
                "Radio check. All systems nominal.",
                System.currentTimeMillis(), 0, 5));
        webSocketHandler.broadcast("{\"type\":\"sessionStarted\",\"sessionUid\":\"" + sessionUid + "\"}");
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

            updateThrottleBuffer(session, playerCar);

            int currentLap = playerCar.has("lap") ? playerCar.get("lap").asInt() : 1;
            float lapDistance = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;
            int totalLaps = state.has("totalLaps") ? state.get("totalLaps").asInt() : 0;
            int trackLength = state.has("trackLength") ? state.get("trackLength").asInt() : 5000;
            int speedKmh = playerCar.has("speed") ? playerCar.get("speed").asInt() : 0;

            // Trace pit-status transition independently of detector-mutated previousPitStatus.
            int pitStatusNow = playerCar.has("pitStatus") ? playerCar.get("pitStatus").asInt() : 0;
            int pitLaneActive = playerCar.has("pitLaneTimerActive") ? playerCar.get("pitLaneTimerActive").asInt() : 0;
            int pitLaneMs = playerCar.has("pitLaneTimeMs") ? playerCar.get("pitLaneTimeMs").asInt() : 0;
            float throttle = playerCar.has("throttle") ? (float) playerCar.get("throttle").asDouble() : -1f;
            int playerPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
            String sessionKind = session.isRace() ? "race" : session.isQualifying() ? "quali" : session.isPractice() ? "practice" : "other";
            TRACE.debug("TICK session={} sessionType={} lap={} lapDist={} pos={} pitStatus={} pitLaneActive={} pitLaneMs={} speedKmh={} throttle={}",
                    sessionKind, session.sessionType, currentLap, lapDistance, playerPos,
                    pitStatusNow, pitLaneActive, pitLaneMs, speedKmh, throttle);
            if (pitStatusNow != session.traceLastPitStatus) {
                TRACE.debug("PIT_TRANSITION from={} to={} lap={} lapDist={} pitLaneActive={} pitLaneMs={}",
                        session.traceLastPitStatus, pitStatusNow, currentLap, lapDistance, pitLaneActive, pitLaneMs);
                session.traceLastPitStatus = pitStatusNow;
            }

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
                detectSessionTrackTraffic(session, carsNode, playerCar, currentLap, trackLength);
                detectSlowLapTrafficWarning(session, carsNode, playerCar, currentLap, trackLength);
            }

            // Practice-only detectors.
            if (session.isPractice()) {
                detectPracticeGripMessages(session, currentLap);
                detectPracticeTyreFuelSummary(session, playerCar, currentLap);
                detectPracticeSectorComparison(session, carsNode, playerCar, currentLap);
                detectSessionTrackTraffic(session, carsNode, playerCar, currentLap, trackLength);
                detectSlowLapTrafficWarning(session, carsNode, playerCar, currentLap, trackLength);
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

        // Stash the next recommended pit on every session. The throttled T-5/T-1/box
        // messages are emitted from detectPitWindowMessages during state updates.
        // If the best strategy has no future pit (e.g. "No stop"), clear any prior
        // recommendation so stale T-1/box messages don't fire from an obsolete plan.
        for (SessionEngineerState session : sessions.values()) {
            int currentLap = session.lastPlayerLap;
            RaceSnapshot.PitStrategy.PitStop nextStop = null;
            if (stops != null) {
                for (RaceSnapshot.PitStrategy.PitStop stop : stops) {
                    if (stop.onLap() > currentLap) {
                        nextStop = stop;
                        break;
                    }
                }
            }
            if (nextStop != null) {
                session.recommendedPitLap = nextStop.onLap();
                session.recommendedPitCompound = nextStop.newCompound();
            } else {
                session.recommendedPitLap = -1;
                session.recommendedPitCompound = 0;
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
                            behindName + " closing from behind. " + formatTenths(gapSeconds) + " seconds back. Defend your position.",
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
            String compound = playerCar.has("tyre") ? playerCar.get("tyre").asText() : "";
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    capitalize(abbreviationToSpokenName(compound)) + " tyres are " + tyreAge + " laps old. Consider a pit stop.",
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
            String newCompound = playerCar.has("tyre") ? playerCar.get("tyre").asText() : "";
            session.queue.enqueue(new EngineerMessage(
                    Priority.NORMAL,
                    "Copy, new " + abbreviationToSpokenName(newCompound) + " tyres on. Take it easy for the out lap.",
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
                            + formatTenths(delta / 1000.0) + " seconds.",
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
            text = "P" + pos + ", " + formatTenths(delta / 1000.0) + " seconds off pole. "
                    + formatLapTime(playerLapMs) + ".";
        }
        session.queue.enqueue(new EngineerMessage(
                Priority.NORMAL, text,
                System.currentTimeMillis(), currentLap, 2));
        session.lastPlayerLap = currentLap;
    }

    private static final String[] GRIP_MESSAGES = {
        "Grip coming in, keep pushing.",
        "Still warming up, take it easy.",
        "Rear feels settled, good balance.",
        "Fronts biting nicely.",
        "Watch the understeer through the high-speed stuff."
    };

    private void detectPracticeGripMessages(SessionEngineerState session, int currentLap) {
        if (currentLap < 2) return;
        if (session.nextGripMessageLap == 0) {
            session.nextGripMessageLap = currentLap + 5 + (int) (Math.random() * 3);
            return;
        }
        if (currentLap < session.nextGripMessageLap) return;
        if (currentLap == session.lastGripMessageLap) return;

        int idx = (int) (Math.random() * GRIP_MESSAGES.length);
        session.queue.enqueue(new EngineerMessage(
                Priority.NORMAL,
                GRIP_MESSAGES[idx],
                System.currentTimeMillis(), currentLap, 3));
        session.lastGripMessageLap = currentLap;
        session.nextGripMessageLap = currentLap + 5 + (int) (Math.random() * 3);
    }

    private void detectPracticeTyreFuelSummary(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        if (currentLap < 2) return;
        if (currentLap - session.lastTyreFuelSummaryLap < 4) return;

        JsonNode wearNode = playerCar.get("tyreWear");
        if (wearNode == null || !wearNode.isArray() || wearNode.size() < 4) return;

        // wear order is [RL, RR, FL, FR]
        int rearAvg = (int) Math.round((wearNode.get(0).asDouble() + wearNode.get(1).asDouble()) / 2.0);
        int frontAvg = (int) Math.round((wearNode.get(2).asDouble() + wearNode.get(3).asDouble()) / 2.0);

        int fuel = playerCar.has("fuel")
                ? (int) Math.round(playerCar.get("fuel").asDouble())
                : -1;
        if (fuel < 0) return;

        String text = "Fronts at " + frontAvg + "% wear, rears at " + rearAvg + "%, fuel "
                + fuel + " kilograms.";
        session.queue.enqueue(new EngineerMessage(
                Priority.NORMAL,
                text,
                System.currentTimeMillis(), currentLap, 3));
        session.lastTyreFuelSummaryLap = currentLap;
    }

    private void detectPracticeSectorComparison(SessionEngineerState session, JsonNode carsNode,
                                                  JsonNode playerCar, int currentLap) {
        // Update best sector times for every car on sector transitions.
        for (JsonNode car : carsNode) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx < 0 || idx >= session.previousCarSectors.length) continue;
            int sector = car.has("sector") ? car.get("sector").asInt() : 0;
            int prevSector = session.previousCarSectors[idx];
            session.previousCarSectors[idx] = sector;

            int completedSector = -1;
            long timeMs = 0;
            JsonNode sectorMs = car.get("lastSectorMs");
            if (sectorMs == null || !sectorMs.isArray() || sectorMs.size() < 2) continue;

            if (prevSector == 0 && sector == 1) {
                completedSector = 0;
                timeMs = sectorMs.get(0).asLong();
            } else if (prevSector == 1 && sector == 2) {
                completedSector = 1;
                timeMs = sectorMs.get(1).asLong();
            }
            if (completedSector < 0 || timeMs <= 0) continue;

            boolean isPlayer = car.has("ai") && !car.get("ai").asBoolean();
            if (isPlayer) {
                long playerBest = session.playerBestSectors[completedSector];
                boolean newPlayerBest = playerBest == 0 || timeMs < playerBest;
                if (newPlayerBest) {
                    session.playerBestSectors[completedSector] = timeMs;
                    fireSectorComparisonIfFaster(session, carsNode, completedSector, timeMs, currentLap);
                }
            } else {
                long[] bests = session.bestSectorTimesByCar.computeIfAbsent(idx, k -> new long[3]);
                if (bests[completedSector] == 0 || timeMs < bests[completedSector]) {
                    bests[completedSector] = timeMs;
                }
            }
        }
    }

    private void fireSectorComparisonIfFaster(SessionEngineerState session, JsonNode carsNode,
                                                int sector, long playerBestMs, int currentLap) {
        if (currentLap - session.lastSectorComparisonLap[sector] < 3) return;

        long fastestOtherMs = 0;
        int fastestCarIdx = -1;
        for (Map.Entry<Integer, long[]> e : session.bestSectorTimesByCar.entrySet()) {
            long ms = e.getValue()[sector];
            if (ms <= 0) continue;
            if (fastestOtherMs == 0 || ms < fastestOtherMs) {
                fastestOtherMs = ms;
                fastestCarIdx = e.getKey();
            }
        }
        if (fastestCarIdx < 0 || fastestOtherMs >= playerBestMs) return;

        long deltaMs = playerBestMs - fastestOtherMs;
        if (deltaMs < 150) return;

        String name = "Rival";
        for (JsonNode car : carsNode) {
            int idx = car.has("idx") ? car.get("idx").asInt() : -1;
            if (idx == fastestCarIdx) {
                if (car.has("name")) name = car.get("name").asText();
                break;
            }
        }

        String text = name + " is " + formatTenths(deltaMs / 1000.0) + " seconds faster in Sector "
                + (sector + 1) + ".";
        session.queue.enqueue(new EngineerMessage(
                Priority.NORMAL,
                text,
                System.currentTimeMillis(), currentLap, 3));
        session.lastSectorComparisonLap[sector] = currentLap;
    }

    private void detectSessionTrackTraffic(SessionEngineerState session, JsonNode carsNode,
                                              JsonNode playerCar, int currentLap, int trackLength) {
        int pitStatus = playerCar.has("pitStatus") ? playerCar.get("pitStatus").asInt() : 0;

        try {
            // Reset stint flag when player leaves the pit lane
            if (pitStatus == 0 && session.previousPitStatus > 0) {
                session.trackTrafficMessageSentThisStint = false;
                TRACE.debug("DETECTOR detectSessionTrackTraffic decision=reset_stint_flag previousPit={} pitStatus={}",
                        session.previousPitStatus, pitStatus);
            }
            if (pitStatus == 0) {
                TRACE.debug("DETECTOR detectSessionTrackTraffic decision=skip reason=on_track pitStatus={}", pitStatus);
                return;
            }
            if (session.trackTrafficMessageSentThisStint) {
                TRACE.debug("DETECTOR detectSessionTrackTraffic decision=skip reason=already_sent_this_stint pitStatus={}", pitStatus);
                return;
            }

            float playerDist = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;

            float nearestApproachingSeconds = Float.MAX_VALUE;
            String nearestName = null;
            for (JsonNode car : carsNode) {
                boolean isAi = car.has("ai") && car.get("ai").asBoolean();
                if (!isAi) continue;
                int otherPit = car.has("pitStatus") ? car.get("pitStatus").asInt() : 0;
                if (otherPit > 0) continue;

                float otherDist = car.has("lapDist") ? (float) car.get("lapDist").asDouble() : 0f;
                float gap = playerDist - otherDist;
                if (gap < 0) gap += trackLength;
                float seconds = gap / 55f;
                if (seconds < nearestApproachingSeconds) {
                    nearestApproachingSeconds = seconds;
                    nearestName = car.has("name") ? car.get("name").asText() : "a car";
                }
            }

            if (nearestName == null) {
                TRACE.debug("DETECTOR detectSessionTrackTraffic decision=skip reason=no_ai_on_track pitStatus={}", pitStatus);
                return;
            }

            String text;
            String fireReason;
            if (nearestApproachingSeconds > 15f) {
                text = "Track is clear, go now.";
                fireReason = "clear_window";
            } else if (nearestApproachingSeconds < 8f) {
                text = "Hold position, " + nearestName + " about to pass.";
                fireReason = "hold_position";
            } else {
                TRACE.debug("DETECTOR detectSessionTrackTraffic decision=skip reason=in_dead_zone pitStatus={} nearestSec={} nearestName={}",
                        pitStatus, nearestApproachingSeconds, nearestName);
                return;
            }

            TRACE.debug("DETECTOR detectSessionTrackTraffic decision=fire reason={} pitStatus={} nearestSec={} nearestName={}",
                    fireReason, pitStatus, nearestApproachingSeconds, nearestName);
            session.queue.enqueue(new EngineerMessage(
                    Priority.HIGH,
                    text,
                    System.currentTimeMillis(), currentLap, 2));
            session.trackTrafficMessageSentThisStint = true;
        } finally {
            session.previousPitStatus = pitStatus;
        }
    }

    private void detectSlowLapTrafficWarning(SessionEngineerState session, JsonNode carsNode,
                                               JsonNode playerCar, int currentLap, int trackLength) {
        int pitStatus = playerCar.has("pitStatus") ? playerCar.get("pitStatus").asInt() : 0;
        if (!isPlayerOnSlowLap(session)) {
            session.previousSlowLapGapBehind = -1f;
            TRACE.debug("DETECTOR detectSlowLapTrafficWarning decision=skip reason=not_slow_lap pitStatus={} throttleBufSize={}",
                    pitStatus, session.playerThrottleBuffer.size());
            return;
        }

        int playerPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
        JsonNode carBehind = null;
        for (JsonNode car : carsNode) {
            int pos = car.has("pos") ? car.get("pos").asInt() : 0;
            if (pos == playerPos + 1) {
                carBehind = car;
                break;
            }
        }
        if (carBehind == null) {
            session.previousSlowLapGapBehind = -1f;
            TRACE.debug("DETECTOR detectSlowLapTrafficWarning decision=skip reason=no_car_behind pitStatus={} playerPos={}", pitStatus, playerPos);
            return;
        }

        int behindIdx = carBehind.has("idx") ? carBehind.get("idx").asInt() : -1;
        String behindName = carBehind.has("name") ? carBehind.get("name").asText() : "Car behind";

        float playerLapDist = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;
        float behindLapDist = carBehind.has("lapDist") ? (float) carBehind.get("lapDist").asDouble() : 0f;
        int playerLap = playerCar.has("lap") ? playerCar.get("lap").asInt() : 0;
        int behindLap = carBehind.has("lap") ? carBehind.get("lap").asInt() : 0;
        if (playerLap != behindLap) {
            session.previousSlowLapGapBehind = -1f;
            TRACE.debug("DETECTOR detectSlowLapTrafficWarning decision=skip reason=lap_mismatch pitStatus={} playerLap={} behindLap={} behindName={}",
                    pitStatus, playerLap, behindLap, behindName);
            return;
        }

        float gap = playerLapDist - behindLapDist;
        if (gap < 0) gap += trackLength;
        float gapSeconds = gap / 55f;

        boolean closing = session.previousSlowLapGapBehind > 0 && gapSeconds < session.previousSlowLapGapBehind;
        float prevGap = session.previousSlowLapGapBehind;
        session.previousSlowLapGapBehind = gapSeconds;

        if (gapSeconds >= SLOW_LAP_GAP_THRESHOLD_SEC) {
            TRACE.debug("DETECTOR detectSlowLapTrafficWarning decision=skip reason=gap_too_large pitStatus={} gapSec={} thresholdSec={} behindName={}",
                    pitStatus, gapSeconds, SLOW_LAP_GAP_THRESHOLD_SEC, behindName);
            return;
        }
        if (!closing) {
            TRACE.debug("DETECTOR detectSlowLapTrafficWarning decision=skip reason=not_closing pitStatus={} gapSec={} prevGap={} behindName={}",
                    pitStatus, gapSeconds, prevGap, behindName);
            return;
        }

        long now = System.currentTimeMillis();
        Long lastFired = session.slowLapCooldownByCar.get(behindIdx);
        if (lastFired != null && (now - lastFired) < SLOW_LAP_COOLDOWN_MS) {
            TRACE.debug("DETECTOR detectSlowLapTrafficWarning decision=skip reason=cooldown pitStatus={} gapSec={} ageMs={} behindName={}",
                    pitStatus, gapSeconds, (now - lastFired), behindName);
            return;
        }

        TRACE.debug("DETECTOR detectSlowLapTrafficWarning decision=fire pitStatus={} gapSec={} prevGap={} behindName={} behindIdx={}",
                pitStatus, gapSeconds, prevGap, behindName, behindIdx);
        session.queue.enqueue(new EngineerMessage(
                Priority.HIGH,
                behindName + " closing fast behind, let them through.",
                now, currentLap, 2));
        session.slowLapCooldownByCar.put(behindIdx, now);
    }

    private static String formatTenths(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.floor(rounded)) return String.format("%.0f", rounded);
        return String.format("%.1f", rounded);
    }

    private static String formatLapTime(long ms) {
        long minutes = ms / 60000;
        double seconds = (ms % 60000) / 1000.0;
        String secStr = formatTenths(seconds) + " seconds";
        if (minutes > 0) {
            return minutes + (minutes == 1 ? " minute " : " minutes ") + secStr;
        }
        return secStr;
    }

    private static final String[] CORNER_NAMES = {"Rear-left", "Rear-right", "Front-left", "Front-right"};

    private void detectPerCornerTyreWear(SessionEngineerState session, JsonNode playerCar, int currentLap) {
        JsonNode wearNode = playerCar.get("tyreWear");
        if (wearNode == null || !wearNode.isArray() || wearNode.size() < 4) return;

        for (int i = 0; i < 4; i++) {
            int wear = (int) wearNode.get(i).asDouble();
            int lastAlert = session.lastWearAlertPct[i];

            if (wear >= 37 && lastAlert < 37) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.HIGH,
                        CORNER_NAMES[i] + " is finished, manage it.",
                        System.currentTimeMillis(), currentLap, 2));
                session.lastWearAlertPct[i] = 37;
            } else if (wear >= 24 && lastAlert < 24) {
                session.queue.enqueue(new EngineerMessage(
                        Priority.NORMAL,
                        CORNER_NAMES[i] + " starting to degrade.",
                        System.currentTimeMillis(), currentLap, 3));
                session.lastWearAlertPct[i] = 24;
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
            TRACE.debug("DETECTOR detectPositionChange decision=skip reason=in_pit_cycle pitStatus={} previousPit={}",
                    pitStatus, session.previousPitStatus);
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
                                + formatTenths(gapSeconds) + " seconds up the road."
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
            TRACE.debug("DETECTOR detectPitStopCompleted decision=pit_entry_detected pitStatus={} pitCount={}",
                    pitStatus, pitCount);
        }

        // Pit exit detected: pitStatus back to 0, pit count increased
        if (pitStatus == 0 && session.previousPitStatus > 0 && pitCount > session.previousPitCount) {
            TRACE.debug("DETECTOR detectPitStopCompleted decision=pit_exit_detected previousPit={} pitCount={} previousPitCount={}",
                    session.previousPitStatus, pitCount, session.previousPitCount);
            if (session.pitEnteredAt > 0) {
                float durationSeconds = (System.currentTimeMillis() - session.pitEnteredAt) / 1000f;
                session.queue.enqueue(new EngineerMessage(
                        Priority.HIGH,
                        "Good stop. " + formatTenths(durationSeconds) + " seconds. Push now.",
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
                sb.append(" ").append(formatTenths(gap)).append(" seconds to ").append(name).append(".");
            }
        }
        if (carBehind != null) {
            float gap = gapToCarSeconds(carBehind, playerCar, trackLength);
            String name = carBehind.has("name") ? carBehind.get("name").asText() : "car behind";
            if (gap >= 0) {
                sb.append(" ").append(formatTenths(gap)).append(" seconds to ").append(name).append(".");
            }
        }
        return sb.toString();
    }

    private static final float BOX_BOX_LAP_FRACTION = 0.8f;

    private void detectPitWindowMessages(SessionEngineerState session, JsonNode playerCar,
                                          int currentLap, int trackLength) {
        int target = session.recommendedPitLap;
        if (target <= 0 || target < currentLap) {
            TRACE.debug("DETECTOR detectPitWindowMessages decision=skip reason=no_target_or_passed targetLap={} currentLap={}",
                    target, currentLap);
            return;
        }
        TRACE.debug("DETECTOR detectPitWindowMessages targetLap={} currentLap={} delta={} lastKind={} lastAnnouncedTarget={}",
                target, currentLap, target - currentLap, session.lastPitMessageKind, session.lastAnnouncedPitTargetLap);

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
                sb.append(" ").append(formatTenths(gapAhead)).append(" seconds to ").append(nameAhead).append(".");
            }
        }
        if (carBehind != null) {
            // Swap args so gap is carBehind-to-player (positive).
            float gapBehind = gapToCarSeconds(carBehind, playerCar, trackLength);
            String nameBehind = carBehind.has("name") ? carBehind.get("name").asText() : "car behind";
            if (gapBehind >= 0) {
                sb.append(" ").append(formatTenths(gapBehind)).append(" seconds to ").append(nameBehind).append(".");
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

    static String abbreviationToSpokenName(String abbr) {
        if (abbr == null) return "unknown";
        return switch (abbr) {
            case "S" -> "soft";
            case "M" -> "medium";
            case "H" -> "hard";
            case "I" -> "intermediate";
            case "W" -> "wet";
            default -> "unknown";
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static final int THROTTLE_BUFFER_SIZE = 3;
    private static final float SLOW_LAP_THROTTLE_THRESHOLD = 0.40f;
    private static final float SLOW_LAP_GAP_THRESHOLD_SEC = 4.0f;
    private static final long SLOW_LAP_COOLDOWN_MS = 15_000L;

    private static void updateThrottleBuffer(SessionEngineerState session, JsonNode playerCar) {
        if (!playerCar.has("throttle")) return;
        float throttle = (float) playerCar.get("throttle").asDouble();
        session.playerThrottleBuffer.addLast(throttle);
        while (session.playerThrottleBuffer.size() > THROTTLE_BUFFER_SIZE) {
            session.playerThrottleBuffer.removeFirst();
        }
    }

    private static boolean isPlayerOnSlowLap(SessionEngineerState session) {
        if (session.playerThrottleBuffer.size() < THROTTLE_BUFFER_SIZE) return false;
        float sum = 0f;
        for (Float f : session.playerThrottleBuffer) sum += f;
        return (sum / session.playerThrottleBuffer.size()) < SLOW_LAP_THROTTLE_THRESHOLD;
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
        boolean isPractice() { return sessionType >= 1 && sessionType <= 4; }
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

        // Practice: grip message cadence
        int lastGripMessageLap = 0;
        int nextGripMessageLap = 0;

        // Practice: tyre+fuel summary cadence
        int lastTyreFuelSummaryLap = 0;

        // Practice: sector comparison state (car idx -> [best S1, best S2, best S3] in ms)
        final Map<Integer, long[]> bestSectorTimesByCar = new HashMap<>();
        final long[] playerBestSectors = new long[3];
        final int[] lastSectorComparisonLap = new int[3];
        final int[] previousCarSectors = new int[22];

        // Practice: track traffic (pit-stint-scoped)
        boolean trackTrafficMessageSentThisStint = false;

        // Slow-lap throttle buffer (rolling 3 samples) and per-driver cooldowns
        final Deque<Float> playerThrottleBuffer = new ArrayDeque<>();
        final Map<Integer, Long> slowLapCooldownByCar = new HashMap<>();
        float previousSlowLapGapBehind = -1f;

        // Trace-only: last observed pitStatus, decoupled from detector mutation order.
        int traceLastPitStatus = 0;

        SessionEngineerState(String sessionUid, int trackId, int sessionType, int ersAssist, int drsAssist) {
            this.sessionUid = sessionUid;
            this.trackId = trackId;
            this.sessionType = sessionType;
            this.ersAssist = ersAssist;
            this.drsAssist = drsAssist;
        }
    }
}
