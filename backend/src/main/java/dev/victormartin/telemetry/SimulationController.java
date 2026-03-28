package dev.victormartin.telemetry;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.victormartin.telemetry.simulation.RaceSnapshot;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final QueueService queueService;
    private final SimulationOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SimulationController(QueueService queueService,
                                SimulationOrchestrator orchestrator) {
        this.queueService = queueService;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> run(@RequestBody RaceSnapshot snapshot) {
        try {
            String jobId = java.util.UUID.randomUUID().toString().substring(0, 8);
            String payload = objectMapper.writeValueAsString(Map.of(
                    "jobId", jobId,
                    "raceSnapshot", snapshot));
            queueService.enqueue("PDBADMIN.SIMULATION_REQUEST", payload);
            return ResponseEntity.accepted()
                    .body(Map.of("jobId", jobId, "status", "accepted"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/results/{jobId}")
    public ResponseEntity<?> results(@PathVariable String jobId) {
        var job = orchestrator.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (job.result() == null) {
            return ResponseEntity.status(202)
                    .body(Map.of("jobId", jobId, "status", "running"));
        }
        return ResponseEntity.ok(job.result());
    }

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> trigger() {
        String jobId = orchestrator.triggerNow();
        if (jobId == null) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "No live race state available"));
        }
        return ResponseEntity.accepted()
                .body(Map.of("jobId", jobId, "status", "started"));
    }
}
