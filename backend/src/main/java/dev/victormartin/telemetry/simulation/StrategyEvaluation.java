package dev.victormartin.telemetry.simulation;

import java.util.List;

/**
 * Output of evaluating multiple candidate strategies via Monte Carlo simulation.
 * Strategies are ranked by expected finishing position (lower = better).
 */
public record StrategyEvaluation(
        int playerCarIndex,
        List<RankedStrategy> strategies,
        boolean insufficientCalibration // player pace uncalibrated (circuit default) → ranks not trustworthy
) {

    public record RankedStrategy(
            int rank,                       // 1-based
            StrategyCandidate candidate,
            double meanPosition,
            double positionStdDev,
            double ci95Low,
            double ci95High,
            double dnfProbability,
            double top3Probability,
            double pointsFinishProbability,
            double expectedPoints           // championship points based on mean position
    ) {}

    /**
     * A candidate strategy: pit stop laps and tyre compounds.
     */
    public record StrategyCandidate(
            String label,                   // e.g. "1-stop: Soft→Hard, pit lap 20"
            List<RaceSnapshot.PitStrategy.PitStop> stops
    ) {}

    // F1 points system (positions 1-10)
    static final double[] POINTS = {25, 18, 15, 12, 10, 8, 6, 4, 2, 1};

    static double expectedPoints(SimulationResult.CarResult carResult) {
        double points = 0;
        for (var entry : carResult.positionDistribution().entrySet()) {
            int pos = entry.getKey();
            double prob = entry.getValue();
            if (pos >= 1 && pos <= POINTS.length) {
                points += POINTS[pos - 1] * prob;
            }
        }
        return points;
    }
}
