package dev.victormartin.telemetry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.engineer.v2.RaceEngineerServiceV2;
import dev.victormartin.telemetry.simulation.RaceSnapshot;
import dev.victormartin.telemetry.simulation.StrategyEvaluation;

@Component
public class StrategyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StrategyOrchestrator.class);
    private static final long DEBOUNCE_MS = 3_000;

    private final QueueService queueService;
    private final JdbcTemplate jdbc;
    private final RaceWebSocketHandler raceWebSocketHandler;
    private final RaceEngineerServiceV2 raceEngineerService;
    private final dev.victormartin.telemetry.simulation.LapHistoryTracker lapHistoryTracker;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "strategy-orchestrator");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> pendingRun;
    private volatile JsonNode latestState;
    private volatile String sessionUid;

    // Trigger detection state
    private int previousPlayerLap = -1;
    private int previousSafetyCarStatus = 0;
    private int previousWeather = -1;
    private final int[] previousPitCounts = new int[22];

    // Leaderboard
    private volatile StrategyLeaderboard leaderboard;

    public StrategyOrchestrator(QueueService queueService, JdbcTemplate jdbc,
                                 RaceWebSocketHandler raceWebSocketHandler,
                                 RaceEngineerServiceV2 raceEngineerService,
                                 dev.victormartin.telemetry.simulation.LapHistoryTracker lapHistoryTracker) {
        this.lapHistoryTracker = lapHistoryTracker;
        this.queueService = queueService;
        this.jdbc = jdbc;
        this.raceWebSocketHandler = raceWebSocketHandler;
        this.raceEngineerService = raceEngineerService;
    }

    public void onStateUpdate(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            latestState = node;

            if (sessionUid == null && node.has("sessionUid")) {
                sessionUid = node.get("sessionUid").asText();
            }

            if (detectTrigger(node)) {
                if (leaderboard != null) {
                    leaderboard = new StrategyLeaderboard(
                            leaderboard.evaluatedAtLap(), true, leaderboard.evaluation());
                    broadcastLeaderboard();
                }
                scheduleDebouncedRun();
            }
        } catch (Exception e) {
            log.warn("StrategyOrchestrator: failed to parse state: {}", e.getMessage());
        }
    }

    public void onEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String event = node.has("event") ? node.get("event").asText() : "";
            if ("SCAR".equals(event)) {
                scheduleDebouncedRun();
            }
        } catch (Exception e) {
            log.warn("StrategyOrchestrator: failed to parse event: {}", e.getMessage());
        }
    }

    public void reset() {
        previousPlayerLap = -1;
        previousSafetyCarStatus = 0;
        previousWeather = -1;
        for (int i = 0; i < previousPitCounts.length; i++) previousPitCounts[i] = 0;
        latestState = null;
        sessionUid = null;
        leaderboard = null;
    }

    public void completeJob(String jobId, int evaluatedAtLap, StrategyEvaluation evaluation) {
        leaderboard = new StrategyLeaderboard(evaluatedAtLap, false, evaluation);
        log.info("StrategyOrchestrator: leaderboard updated at lap {}", evaluatedAtLap);
        broadcastLeaderboard();
        raceEngineerService.onStrategyEvaluation(evaluatedAtLap, evaluation);
    }

    public StrategyLeaderboard getLeaderboard() {
        return leaderboard;
    }

    // ── trigger detection ─────────────────────────────────────────────

    boolean detectTrigger(JsonNode state) {
        boolean triggered = false;
        JsonNode cars = state.get("cars");

        if (cars != null && cars.isArray()) {
            // Find player car (ai=false)
            for (JsonNode car : cars) {
                boolean ai = !car.has("ai") || car.get("ai").asBoolean();
                if (!ai) {
                    int lap = car.has("lap") ? car.get("lap").asInt() : 0;
                    if (lap > previousPlayerLap && previousPlayerLap > 0) {
                        triggered = true;
                    }
                    previousPlayerLap = lap;
                    break;
                }
            }

            // Pit stop detected for any car
            for (JsonNode car : cars) {
                int idx = car.has("idx") ? car.get("idx").asInt() : -1;
                int pits = car.has("pits") ? car.get("pits").asInt() : 0;
                if (idx >= 0 && idx < previousPitCounts.length) {
                    if (pits > previousPitCounts[idx]) {
                        triggered = true;
                    }
                    previousPitCounts[idx] = pits;
                }
            }
        }

        // Safety car change
        int scStatus = state.has("safetyCarStatus") ? state.get("safetyCarStatus").asInt() : 0;
        if (scStatus != previousSafetyCarStatus && previousSafetyCarStatus >= 0) {
            triggered = true;
        }
        previousSafetyCarStatus = scStatus;

        // Weather change
        int weather = state.has("weather") ? state.get("weather").asInt() : 0;
        if (weather != previousWeather && previousWeather >= 0) {
            triggered = true;
        }
        previousWeather = weather;

        return triggered;
    }

    // ── debounce & execution ──────────────────────────────────────────

    private synchronized void scheduleDebouncedRun() {
        if (pendingRun != null && !pendingRun.isDone()) {
            pendingRun.cancel(false);
        }
        pendingRun = scheduler.schedule(this::enqueueStrategyRequest, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void enqueueStrategyRequest() {
        JsonNode state = latestState;
        if (state == null) return;

        try {
            RaceSnapshot snapshot = assembleSnapshotWithTyreSets(state);
            if (snapshot == null) return;

            // Find player car index
            int playerCarIndex = 0;
            for (var car : snapshot.cars()) {
                if (!car.aiControlled()) {
                    playerCarIndex = car.carIndex();
                    break;
                }
            }

            int playerTyreSets = 0;
            for (var car : snapshot.cars()) {
                if (car.carIndex() == playerCarIndex) {
                    playerTyreSets = car.tyreSets().size();
                    break;
                }
            }

            String jobId = UUID.randomUUID().toString().substring(0, 8);
            String uid = sessionUid != null ? sessionUid : "-";
            String payload = objectMapper.writeValueAsString(Map.of(
                    "jobId", jobId,
                    "sessionUid", uid,
                    "playerCarIndex", playerCarIndex,
                    "raceSnapshot", snapshot));
            queueService.enqueue("PDBADMIN.STRATEGY_REQUEST", payload);
            log.info("StrategyOrchestrator: enqueued strategy request {} (lap={}/{}, playerCarIndex={}, playerTyreSets={})", jobId, snapshot.currentLap(), snapshot.totalLaps(), playerCarIndex, playerTyreSets);
        } catch (Exception e) {
            log.error("StrategyOrchestrator: failed to enqueue: {}", e.getMessage(), e);
        }
    }

    // ── snapshot assembly with tyre sets ──────────────────────────────

    RaceSnapshot assembleSnapshotWithTyreSets(JsonNode state) {
        try {
            int trackId = state.get("trackId").asInt();
            int totalLaps = state.has("totalLaps") ? state.get("totalLaps").asInt() : 50;
            int weather = state.has("weather") ? state.get("weather").asInt() : 0;
            int trackTemp = state.has("trackTemp") ? state.get("trackTemp").asInt() : 30;
            int airTemp = state.has("airTemp") ? state.get("airTemp").asInt() : 25;
            boolean safetyCar = state.has("safetyCarStatus") && state.get("safetyCarStatus").asInt() > 0;

            JsonNode carsNode = state.get("cars");
            if (carsNode == null || !carsNode.isArray()) return null;

            int currentLap = 1;
            int currentSector = 0;
            for (JsonNode c : carsNode) {
                if (c.has("pos") && c.get("pos").asInt() == 1) {
                    currentLap = c.has("lap") ? c.get("lap").asInt() : 1;
                    currentSector = c.has("sector") ? c.get("sector").asInt() : 0;
                    break;
                }
            }

            // Load tyre sets from DB keyed by car index
            String uid = sessionUid;
            Map<Integer, List<RaceSnapshot.TyreSet>> tyreSetsByCarIndex = new HashMap<>();
            if (uid != null) {
                try {
                    var rows = jdbc.queryForList(
                            """
                            SELECT car_index, tyre_compound_visual, available, wear,
                                   life_span, usable_life, lap_delta_time_ms, fitted
                            FROM tyre_sets
                            WHERE session_uid = ?
                            ORDER BY car_index, set_index
                            """,
                            Long.parseUnsignedLong(uid, 16));
                    for (var row : rows) {
                        int carIdx = ((Number) row.get("CAR_INDEX")).intValue();
                        tyreSetsByCarIndex.computeIfAbsent(carIdx, k -> new ArrayList<>())
                                .add(new RaceSnapshot.TyreSet(
                                        ((Number) row.get("TYRE_COMPOUND_VISUAL")).intValue(),
                                        ((Number) row.get("AVAILABLE")).intValue() == 1,
                                        ((Number) row.get("WEAR")).intValue(),
                                        row.get("LIFE_SPAN") != null ? ((Number) row.get("LIFE_SPAN")).intValue() : 0,
                                        row.get("USABLE_LIFE") != null ? ((Number) row.get("USABLE_LIFE")).intValue() : 0,
                                        row.get("LAP_DELTA_TIME_MS") != null ? ((Number) row.get("LAP_DELTA_TIME_MS")).intValue() : 0,
                                        row.get("FITTED") != null && ((Number) row.get("FITTED")).intValue() == 1));
                    }
                } catch (Exception e) {
                    log.warn("StrategyOrchestrator: failed to load tyre sets: {}", e.getMessage());
                }
            }

            List<RaceSnapshot.CarSnapshot> cars = new ArrayList<>();
            for (JsonNode c : carsNode) {
                int idx = c.get("idx").asInt();
                String name = c.has("name") ? c.get("name").asText() : "Car " + idx;
                boolean ai = !c.has("ai") || c.get("ai").asBoolean();
                int pos = c.has("pos") ? c.get("pos").asInt() : idx + 1;
                int tyreCompound = mapTyreCode(c.has("tyre") ? c.get("tyre").asText() : "M");
                int tyreAge = c.has("tyreAge") ? c.get("tyreAge").asInt() : 0;
                double fuel = c.has("fuel") ? c.get("fuel").asDouble() : 30.0;
                int lapsRemaining = Math.max(totalLaps - currentLap + 1, 1);
                double fuelBurnPerSector = fuel / (lapsRemaining * 3.0);
                int fwDmg = c.has("fwDmg") ? c.get("fwDmg").asInt() : 0;
                int flDmg = c.has("flDmg") ? c.get("flDmg").asInt() : 0;
                int engDmg = c.has("engDmg") ? c.get("engDmg").asInt() : 0;
                int pits = c.has("pits") ? c.get("pits").asInt() : 0;

                int resultStatus = c.has("resultStatus") ? c.get("resultStatus").asInt() : 2;
                if (resultStatus <= 1 || resultStatus >= 4) continue;

                double totalTimeMs = (pos - 1) * 1000.0;
                List<RaceSnapshot.TyreSet> tyreSets = tyreSetsByCarIndex.getOrDefault(idx, List.of());

                cars.add(new RaceSnapshot.CarSnapshot(
                        idx, name, ai, pos, tyreCompound, tyreAge,
                        fuel, fuelBurnPerSector, fwDmg, flDmg, engDmg,
                        pits, totalTimeMs, tyreSets,
                        lapHistoryTracker.recent(idx)));
            }

            if (cars.isEmpty()) return null;

            return new RaceSnapshot(trackId, totalLaps, currentLap, currentSector,
                    weather, trackTemp, airTemp, safetyCar, cars, null);
        } catch (Exception e) {
            log.warn("StrategyOrchestrator: snapshot assembly failed: {}", e.getMessage());
            return null;
        }
    }

    private static int mapTyreCode(String tyre) {
        return switch (tyre) {
            case "S" -> 16;
            case "M" -> 17;
            case "H" -> 18;
            case "I" -> 7;
            case "W" -> 8;
            default -> 17;
        };
    }

    private void broadcastLeaderboard() {
        StrategyLeaderboard lb = leaderboard;
        if (lb == null) return;
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "strategyEvaluation",
                    "evaluatedAtLap", lb.evaluatedAtLap(),
                    "stale", lb.stale(),
                    "evaluation", lb.evaluation()));
            raceWebSocketHandler.broadcast(json);
        } catch (Exception e) {
            log.warn("StrategyOrchestrator: broadcast failed: {}", e.getMessage());
        }
    }

    public record StrategyLeaderboard(int evaluatedAtLap, boolean stale, StrategyEvaluation evaluation) {}
}
