package dev.victormartin.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.victormartin.telemetry.simulation.RaceSnapshot;
import dev.victormartin.telemetry.simulation.SimulationResult;

/**
 * Orchestrates automatic simulation triggers during a live race.
 * Detects lap completions and disruptive events, debounces triggers,
 * and runs simulations asynchronously via a single-thread executor.
 */
@Component
public class SimulationOrchestrator {

    private static final long DEBOUNCE_MS = 3_000;
    private static final int MAX_STORED_RESULTS = 50;

    private final RestClient restClient;
    private final RaceWebSocketHandler raceWebSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-orchestrator");
        t.setDaemon(true);
        return t;
    });

    // Job store: jobId -> result (or null if still running)
    private final Map<String, SimulationJob> jobs = new ConcurrentHashMap<>();

    // Debounce state
    private ScheduledFuture<?> pendingRun;
    private final AtomicBoolean simulationRunning = new AtomicBoolean(false);
    private volatile JsonNode latestState;
    private volatile boolean rerunRequested;

    // Trigger detection state
    private int previousLeaderLap = -1;
    private int previousSafetyCarStatus = 0;
    private final int[] previousPitStatus = new int[22];

    public SimulationOrchestrator(@Value("${simulator.base-url}") String simulatorBaseUrl,
                                  RaceWebSocketHandler raceWebSocketHandler) {
        this.restClient = RestClient.builder().baseUrl(simulatorBaseUrl).build();
        this.raceWebSocketHandler = raceWebSocketHandler;
    }

    /**
     * Called by TelemetryTcpServer for each state message.
     */
    public void onStateUpdate(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            latestState = node;

            if (detectTrigger(node)) {
                scheduleDebouncedRun();
            }
        } catch (Exception e) {
            System.err.println("SimulationOrchestrator: failed to parse state: " + e.getMessage());
        }
    }

    /**
     * Called by TelemetryTcpServer for event messages (SCAR, RTMT, COLL).
     */
    public void onEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String event = node.has("event") ? node.get("event").asText() : "";

            switch (event) {
                case "SCAR", "RTMT", "COLL" -> {
                    System.out.println("SimulationOrchestrator: disruptive event " + event);
                    scheduleDebouncedRun();
                }
            }
        } catch (Exception e) {
            System.err.println("SimulationOrchestrator: failed to parse event: " + e.getMessage());
        }
    }

    /**
     * Get a stored simulation job by ID.
     */
    public SimulationJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Manually trigger a simulation with the latest state. Returns jobId or null if no state.
     */
    public String triggerNow() {
        JsonNode state = latestState;
        if (state == null) return null;
        return executeSimulation(state);
    }

    /**
     * Reset trigger detection state (e.g., on session start).
     */
    public void reset() {
        previousLeaderLap = -1;
        previousSafetyCarStatus = 0;
        for (int i = 0; i < previousPitStatus.length; i++) previousPitStatus[i] = 0;
        latestState = null;
        rerunRequested = false;
    }

    // ── trigger detection ─────────────────────────────────────────────

    boolean detectTrigger(JsonNode state) {
        boolean triggered = false;

        // 1. Lap completion: leader's lap number increased
        JsonNode cars = state.get("cars");
        if (cars != null && cars.isArray()) {
            int leaderLap = 0;
            for (JsonNode car : cars) {
                int pos = car.has("pos") ? car.get("pos").asInt() : 99;
                if (pos == 1) {
                    leaderLap = car.has("lap") ? car.get("lap").asInt() : 0;
                    break;
                }
            }
            if (leaderLap > previousLeaderLap && previousLeaderLap > 0) {
                System.out.println("SimulationOrchestrator: leader completed lap " + leaderLap);
                triggered = true;
            }
            previousLeaderLap = leaderLap;

            // 2. Pit stop detected
            for (JsonNode car : cars) {
                int idx = car.has("idx") ? car.get("idx").asInt() : -1;
                int pitStatus = car.has("pitStatus") ? car.get("pitStatus").asInt() : 0;
                if (idx >= 0 && idx < previousPitStatus.length) {
                    if (pitStatus > 0 && previousPitStatus[idx] == 0) {
                        System.out.println("SimulationOrchestrator: pit stop detected for car " + idx);
                        triggered = true;
                    }
                    previousPitStatus[idx] = pitStatus;
                }
            }
        }

        // 3. Safety car status change
        int scStatus = state.has("safetyCarStatus") ? state.get("safetyCarStatus").asInt() : 0;
        if (scStatus != previousSafetyCarStatus) {
            System.out.println("SimulationOrchestrator: safety car status changed " + previousSafetyCarStatus + " -> " + scStatus);
            triggered = true;
        }
        previousSafetyCarStatus = scStatus;

        return triggered;
    }

    // ── debounce & execution ──────────────────────────────────────────

    private synchronized void scheduleDebouncedRun() {
        if (pendingRun != null && !pendingRun.isDone()) {
            pendingRun.cancel(false);
        }
        pendingRun = scheduler.schedule(this::runOrQueue, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void runOrQueue() {
        JsonNode state = latestState;
        if (state == null) return;

        if (simulationRunning.compareAndSet(false, true)) {
            executeSimulation(state);
        } else {
            // A simulation is already running; mark for re-run with latest state
            rerunRequested = true;
        }
    }

    private String executeSimulation(JsonNode state) {
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        jobs.put(jobId, new SimulationJob(jobId, System.currentTimeMillis(), null));

        // Evict old jobs if too many
        if (jobs.size() > MAX_STORED_RESULTS) {
            jobs.entrySet().stream()
                    .filter(e -> e.getValue().result() != null)
                    .sorted((a, b) -> Long.compare(a.getValue().startedAt(), b.getValue().startedAt()))
                    .limit(jobs.size() - MAX_STORED_RESULTS)
                    .forEach(e -> jobs.remove(e.getKey()));
        }

        scheduler.execute(() -> {
            try {
                RaceSnapshot snapshot = assembleSnapshot(state);
                if (snapshot == null) {
                    System.err.println("SimulationOrchestrator: failed to assemble snapshot for job " + jobId);
                    jobs.remove(jobId);
                    return;
                }

                SimulationResult result = restClient.post()
                        .uri("/simulate")
                        .body(snapshot)
                        .retrieve()
                        .body(SimulationResult.class);

                jobs.put(jobId, new SimulationJob(jobId, jobs.get(jobId).startedAt(), result));
                System.out.println("SimulationOrchestrator: job " + jobId + " completed in "
                        + result.wallClockMs() + "ms (" + result.iterations() + " iterations)");

                // Push result to portal
                String resultJson = objectMapper.writeValueAsString(Map.of(
                        "type", "simulationResult",
                        "jobId", jobId,
                        "result", result));
                raceWebSocketHandler.broadcast(resultJson);
            } catch (Exception e) {
                System.err.println("SimulationOrchestrator: job " + jobId + " failed: " + e.getMessage());
                jobs.remove(jobId);
            } finally {
                simulationRunning.set(false);

                // If another trigger fired while we were running, re-run with latest state
                if (rerunRequested) {
                    rerunRequested = false;
                    runOrQueue();
                }
            }
        });

        return jobId;
    }

    // ── snapshot assembly ─────────────────────────────────────────────

    RaceSnapshot assembleSnapshot(JsonNode state) {
        try {
            int trackId = state.get("trackId").asInt();
            int totalLaps = state.has("totalLaps") ? state.get("totalLaps").asInt() : 50;
            int weather = state.has("weather") ? state.get("weather").asInt() : 0;
            int trackTemp = state.has("trackTemp") ? state.get("trackTemp").asInt() : 30;
            int airTemp = state.has("airTemp") ? state.get("airTemp").asInt() : 25;
            boolean safetyCar = state.has("safetyCarStatus") && state.get("safetyCarStatus").asInt() > 0;

            JsonNode carsNode = state.get("cars");
            if (carsNode == null || !carsNode.isArray()) return null;

            // Find the current lap (leader's lap)
            int currentLap = 1;
            int currentSector = 0;
            for (JsonNode c : carsNode) {
                if (c.has("pos") && c.get("pos").asInt() == 1) {
                    currentLap = c.has("lap") ? c.get("lap").asInt() : 1;
                    currentSector = c.has("sector") ? c.get("sector").asInt() : 0;
                    break;
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
                // Estimate fuel burn per sector from total fuel and remaining laps
                int lapsRemaining = Math.max(totalLaps - currentLap, 1);
                double fuelBurnPerSector = fuel / (lapsRemaining * 3.0);
                int fwDmg = c.has("fwDmg") ? c.get("fwDmg").asInt() : 0;
                int flDmg = c.has("flDmg") ? c.get("flDmg").asInt() : 0;
                int engDmg = c.has("engDmg") ? c.get("engDmg").asInt() : 0;
                int pits = c.has("pits") ? c.get("pits").asInt() : 0;

                // Skip retired cars (resultStatus 3=finished, 4=DNF, 5=DSQ, 6=DNS, 7=withdrawn)
                int resultStatus = c.has("resultStatus") ? c.get("resultStatus").asInt() : 2;
                if (resultStatus >= 4) continue;

                // Compute cumulative time estimate from position (simplified: leader=0, +1s per position)
                double totalTimeMs = (pos - 1) * 1000.0;

                cars.add(new RaceSnapshot.CarSnapshot(
                        idx, name, ai, pos, tyreCompound, tyreAge,
                        fuel, fuelBurnPerSector, fwDmg, flDmg, engDmg,
                        pits, totalTimeMs));
            }

            if (cars.isEmpty()) return null;

            return new RaceSnapshot(trackId, totalLaps, currentLap, currentSector,
                    weather, trackTemp, airTemp, safetyCar, cars, null);
        } catch (Exception e) {
            System.err.println("SimulationOrchestrator: snapshot assembly failed: " + e.getMessage());
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

    // ── job record ────────────────────────────────────────────────────

    public record SimulationJob(String jobId, long startedAt, SimulationResult result) {}
}
