package dev.victormartin.telemetry.engineer.v2;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.engineer.CircuitSafeZoneService;
import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.RaceEngineerQueue;
import dev.victormartin.telemetry.engineer.RaceEngineerWebSocketHandler;
import dev.victormartin.telemetry.engineer.log.RadioMessageLog;
import dev.victormartin.telemetry.engineer.log.RadioMessageLogEntry;
import dev.victormartin.telemetry.engineer.log.RadioStrategySummary;
import dev.victormartin.telemetry.engineer.v2.detectors.CarAheadDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.CarBehindDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.DamageDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.DrsDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.ErsModeDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.FastestLapByRivalDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.FlagChangesDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.FuelDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.LapCountdownDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PenaltiesDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PerCornerWearDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PeriodicSituationalAwarenessDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PitStopCompletedDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PitWindowMessagesDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PositionChangeDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PracticeLapCompleteDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PracticeSectorComparisonDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PracticeSpeedTrapDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.PracticeTyreFuelSummaryDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.QualifyingLapCompleteDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.QualifyingSectorDeltaDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.RaceFinishDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.RaceLapCompleteDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.SessionStartGreetingDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.SlowLapTrafficWarningDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.TyreConditionDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.TrackTrafficExitDetector;
import dev.victormartin.telemetry.engineer.v2.detectors.WeatherDetector;
import dev.victormartin.telemetry.simulation.RaceSnapshot;
import dev.victormartin.telemetry.simulation.StrategyEvaluation;

/**
 * Orchestrator for v2. Mirrors v1 {@code RaceEngineerService} lifecycle
 * (onSessionStarted / onStateUpdate / onSessionEnded / onEvent) so cut-over in
 * {@code TelemetryTcpServer.routeMessage} is a one-line swap.
 *
 * Internally: parses the per-tick JSON, computes {@link PitState} via
 * {@link PitStateClassifier}, builds an {@link EngineerTick}, and dispatches
 * to every registered {@link RadioDetector} that opts into the current pit
 * state and session kind. Reuses {@link RaceEngineerQueue} and
 * {@link CircuitSafeZoneService} unchanged.
 *
 * Detectors are constructor-listed (not Spring-collected) so registration is
 * explicit and the iteration order is stable. Add new detectors here as they
 * land in iterations 3-4.
 */
@Component
public class RaceEngineerServiceV2 {

    private static final Logger TRACE = LoggerFactory.getLogger("engineer.trace");

    private final CircuitSafeZoneService safeZoneService;
    private final RaceEngineerWebSocketHandler webSocketHandler;
    private final RadioMessageLog radioMessageLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<RadioDetector> detectors;

    // Detectors with extra hooks the orchestrator needs to call directly.
    private final PitStopCompletedDetector pitStopCompleted;
    private final PitWindowMessagesDetector pitWindow;
    private final RaceFinishDetector raceFinish;

    private final Map<String, V2SessionState> sessions = new ConcurrentHashMap<>();

    public RaceEngineerServiceV2(CircuitSafeZoneService safeZoneService,
                                 RaceEngineerWebSocketHandler webSocketHandler,
                                 RadioMessageLog radioMessageLog) {
        this.safeZoneService = safeZoneService;
        this.webSocketHandler = webSocketHandler;
        this.radioMessageLog = radioMessageLog;
        this.pitStopCompleted = new PitStopCompletedDetector();
        this.pitWindow = new PitWindowMessagesDetector();
        this.raceFinish = new RaceFinishDetector();
        this.detectors = List.of(
                // Greeting (fires once on first ON_TRACK tick of the session)
                new SessionStartGreetingDetector(),
                // Always-on
                new FlagChangesDetector(),
                new FastestLapByRivalDetector(),
                new PenaltiesDetector(),
                new TyreConditionDetector(),
                new PerCornerWearDetector(),
                new DamageDetector(),
                new DrsDetector(),
                new ErsModeDetector(),
                new WeatherDetector(),
                // Race only
                new CarBehindDetector(),
                new CarAheadDetector(),
                new LapCountdownDetector(),
                new PositionChangeDetector(),
                pitStopCompleted,
                pitWindow,
                new FuelDetector(),
                new PeriodicSituationalAwarenessDetector(),
                new RaceLapCompleteDetector(),
                raceFinish,
                // Qualifying / Sprint Quali
                new QualifyingSectorDeltaDetector(),
                new QualifyingLapCompleteDetector(),
                // Practice
                new PracticeTyreFuelSummaryDetector(),
                new PracticeLapCompleteDetector(),
                new PracticeSectorComparisonDetector(),
                new PracticeSpeedTrapDetector(),
                // Pit-state-bug fixes (Group A)
                new TrackTrafficExitDetector(),
                new SlowLapTrafficWarningDetector()
        );
    }

