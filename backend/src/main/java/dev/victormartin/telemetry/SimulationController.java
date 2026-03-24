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

    public SimulationController(CoefficientRepository coefficientRepository) {
        this.coefficientRepository = coefficientRepository;
    }

    @PostMapping("/run")
    public SimulationResult run(@RequestBody RaceSnapshot snapshot) {
        var coefficients = coefficientRepository.loadForTrack(snapshot.trackId());
        var engine = new MonteCarloEngine(coefficients);
        return engine.simulate(snapshot);
    }

    @GetMapping("/results/{jobId}")
    public ResponseEntity<Map<String, String>> results(@PathVariable String jobId) {
        // Async job management is implemented in todo 23 (Simulation Trigger Orchestration).
        // This endpoint will return stored results once async jobs are in place.
        return ResponseEntity.status(501)
                .body(Map.of("error", "Async simulation jobs not yet implemented (see todo 23)",
                             "jobId", jobId));
    }
}
