package dev.victormartin.telemetry.simulation;

import java.util.List;
import java.util.Map;

/**
 * Output of a Monte Carlo simulation run.
 */
public record SimulationResult(
        int iterations,
        boolean converged,
        long wallClockMs,
        List<CarResult> cars
) {

    public record CarResult(
            int carIndex,
            String driverName,
            double meanPosition,
            double positionStdDev,
            double ci95Low,
            double ci95High,
            double dnfProbability,
            double top3Probability,
            double pointsFinishProbability,
            Map<Integer, Double> positionDistribution  // position -> probability
    ) {}
}
