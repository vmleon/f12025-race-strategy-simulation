package dev.victormartin.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.simulation.StrategyEvaluation;

@Component
public class StrategyResultConsumer {

    private final QueueService queueService;
    private final StrategyOrchestrator strategyOrchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StrategyResultConsumer(QueueService queueService,
                                  StrategyOrchestrator strategyOrchestrator) {
        this.queueService = queueService;
        this.strategyOrchestrator = strategyOrchestrator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread thread = new Thread(this::consumeLoop, "strategy-result-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String json = queueService.dequeue("PDBADMIN.STRATEGY_RESULT", 5);
                if (json == null) continue;

                JsonNode node = objectMapper.readTree(json);
                String jobId = node.get("jobId").asText();
                int evaluatedAtLap = node.has("evaluatedAtLap") ? node.get("evaluatedAtLap").asInt() : 0;
                StrategyEvaluation evaluation = objectMapper.treeToValue(
                        node.get("result"), StrategyEvaluation.class);

                System.out.println("StrategyResultConsumer: received result for job " + jobId
                        + " (lap " + evaluatedAtLap + ", " + evaluation.strategies().size() + " strategies)");
                strategyOrchestrator.completeJob(jobId, evaluatedAtLap, evaluation);
            } catch (Exception e) {
                System.err.println("StrategyResultConsumer: error: " + e.getMessage());
            }
        }
    }
}