    // -- session lifecycle ----------------------------------------------------

    public void onSessionStarted(String sessionUid, int trackId, int sessionType,
                                 int ersAssist, int drsAssist) {
        sessions.values().removeIf(s -> s.trackId == trackId);
        SessionKind kind = SessionKind.fromSessionType(sessionType);
        V2SessionState session = new V2SessionState(sessionUid, trackId, sessionType, kind);
        sessions.put(sessionUid, session);
        for (RadioDetector d : detectors) {
            d.onSessionStarted(sessionUid, trackId, sessionType);
        }
        // Tell every connected client the active session uid so they can re-target
        // their per-session message filter. Without this broadcast the iOS client
        // keeps the previous sessionUid and silently drops every raceEngineer
        // message that carries the new uid.
        webSocketHandler.broadcast("{\"type\":\"sessionStarted\",\"sessionUid\":\"" + sessionUid + "\"}");
        // The greeting is now fired by SessionStartGreetingDetector on the first
        // ON_TRACK tick — enqueueing it here would expire (60s NORMAL TTL) while
        // the car sits in the garage with no safe zone available.
        TRACE.debug("V2_SESSION_START sessionUid={} trackId={} sessionType={} kind={}",
                sessionUid, trackId, sessionType, kind);
    }

    public void onSessionEnded(String sessionUid) {
        V2SessionState removed = sessions.remove(sessionUid);
        if (removed == null) return;
        removed.queue.clear();
        for (RadioDetector d : detectors) {
            d.onSessionEnded(sessionUid);
        }
        TRACE.debug("V2_SESSION_END sessionUid={}", sessionUid);
    }

    // -- state update ---------------------------------------------------------

