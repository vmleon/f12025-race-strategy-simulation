package dev.victormartin.telemetry;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.victormartin.telemetry.simulation.CoefficientRepository;
import dev.victormartin.telemetry.simulation.MonteCarloEngine;
import dev.victormartin.telemetry.simulation.RaceSnapshot;
import dev.victormartin.telemetry.simulation.SimulationResult;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final CoefficientRepository coefficientRepository;
    private final SimulationOrchestrator orchestrator;

    public SimulationController(CoefficientRepository coefficientRepository,
                                SimulationOrchestrator orchestrator) {
        this.coefficientRepository = coefficientRepository;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/run")
    public SimulationResult run(@RequestBody RaceSnapshot snapshot) {
        var coefficients = coefficientRepository.loadForTrack(snapshot.trackId());
        var engine = new MonteCarloEngine(coefficients);
        return engine.simulate(snapshot);
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
