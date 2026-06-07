package dev.victormartin.telemetry;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
    private final SimulationIoLog simulationIoLog;
    private final ObjectMapper objectMapper = newResultMapper();

    /**
     * The simulator owns the STRATEGY_RESULT schema and may add fields (e.g.
     * {@code repairFrontWing} on a pit stop) ahead of the backend. Tolerate
     * unknown properties so one new field never drops the whole result.
     */
    static ObjectMapper newResultMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public StrategyResultConsumer(QueueService queueService,
                                  StrategyOrchestrator strategyOrchestrator,
                                  SimulationIoLog simulationIoLog) {
        this.queueService = queueService;
        this.strategyOrchestrator = strategyOrchestrator;
        this.simulationIoLog = simulationIoLog;
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

                Double playerMean = evaluation.strategies().isEmpty()
                        ? null : evaluation.strategies().get(0).meanPosition();
                try {
                    simulationIoLog.recordResult(jobId, node.get("result").toString(), playerMean);
                } catch (Exception ioEx) {
                    log.warn("StrategyResultConsumer: io log recordResult failed: {}", ioEx.getMessage());
                }

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