    public void onStateUpdate(String json) {
        try {
            JsonNode state = mapper.readTree(json);
            int trackId = state.has("trackId") ? state.get("trackId").asInt() : -1;
            V2SessionState session = findByTrackId(trackId);
            if (session == null) return;

            JsonNode cars = state.get("cars");
            if (cars == null || !cars.isArray()) return;

            JsonNode playerCar = null;
            for (JsonNode car : cars) {
                if (car.has("ai") && !car.get("ai").asBoolean()) { playerCar = car; break; }
            }
            if (playerCar == null) return;

            int currentLap = playerCar.has("lap") ? playerCar.get("lap").asInt() : 1;
            float lapDist = playerCar.has("lapDist") ? (float) playerCar.get("lapDist").asDouble() : 0f;
            int speedKmh = playerCar.has("speed") ? playerCar.get("speed").asInt() : 0;
            int pitStatus = playerCar.has("pitStatus") ? playerCar.get("pitStatus").asInt() : 0;
            int pitLaneTimer = playerCar.has("pitLaneTimerActive") ? playerCar.get("pitLaneTimerActive").asInt() : 0;
            int pitLaneMs = playerCar.has("pitLaneTimeMs") ? playerCar.get("pitLaneTimeMs").asInt() : 0;
            float throttle = playerCar.has("throttle") ? (float) playerCar.get("throttle").asDouble() : 0f;
            int playerPos = playerCar.has("pos") ? playerCar.get("pos").asInt() : 0;
            int totalLaps = state.has("totalLaps") ? state.get("totalLaps").asInt() : 0;
            int trackLength = state.has("trackLength") ? state.get("trackLength").asInt() : 5000;

            PitState previousPitState = session.lastPitState;
            PitState pitState = PitStateClassifier.classify(
                    pitStatus, pitLaneTimer, pitLaneMs, lapDist, speedKmh, session.kind, previousPitState);
            session.lastPitState = pitState;
            session.currentLap = currentLap;

            if (pitState != previousPitState && previousPitState != null) {
                TRACE.debug("V2_PIT_TRANSITION from={} to={} lap={} lapDist={} pitStatus={} speedKmh={}",
                        previousPitState, pitState, currentLap, lapDist, pitStatus, speedKmh);
            }

            EngineerTick tick = new EngineerTick(
                    System.currentTimeMillis(),
                    session.sessionUid,
                    session.sessionType,
                    session.kind,
                    trackId,
                    currentLap,
                    totalLaps,
                    trackLength,
                    pitState,
                    previousPitState != null ? previousPitState : pitState,
                    state,
                    playerCar,
                    cars,
                    playerPos,
                    lapDist,
                    speedKmh,
                    throttle,
                    pitStatus,
                    pitLaneTimer,
                    pitLaneMs);

            for (RadioDetector d : detectors) {
                if (!d.appliesToStates().isEmpty() && !d.appliesToStates().contains(pitState)) continue;
                if (!d.appliesToSessions().isEmpty() && !d.appliesToSessions().contains(session.kind)) continue;
                Optional<EngineerMessage> msg = d.evaluate(tick);
                if (msg.isPresent()) {
                    TRACE.debug("V2_FIRE detector={} text=\"{}\"", d.name(), msg.get().text());
                    session.queue.enqueue(msg.get());
                }
            }
            // Pit-stop completed has a follow-up "out of the pits in P5..." recap.
            pitStopCompleted.takePendingRecap(tick).ifPresent(m -> {
                TRACE.debug("V2_FIRE detector=PitStopCompletedRecap text=\"{}\"", m.text());
                session.queue.enqueue(m);
            });

            EngineerMessage delivered = session.queue.pollForDelivery(
                    lapDist, trackId, currentLap, speedKmh, safeZoneService);
            if (delivered != null) {
                deliver(session.sessionUid, delivered);
                logDelivered(session, tick, playerCar, delivered);
            }
        } catch (Exception e) {
            TRACE.warn("V2_ERROR onStateUpdate failed: {}", e.getMessage());
        }
    }

    // -- event handling (SCAR / RTMT / CHQF) ----------------------------------

