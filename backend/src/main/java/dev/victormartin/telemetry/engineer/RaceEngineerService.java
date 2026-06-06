package dev.victormartin.telemetry.engineer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.GameMappings;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.log.RadioMessageLog;
import dev.victormartin.telemetry.engineer.log.RadioMessageLogEntry;
import dev.victormartin.telemetry.engineer.log.RadioStrategySummary;
import dev.victormartin.telemetry.engineer.detectors.CarAheadDetector;
import dev.victormartin.telemetry.engineer.detectors.CarBehindDetector;
import dev.victormartin.telemetry.engineer.detectors.DamageDetector;
import dev.victormartin.telemetry.engineer.detectors.DrsDetector;
import dev.victormartin.telemetry.engineer.detectors.ErsModeDetector;
import dev.victormartin.telemetry.engineer.detectors.FastestLapByRivalDetector;
import dev.victormartin.telemetry.engineer.detectors.FlagChangesDetector;
import dev.victormartin.telemetry.engineer.detectors.InvalidLapDetector;
import dev.victormartin.telemetry.engineer.detectors.LapCountdownDetector;
import dev.victormartin.telemetry.engineer.detectors.PenaltiesDetector;
import dev.victormartin.telemetry.engineer.detectors.PerCornerWearDetector;
import dev.victormartin.telemetry.engineer.detectors.PeriodicSituationalAwarenessDetector;
import dev.victormartin.telemetry.engineer.detectors.PitStopCompletedDetector;
import dev.victormartin.telemetry.engineer.detectors.PitWindowMessagesDetector;
import dev.victormartin.telemetry.engineer.detectors.PositionChangeDetector;
import dev.victormartin.telemetry.engineer.detectors.PracticeLapCompleteDetector;
import dev.victormartin.telemetry.engineer.detectors.PracticeSectorComparisonDetector;
import dev.victormartin.telemetry.engineer.detectors.PracticeSpeedTrapDetector;
import dev.victormartin.telemetry.engineer.detectors.PracticeTyreFuelSummaryDetector;
import dev.victormartin.telemetry.engineer.detectors.QualifyingLapCompleteDetector;
import dev.victormartin.telemetry.engineer.detectors.QualifyingSectorDeltaDetector;
import dev.victormartin.telemetry.engineer.detectors.RaceFinishDetector;
import dev.victormartin.telemetry.engineer.detectors.RaceLapCompleteDetector;
import dev.victormartin.telemetry.engineer.detectors.SessionStartGreetingDetector;
import dev.victormartin.telemetry.engineer.detectors.SlowLapTrafficWarningDetector;
import dev.victormartin.telemetry.engineer.detectors.TyreConditionDetector;
import dev.victormartin.telemetry.engineer.detectors.TrackTrafficExitDetector;
import dev.victormartin.telemetry.engineer.detectors.WeatherDetector;
import dev.victormartin.telemetry.engineer.detectors.YellowFlagDetector;
import dev.victormartin.telemetry.engineer.llm.RadioMessageRenderer;
import dev.victormartin.telemetry.engineer.llm.RadioRenderContext;
import dev.victormartin.telemetry.simulation.RaceSnapshot;
import dev.victormartin.telemetry.simulation.StrategyEvaluation;

