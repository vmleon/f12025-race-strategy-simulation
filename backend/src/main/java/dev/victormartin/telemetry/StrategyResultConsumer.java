package dev.victormartin.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.victormartin.telemetry.simulation.StrategyEvaluation;

@Component
public class StrategyResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(StrategyResultConsumer.class);

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
            String mdcSet = null;
            try {
                String json = queueService.dequeue("PDBADMIN.STRATEGY_RESULT", 5);
                if (json == null) continue;

                JsonNode node = objectMapper.readTree(json);

                if (node.has("sessionUid")) {
                    mdcSet = node.get("sessionUid").asText();
                    MDC.put("sessionUid", mdcSet);
                }

                log.info("Dequeued STRATEGY_RESULT payloadBytes={}", json.length());

                String jobId = node.get("jobId").asText();
                int evaluatedAtLap = node.has("evaluatedAtLap") ? node.get("evaluatedAtLap").asInt() : 0;
                StrategyEvaluation evaluation = objectMapper.treeToValue(
                        node.get("result"), StrategyEvaluation.class);

                log.info("StrategyResultConsumer: received result for job {} (lap {}, {} strategies)",
                        jobId, evaluatedAtLap, evaluation.strategies().size());
                strategyOrchestrator.completeJob(jobId, evaluatedAtLap, evaluation);
            } catch (Exception e) {
                log.error("StrategyResultConsumer: error: {}", e.getMessage(), e);
            } finally {
                if (mdcSet != null) {
                    MDC.remove("sessionUid");
                }
            }
        }
    }
}
