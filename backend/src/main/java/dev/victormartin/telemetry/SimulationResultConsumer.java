package dev.victormartin.telemetry;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.simulation.SimulationResult;

@Component
public class SimulationResultConsumer {

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
            try {
                String json = queueService.dequeue("PDBADMIN.SIMULATION_RESULT", 5);
                if (json == null) continue;

                JsonNode node = objectMapper.readTree(json);
                String jobId = node.get("jobId").asText();
                SimulationResult result = objectMapper.treeToValue(node.get("result"), SimulationResult.class);

                System.out.println("SimulationResultConsumer: received result for job " + jobId);
                orchestrator.completeJob(jobId, result);

                String resultJson = objectMapper.writeValueAsString(Map.of(
                        "type", "simulationResult",
                        "jobId", jobId,
                        "result", result));
                raceWebSocketHandler.broadcast(resultJson);
            } catch (Exception e) {
                System.err.println("SimulationResultConsumer: error: " + e.getMessage());
            }
        }
    }
}