/**
 * Orchestrator for the race-engineer radio. Exposes the session lifecycle
 * (onSessionStarted / onStateUpdate / onSessionEnded / onEvent) that
 * {@code TelemetryTcpServer.routeMessage} drives.
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
public class RaceEngineerService {

    private static final Logger TRACE = LoggerFactory.getLogger("engineer.trace");

    // After a flashback, the game replays a few seconds of state; detectors would
    // otherwise fire a burst of spurious messages. Hold radio until things settle.
    private static final long FLASHBACK_SUPPRESS_MS = 4000;

    private final CircuitSafeZoneService safeZoneService;
    private final RaceEngineerWebSocketHandler webSocketHandler;
    private final RadioMessageLog radioMessageLog;
    private final RadioMessageRenderer renderer;
    private final long renderTimeoutMs;
    private final Executor renderExecutor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<RadioDetector> detectors;

    // Detectors with extra hooks the orchestrator needs to call directly.
    private final PitStopCompletedDetector pitStopCompleted;
    private final PitWindowMessagesDetector pitWindow;
    private final RaceFinishDetector raceFinish;

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Autowired
    public RaceEngineerService(CircuitSafeZoneService safeZoneService,
                               RaceEngineerWebSocketHandler webSocketHandler,
                               RadioMessageLog radioMessageLog,
                               RadioMessageRenderer renderer,
                               @Value("${engineer.llm.timeout-ms:500}") long renderTimeoutMs) {
        this(safeZoneService, webSocketHandler, radioMessageLog, renderer, renderTimeoutMs,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "radio-render");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /** Visible for tests — inject a same-thread executor ({@code Runnable::run}) for
     *  deterministic, synchronous delivery. */
    RaceEngineerService(CircuitSafeZoneService safeZoneService,
                        RaceEngineerWebSocketHandler webSocketHandler,
                        RadioMessageLog radioMessageLog,
                        RadioMessageRenderer renderer,
                        long renderTimeoutMs,
                        Executor renderExecutor) {
        this.safeZoneService = safeZoneService;
        this.webSocketHandler = webSocketHandler;
        this.radioMessageLog = radioMessageLog;
        this.renderer = renderer;
        this.renderTimeoutMs = renderTimeoutMs;
        this.renderExecutor = renderExecutor;
        this.pitStopCompleted = new PitStopCompletedDetector();
        this.pitWindow = new PitWindowMessagesDetector();
        this.raceFinish = new RaceFinishDetector();
        this.detectors = List.of(
                // Greeting (fires once on first ON_TRACK tick of the session)
                new SessionStartGreetingDetector(),
                // Always-on
                new FlagChangesDetector(),
                new YellowFlagDetector(),
                new FastestLapByRivalDetector(),
                new PenaltiesDetector(),
                new TyreConditionDetector(),
                new PerCornerWearDetector(),
                new DamageDetector(),
                new DrsDetector(),
                new ErsModeDetector(),
                new WeatherDetector(),
                new InvalidLapDetector(),
                // Race only
                new CarBehindDetector(),
                new CarAheadDetector(),
                new LapCountdownDetector(),
                new PositionChangeDetector(),
                pitStopCompleted,
                pitWindow,
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
        SessionState session = new SessionState(sessionUid, trackId, sessionType, kind);
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
        TRACE.debug("ENGINEER_SESSION_START sessionUid={} trackId={} sessionType={} kind={}",
                sessionUid, trackId, sessionType, kind);
    }

    public void onSessionEnded(String sessionUid) {
        SessionState removed = sessions.remove(sessionUid);
        if (removed == null) return;
        removed.queue.clear();
        for (RadioDetector d : detectors) {
            d.onSessionEnded(sessionUid);
        }
        TRACE.debug("ENGINEER_SESSION_END sessionUid={}", sessionUid);
    }

    // -- state update ---------------------------------------------------------

    public void onStateUpdate(String json) {
        try {
            JsonNode state = mapper.readTree(json);
            int trackId = state.has("trackId") ? state.get("trackId").asInt() : -1;
            SessionState session = findByTrackId(trackId);
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
                TRACE.debug("ENGINEER_PIT_TRANSITION from={} to={} lap={} lapDist={} pitStatus={} speedKmh={}",
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
                    TRACE.debug("ENGINEER_FIRE detector={} text=\"{}\"", d.name(), msg.get().text());
                    session.queue.enqueue(msg.get());
                }
            }
            // Pit-stop completed has a follow-up "out of the pits in P5..." recap.
            pitStopCompleted.takePendingRecap(tick).ifPresent(m -> {
                TRACE.debug("ENGINEER_FIRE detector=PitStopCompletedRecap text=\"{}\"", m.text());
                session.queue.enqueue(m);
            });

            // During a flashback replay, drop whatever the detectors just queued
            // (it reflects rewound/jumpy state) and deliver nothing until stable.
            if (System.currentTimeMillis() < session.radioSuppressedUntilMs) {
                session.queue.clear();
                return;
            }

            EngineerMessage delivered = session.queue.pollForDelivery(
                    lapDist, trackId, currentLap, speedKmh, safeZoneService);
            if (delivered != null) {
                renderAndDeliver(session, tick, delivered);
            }
        } catch (Exception e) {
            TRACE.warn("ENGINEER_ERROR onStateUpdate failed: {}", e.getMessage());
        }
    }

    // -- event handling (SCAR / RTMT / CHQF) ----------------------------------

    /**
     * Handles discrete game events. Every enqueued message uses
     * {@code session.currentLap} as the {@code createdAtLap} baseline (not a
     * stale last-seen lap that would be 0 until the first lap-up tick).
     * Phase C bugs 3.5 + 3.6.
     */
    public void onEvent(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String event = node.has("event") ? node.get("event").asText() : "";
            int trackId = node.has("trackId") ? node.get("trackId").asInt() : -1;
            SessionState session = trackId >= 0
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
                    String rawName = node.has("driverName") ? node.get("driverName").asText() : "";
                    String name = rawName.isBlank() ? "A car" : rawName;
                    session.queue.enqueue(new EngineerMessage(
                            Priority.NORMAL,
                            name + " has retired.",
                            System.currentTimeMillis(), lap, 2));
                }
                case "FLBK" -> session.radioSuppressedUntilMs =
                        System.currentTimeMillis() + FLASHBACK_SUPPRESS_MS;
                case "CHQF" -> {
                    session.chequeredFlag = true;
                    raceFinish.notifyChequered(session.sessionUid);
                }
                default -> { /* unknown event — ignore */ }
            }
        } catch (Exception e) {
            TRACE.warn("ENGINEER_EVENT_ERROR {}", e.getMessage());
        }
    }

    // -- strategy callback ----------------------------------------------------

    /** Pushes the next recommended pit lap to PitWindowMessagesDetector.
     * If the best strategy has no future pit (e.g. "No stop"), clear any prior recommendation so stale
     * T-1/box messages don't fire from an obsolete plan. */
    public void onStrategyEvaluation(int evaluatedAtLap, StrategyEvaluation evaluation) {
        if (evaluation == null || evaluation.strategies() == null || evaluation.strategies().isEmpty()) return;
        StrategyEvaluation.RankedStrategy best = evaluation.strategies().getFirst();
        List<RaceSnapshot.PitStrategy.PitStop> stops = best.candidate().stops();
        for (SessionState session : sessions.values()) {
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
        SessionState s = sessions.get(sessionUid);
        return s != null ? s.queue : null;
    }

    // -- helpers --------------------------------------------------------------

    private SessionState findByTrackId(int trackId) {
        for (SessionState s : sessions.values()) {
            if (s.trackId == trackId) return s;
        }
        return null;
    }

    /**
     * Renders the message off the telemetry thread, then broadcasts and logs the
     * result. The render call is bounded by {@code renderTimeoutMs}; on timeout or
     * any error it falls back to the original templated text so the driver always
     * hears something. The telemetry thread is never blocked.
     */
    private void renderAndDeliver(SessionState session, EngineerTick tick, EngineerMessage original) {
        // The single-thread renderExecutor orders the render step only; on timeout the
        // fallback completes on a scheduler thread, so delivery order is best-effort.
        RadioRenderContext ctx = buildRenderContext(session, tick, original);
        CompletableFuture
                .supplyAsync(() -> renderer.render(ctx), renderExecutor)
                .orTimeout(renderTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    TRACE.warn("ENGINEER_RENDER_FALLBACK {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
                    return original.text();
                })
                .thenAccept(rendered -> {
                    String text = rendered != null ? rendered : original.text();
                    deliver(session.sessionUid, original, text);
                    logDelivered(session, tick, original, text);
                });
    }

    private RadioRenderContext buildRenderContext(SessionState session, EngineerTick tick,
                                                  EngineerMessage original) {
        JsonNode playerCar = tick.playerCar();
        String tyre = playerCar.has("tyre") ? playerCar.get("tyre").asText() : null;
        int tyreAge = playerCar.has("tyreAge") ? playerCar.get("tyreAge").asInt() : 0;
        int sector = playerCar.has("sector") ? playerCar.get("sector").asInt() : 0;
        String driverName = playerCar.has("name") ? playerCar.get("name").asText() : null;
        String circuitName = GameMappings.trackName(tick.trackId());
        String strategies = RadioStrategySummary.topThreeJson(mapper, session.latestEvaluation);
        return new RadioRenderContext(
                original.text(), original.priority(), tick.sessionType(), tick.trackId(), circuitName,
                tick.currentLap(), tick.totalLaps(), tick.playerPos(), driverName,
                tyre, tyreAge, sector, strategies);
    }

    /** Sentence-boundary marker for the iOS client: it splits on this to speak each
     * sentence as its own utterance (a built-in pause) and swaps it back to a space for
     * display. Inserted AFTER rendering so the LLM can't drop/move it. The terminator is
     * kept and the inter-sentence space is replaced, so decimals (no space after the dot)
     * are never split and `!`/`?` survive. */
    static final String SENTENCE_SEP = "|";

    static String markSentenceBoundaries(String text) {
        return text == null ? null : text.replaceAll("([.!?]) +", "$1" + SENTENCE_SEP);
    }

    private void deliver(String sessionUid, EngineerMessage original, String renderedText) {
        try {
            String wireJson = mapper.writeValueAsString(Map.of(
                    "type", "raceEngineer",
                    "sessionUid", sessionUid,
                    "priority", original.priority().name(),
                    "text", markSentenceBoundaries(renderedText),
                    "timestamp", original.createdAt()));
            webSocketHandler.broadcast(wireJson);
            TRACE.debug("ENGINEER_DELIVER priority={} text=\"{}\"", original.priority(), renderedText);
        } catch (Exception e) {
            TRACE.warn("ENGINEER_DELIVER_FAILED {}", e.getMessage());
        }
    }

    private void logDelivered(SessionState session, EngineerTick tick,
                              EngineerMessage message, String renderedText) {
        try {
            JsonNode playerCar = tick.playerCar();
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
                    renderedText,
                    strategies,
                    tick.wallClockMs()));
        } catch (Exception e) {
            TRACE.warn("ENGINEER_RADIO_LOG_FAILED {}", e.getMessage());
        }
    }

    static class SessionState {
        final String sessionUid;
        final int trackId;
        final int sessionType;
        final SessionKind kind;
        final RaceEngineerQueue queue = new RaceEngineerQueue();
        PitState lastPitState;        // null until first tick
        int currentLap = 0;
        boolean chequeredFlag = false;
        long radioSuppressedUntilMs = 0;  // set on flashback; radio held until then
        volatile StrategyEvaluation latestEvaluation;   // null until first strategy push

        SessionState(String sessionUid, int trackId, int sessionType, SessionKind kind) {
            this.sessionUid = sessionUid;
            this.trackId = trackId;
            this.sessionType = sessionType;
            this.kind = kind;
        }
    }
}
