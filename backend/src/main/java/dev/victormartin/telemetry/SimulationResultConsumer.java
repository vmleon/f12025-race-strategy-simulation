package dev.victormartin.telemetry;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.simulation.SimulationResult;

@Component
public class SimulationResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(SimulationResultConsumer.class);

    private final QueueService queueService;
    private final SimulationOrchestrator orchestrator;
    private final RaceWebSocketHandler raceWebSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SimulationResultConsumer(QueueService queueService,
                                    SimulationOrchestrator orchestrator,
                                    RaceWebSocketHandler raceWebSocketHandler) {
        this.queueService = queueService;
        this.orchestrator = orchestrator;
        this.raceWebSocketHandler = raceWebSocketHandler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread thread = new Thread(this::consumeLoop, "simulation-result-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            String mdcSet = null;
            try {
                String json = queueService.dequeue("PDBADMIN.SIMULATION_RESULT", 5);
                if (json == null) continue;

                JsonNode node = objectMapper.readTree(json);

                if (node.has("sessionUid")) {
                    mdcSet = node.get("sessionUid").asText();
                    MDC.put("sessionUid", mdcSet);
                }

                log.info("Dequeued SIMULATION_RESULT payloadBytes={}", json.length());

                String jobId = node.get("jobId").asText();
                SimulationResult result = objectMapper.treeToValue(node.get("result"), SimulationResult.class);

                log.info("SimulationResultConsumer: received result for job {}", jobId);
                orchestrator.completeJob(jobId, result);

                String resultJson = objectMapper.writeValueAsString(Map.of(
                        "type", "simulationResult",
                        "jobId", jobId,
                        "result", result));
                raceWebSocketHandler.broadcast(resultJson);
            } catch (Exception e) {
                log.error("SimulationResultConsumer: error: {}", e.getMessage(), e);
            } finally {
                if (mdcSet != null) {
                    MDC.remove("sessionUid");
                }
            }
        }
    }
}
