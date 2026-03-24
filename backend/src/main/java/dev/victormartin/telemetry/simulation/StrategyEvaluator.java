package dev.victormartin.telemetry.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Evaluates multiple candidate pit strategies by running a Monte Carlo simulation
 * for each one and ranking by expected finishing position.
 */
public class StrategyEvaluator {

    private final MonteCarloEngine engine;

    public StrategyEvaluator(MonteCarloEngine engine) {
        this.engine = engine;
    }

    /**
     * Evaluates all candidate strategies against the given race snapshot.
     * Each candidate's pit stops are applied to the player car (identified by
     * playerCarIndex), the simulation is run, and the player's result is collected.
     *
     * @param baseSnapshot  current race state (pitStrategy field is ignored)
     * @param playerCarIndex which car to evaluate strategies for
     * @param candidates    list of strategies to compare
     * @return ranked evaluation, best strategy first
     */
    public StrategyEvaluation evaluate(RaceSnapshot baseSnapshot, int playerCarIndex,
                                       List<StrategyEvaluation.StrategyCandidate> candidates) {
        List<StrategyEvaluation.RankedStrategy> results = new ArrayList<>();

        for (var candidate : candidates) {
            var pitStrategy = new RaceSnapshot.PitStrategy(playerCarIndex, candidate.stops());
            var snapshot = new RaceSnapshot(
                    baseSnapshot.trackId(), baseSnapshot.totalLaps(),
                    baseSnapshot.currentLap(), baseSnapshot.currentSector(),
                    baseSnapshot.weather(), baseSnapshot.trackTemp(), baseSnapshot.airTemp(),
                    baseSnapshot.safetyCar(), baseSnapshot.cars(), pitStrategy);

            SimulationResult simResult = engine.simulate(snapshot);
            SimulationResult.CarResult playerResult = findPlayerResult(simResult, playerCarIndex);

            results.add(new StrategyEvaluation.RankedStrategy(
                    0, // rank assigned below
                    candidate,
                    playerResult.meanPosition(),
                    playerResult.positionStdDev(),
                    playerResult.ci95Low(),
                    playerResult.ci95High(),
                    playerResult.dnfProbability(),
                    playerResult.top3Probability(),
                    playerResult.pointsFinishProbability(),
                    StrategyEvaluation.expectedPoints(playerResult)));
        }

        // Rank by mean position ascending (lower = better)
        results.sort(Comparator.comparingDouble(StrategyEvaluation.RankedStrategy::meanPosition));
        List<StrategyEvaluation.RankedStrategy> ranked = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            ranked.add(new StrategyEvaluation.RankedStrategy(
                    i + 1, r.candidate(), r.meanPosition(), r.positionStdDev(),
                    r.ci95Low(), r.ci95High(), r.dnfProbability(),
                    r.top3Probability(), r.pointsFinishProbability(), r.expectedPoints()));
        }

        return new StrategyEvaluation(playerCarIndex, ranked);
    }

    /**
     * Checks whether a candidate strategy is feasible given available tyre sets.
     * A strategy is infeasible if it requires a compound that has no available sets.
     *
     * @param candidate the strategy to check
     * @param availableSets tyre sets from TyreSetRepository
     * @return null if feasible, or a rejection reason string if not
     */
    public static String checkFeasibility(StrategyEvaluation.StrategyCandidate candidate,
                                          List<TyreSetRepository.TyreSet> availableSets) {
        // Collect available compound counts
        Map<Integer, Long> compoundCounts = availableSets.stream()
                .collect(Collectors.groupingBy(s -> s.compound(), Collectors.counting()));

        // Track how many sets of each compound the strategy needs
        Map<Integer, Long> neededByCompound = candidate.stops().stream()
                .collect(Collectors.groupingBy(s -> s.newCompound(), Collectors.counting()));

        for (var entry : neededByCompound.entrySet()) {
            int compound = entry.getKey();
            long needed = entry.getValue();
            long available = compoundCounts.getOrDefault(compound, 0L);
            if (available < needed) {
                String name = compoundName(compound);
                return "Need " + needed + " " + name + " set(s) but only " + available + " available";
            }
        }

        return null; // feasible
    }

    private SimulationResult.CarResult findPlayerResult(SimulationResult result, int playerCarIndex) {
        for (var car : result.cars()) {
            if (car.carIndex() == playerCarIndex) return car;
        }
        throw new IllegalStateException("Player car " + playerCarIndex + " not found in simulation results");
    }

    static String compoundName(int compound) {
        return switch (compound) {
            case 16 -> "Soft";
            case 17 -> "Medium";
            case 18 -> "Hard";
            default -> "Compound-" + compound;
        };
    }
}