    /**
     * Handles discrete game events. v2 fix vs. v1: every enqueued message uses
     * {@code session.currentLap} as the {@code createdAtLap} baseline, not the
     * stale {@code lastPlayerLap} v1 used (which was 0 until the first
     * lap-up tick). Phase C bugs 3.5 + 3.6.
     */
    public void onEvent(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String event = node.has("event") ? node.get("event").asText() : "";
            int trackId = node.has("trackId") ? node.get("trackId").asInt() : -1;
            V2SessionState session = trackId >= 0
                    ? findByTrackId(trackId)
                    : sessions.values().stream().findFirst().orElse(null);
            if (session == null) return;

            int lap = session.currentLap;
            switch (event) {
                case "SCAR" -> session.queue.enqueue(new EngineerMessage(
                        Priority.IMMEDIATE,
                        "Safety car deployed. Bunch up, stay within ten car lengths. We'll talk strategy.",
                        System.currentTimeMillis(), lap, 3));
                case "RTMT" -> {
                    String name = node.has("driverName") ? node.get("driverName").asText() : "A car";
                    session.queue.enqueue(new EngineerMessage(
                            Priority.NORMAL,
                            name + " has retired. Watch for debris on track.",
                            System.currentTimeMillis(), lap, 2));
                }
                case "CHQF" -> {
                    session.chequeredFlag = true;
                    raceFinish.notifyChequered(session.sessionUid);
                }
                default -> { /* unknown event — ignore */ }
            }
        } catch (Exception e) {
            TRACE.warn("V2_EVENT_ERROR {}", e.getMessage());
        }
    }

    // -- strategy callback ----------------------------------------------------

    /** Mirror of v1 onStrategyEvaluation — pushes the next recommended pit lap to PitWindowMessagesDetector.
     * If the best strategy has no future pit (e.g. "No stop"), clear any prior recommendation so stale
     * T-1/box messages don't fire from an obsolete plan. */
    public void onStrategyEvaluation(int evaluatedAtLap, StrategyEvaluation evaluation) {
        if (evaluation == null || evaluation.strategies() == null || evaluation.strategies().isEmpty()) return;
        StrategyEvaluation.RankedStrategy best = evaluation.strategies().getFirst();
        List<RaceSnapshot.PitStrategy.PitStop> stops = best.candidate().stops();
        for (V2SessionState session : sessions.values()) {
            session.latestEvaluation = evaluation;
            int lap = session.currentLap;
            RaceSnapshot.PitStrategy.PitStop nextStop = null;
            if (stops != null) {
                for (RaceSnapshot.PitStrategy.PitStop stop : stops) {
                    if (stop.onLap() > lap) {
                        nextStop = stop;
                        break;
                    }
                }
            }
            if (nextStop != null) {
                pitWindow.setRecommendation(session.sessionUid, nextStop.onLap(), nextStop.newCompound());
            } else {
                pitWindow.setRecommendation(session.sessionUid, -1, 0);
            }
        }
    }

    /** Visible for tests — peek at queued-but-not-yet-delivered messages. */
    public RaceEngineerQueue queueFor(String sessionUid) {
        V2SessionState s = sessions.get(sessionUid);
        return s != null ? s.queue : null;
    }

    // -- helpers --------------------------------------------------------------

    private V2SessionState findByTrackId(int trackId) {
        for (V2SessionState s : sessions.values()) {
            if (s.trackId == trackId) return s;
        }
        return null;
    }

    private void deliver(String sessionUid, EngineerMessage message) {
        try {
            String wireJson = mapper.writeValueAsString(Map.of(
                    "type", "raceEngineer",
                    "sessionUid", sessionUid,
                    "priority", message.priority().name(),
                    "text", message.text(),
                    "timestamp", message.createdAt()));
            webSocketHandler.broadcast(wireJson);
            TRACE.debug("V2_DELIVER priority={} text=\"{}\"", message.priority(), message.text());
        } catch (Exception e) {
            TRACE.warn("V2_DELIVER_FAILED {}", e.getMessage());
        }
    }

    private void logDelivered(V2SessionState session, EngineerTick tick,
                              JsonNode playerCar, EngineerMessage message) {
        try {
            String tyre = playerCar.has("tyre") ? playerCar.get("tyre").asText() : null;
            int tyreAge = playerCar.has("tyreAge") ? playerCar.get("tyreAge").asInt() : 0;
            int sector = playerCar.has("sector") ? playerCar.get("sector").asInt() : 0;
            String strategies = RadioStrategySummary.topThreeJson(mapper, session.latestEvaluation);
            radioMessageLog.record(new RadioMessageLogEntry(
                    session.sessionUid,
                    tick.trackId(),
                    tick.sessionType(),
                    tick.currentLap(),
                    tick.totalLaps(),
                    tick.playerPos(),
                    tick.playerLapDist(),
                    sector,
                    tick.pitState().name(),
                    tyre,
                    tyreAge,
                    message.priority().name(),
                    message.text(),
                    strategies,
                    tick.wallClockMs()));
        } catch (Exception e) {
            TRACE.warn("V2_RADIO_LOG_FAILED {}", e.getMessage());
        }
    }

    static class V2SessionState {
        final String sessionUid;
        final int trackId;
        final int sessionType;
        final SessionKind kind;
        final RaceEngineerQueue queue = new RaceEngineerQueue();
        PitState lastPitState;        // null until first tick
        int currentLap = 0;
        boolean chequeredFlag = false;
        volatile StrategyEvaluation latestEvaluation;   // null until first strategy push

        V2SessionState(String sessionUid, int trackId, int sessionType, SessionKind kind) {
            this.sessionUid = sessionUid;
            this.trackId = trackId;
            this.sessionType = sessionType;
            this.kind = kind;
        }
    }
}
