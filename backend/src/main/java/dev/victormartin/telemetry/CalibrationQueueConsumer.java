package dev.victormartin.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CalibrationQueueConsumer {

    private final QueueService queueService;
    private final CalibrationService calibrationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CalibrationQueueConsumer(QueueService queueService, CalibrationService calibrationService) {
        this.queueService = queueService;
        this.calibrationService = calibrationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread thread = new Thread(this::consumeLoop, "calibration-queue-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String json = queueService.dequeue("PDBADMIN.CALIBRATION_REQUEST", 5);
                if (json == null) continue;

                JsonNode node = objectMapper.readTree(json);
                int trackId = node.get("trackId").asInt();
                System.out.println("CalibrationQueueConsumer: received calibration request for track " + trackId);
                calibrationService.triggerCalibration(trackId);
            } catch (Exception e) {
                System.err.println("CalibrationQueueConsumer: error: " + e.getMessage());
            }
        }
    }
}
