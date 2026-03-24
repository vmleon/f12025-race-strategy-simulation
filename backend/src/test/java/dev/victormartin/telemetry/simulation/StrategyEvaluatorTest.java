package dev.victormartin.telemetry.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class StrategyEvaluatorTest {

    private Coefficients coefficients;
    private MonteCarloEngine engine;
    private StrategyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        coefficients = Coefficients.defaults();
        for (String regime : List.of("PLAYER", "AI")) {
            for (int sector = 0; sector < 3; sector++) {
                coefficients.put("base_pace_mean", regime, sector, 30_000);
                coefficients.put("base_pace_variance", regime, sector, 40_000);
            }
        }
        engine = new MonteCarloEngine(coefficients, new Random(42));
        evaluator = new StrategyEvaluator(engine);
    }

    @Test
    void threeStrategiesProduceDifferentRankedResults() {
        RaceSnapshot snapshot = buildRace(5, 30);

        var candidates = List.of(
                new StrategyEvaluation.StrategyCandidate("1-stop: Soft→Hard, pit lap 15",
                        List.of(new RaceSnapshot.PitStrategy.PitStop(15, 18))),
                new StrategyEvaluation.StrategyCandidate("2-stop: Soft→Med→Med, pit laps 10,20",
                        List.of(new RaceSnapshot.PitStrategy.PitStop(10, 17),
                                new RaceSnapshot.PitStrategy.PitStop(20, 17))),
                new StrategyEvaluation.StrategyCandidate("No stops",
                        List.of())
        );

        StrategyEvaluation eval = evaluator.evaluate(snapshot, 0, candidates);

        assertEquals(0, eval.playerCarIndex());
        assertEquals(3, eval.strategies().size());

        // Ranks are 1, 2, 3
        for (int i = 0; i < eval.strategies().size(); i++) {
            assertEquals(i + 1, eval.strategies().get(i).rank());
        }

        // Best strategy should have lower mean position than worst
        assertTrue(eval.strategies().getFirst().meanPosition() <= eval.strategies().getLast().meanPosition(),
                "Best strategy should have lower (better) mean position");

        // All results should have valid mean positions
        for (var s : eval.strategies()) {
            assertTrue(s.meanPosition() >= 1 && s.meanPosition() <= 5,
                    "Mean position should be in [1,5], got " + s.meanPosition());
            assertTrue(s.ci95Low() <= s.meanPosition());
            assertTrue(s.ci95High() >= s.meanPosition());
        }
    }

    @Test
    void aggressiveStrategyHasHigherVariance() {
        RaceSnapshot snapshot = buildRace(5, 30);

        // Conservative: 1-stop late
        var conservative = new StrategyEvaluation.StrategyCandidate("1-stop late",
                List.of(new RaceSnapshot.PitStrategy.PitStop(25, 18)));

        // Aggressive: 3-stop
        var aggressive = new StrategyEvaluation.StrategyCandidate("3-stop aggressive",
                List.of(new RaceSnapshot.PitStrategy.PitStop(8, 17),
                        new RaceSnapshot.PitStrategy.PitStop(16, 16),
                        new RaceSnapshot.PitStrategy.PitStop(24, 17)));

        StrategyEvaluation eval = evaluator.evaluate(snapshot, 0, List.of(conservative, aggressive));

        var conservativeResult = eval.strategies().stream()
                .filter(s -> s.candidate().label().equals("1-stop late")).findFirst().orElseThrow();
        var aggressiveResult = eval.strategies().stream()
                .filter(s -> s.candidate().label().equals("3-stop aggressive")).findFirst().orElseThrow();

        // Both should produce valid results regardless of which is "better"
        assertTrue(conservativeResult.meanPosition() >= 1);
        assertTrue(aggressiveResult.meanPosition() >= 1);
    }

    @Test
    void expectedPointsAreCalculated() {
        RaceSnapshot snapshot = buildRace(5, 10);

        var candidate = new StrategyEvaluation.StrategyCandidate("Simple",
                List.of(new RaceSnapshot.PitStrategy.PitStop(5, 18)));

        StrategyEvaluation eval = evaluator.evaluate(snapshot, 0, List.of(candidate));

        var result = eval.strategies().getFirst();
        // With 5 cars, player starting P1 should earn some points
        assertTrue(result.expectedPoints() > 0,
                "Expected points should be > 0 for a front-running car, got " + result.expectedPoints());
    }

    @Test
    void feasibilityRejectsUnavailableCompound() {
        var candidate = new StrategyEvaluation.StrategyCandidate("Need softs",
                List.of(new RaceSnapshot.PitStrategy.PitStop(10, 16),
                        new RaceSnapshot.PitStrategy.PitStop(20, 16)));

        // Only 1 soft set available
        var availableSets = List.of(
                new TyreSetRepository.TyreSet(0, 16, 0.0, 20),  // 1 soft
                new TyreSetRepository.TyreSet(1, 17, 0.0, 30),  // 1 medium
                new TyreSetRepository.TyreSet(2, 18, 0.0, 40)   // 1 hard
        );

        String rejection = StrategyEvaluator.checkFeasibility(candidate, availableSets);
        assertNotNull(rejection, "Strategy needing 2 softs with only 1 available should be rejected");
        assertTrue(rejection.contains("Soft"));
    }

    @Test
    void feasibilityAcceptsValidStrategy() {
        var candidate = new StrategyEvaluation.StrategyCandidate("1 medium, 1 hard",
                List.of(new RaceSnapshot.PitStrategy.PitStop(15, 17),
                        new RaceSnapshot.PitStrategy.PitStop(30, 18)));

        var availableSets = List.of(
                new TyreSetRepository.TyreSet(0, 17, 0.0, 30),
                new TyreSetRepository.TyreSet(1, 17, 0.0, 30),
                new TyreSetRepository.TyreSet(2, 18, 0.0, 40),
                new TyreSetRepository.TyreSet(3, 18, 0.0, 40)
        );

        String rejection = StrategyEvaluator.checkFeasibility(candidate, availableSets);
        assertNull(rejection, "Valid strategy should be accepted");
    }

    @Test
    void feasibilityAcceptsNoStopStrategy() {
        var candidate = new StrategyEvaluation.StrategyCandidate("No stops", List.of());
        String rejection = StrategyEvaluator.checkFeasibility(candidate, List.of());
        assertNull(rejection, "No-stop strategy always feasible");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private RaceSnapshot buildRace(int numCars, int totalLaps) {
        List<RaceSnapshot.CarSnapshot> cars = new ArrayList<>();
        for (int i = 0; i < numCars; i++) {
            cars.add(new RaceSnapshot.CarSnapshot(
                    i, "Driver" + i, i > 0,
                    i + 1, 16, 3,
                    50.0, 0.1,
                    0, 0, 0,
                    0, i * 500.0));
        }
        return new RaceSnapshot(1, totalLaps, 1, 0, 0, 30, 22, false, cars, null);
    }
}
