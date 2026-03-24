package dev.victormartin.telemetry;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import dev.victormartin.telemetry.simulation.RaceSnapshot;
import dev.victormartin.telemetry.simulation.SimulationResult;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final RestClient restClient;
    private final SimulationOrchestrator orchestrator;

    public SimulationController(@Value("${simulator.base-url}") String simulatorBaseUrl,
                                SimulationOrchestrator orchestrator) {
        this.restClient = RestClient.builder().baseUrl(simulatorBaseUrl).build();
        this.orchestrator = orchestrator;
    }

    @PostMapping("/run")
    public SimulationResult run(@RequestBody RaceSnapshot snapshot) {
        return restClient.post()
                .uri("/simulate")
                .body(snapshot)
                .retrieve()
                .body(SimulationResult.class);
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
