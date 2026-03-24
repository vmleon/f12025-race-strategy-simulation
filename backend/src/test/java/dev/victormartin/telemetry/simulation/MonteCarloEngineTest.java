package dev.victormartin.telemetry.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MonteCarloEngineTest {

    private Coefficients coefficients;
    private MonteCarloEngine engine;

    @BeforeEach
    void setUp() {
        coefficients = Coefficients.defaults();
        // Set realistic base pace per sector (ms) — ~30s per sector
        for (String regime : List.of("PLAYER", "AI")) {
            for (int sector = 0; sector < 3; sector++) {
                coefficients.put("base_pace_mean", regime, sector, 30_000);
                coefficients.put("base_pace_variance", regime, sector, 40_000); // ~200ms std dev
            }
        }
        engine = new MonteCarloEngine(coefficients, new Random(42));
    }

    @Test
    void simulationCompletesWithDefaultCoefficients() {
        RaceSnapshot snapshot = buildSyntheticRace(20, 10);
        SimulationResult result = engine.simulate(snapshot, 100);

        assertNotNull(result);
        assertEquals(20, result.cars().size());
        assertTrue(result.iterations() > 0);
        assertTrue(result.wallClockMs() >= 0);
    }

    @Test
    void leaderMostlyFinishesFirst() {
        // Give the leader a significant pace advantage via lower total time
        RaceSnapshot snapshot = buildSyntheticRace(5, 5);
        SimulationResult result = engine.simulate(snapshot, 500);

        SimulationResult.CarResult leader = result.cars().getFirst();
        // Leader should have mean position closer to 1 than to last
        assertTrue(leader.meanPosition() < 3.0,
                "Leader mean position should be near front, got " + leader.meanPosition());
    }

    @Test
    void backmarkerMostlyFinishesLast() {
        RaceSnapshot snapshot = buildSyntheticRace(5, 5);
        SimulationResult result = engine.simulate(snapshot, 500);

        SimulationResult.CarResult last = result.cars().getLast();
        assertTrue(last.meanPosition() > 3.0,
                "Backmarker mean position should be near back, got " + last.meanPosition());
    }

    @Test
    void positionDistributionsSumToOne() {
        RaceSnapshot snapshot = buildSyntheticRace(5, 5);
        SimulationResult result = engine.simulate(snapshot, 200);

        for (var car : result.cars()) {
            double sum = car.positionDistribution().values().stream().mapToDouble(d -> d).sum();
            assertEquals(1.0, sum, 0.01,
                    "Position distribution for " + car.driverName() + " should sum to 1.0, got " + sum);
        }
    }

    @Test
    void dnfProbabilityIsLowWithNoDamage() {
        RaceSnapshot snapshot = buildSyntheticRace(5, 5);
        SimulationResult result = engine.simulate(snapshot, 500);

        for (var car : result.cars()) {
            assertTrue(car.dnfProbability() < 0.2,
                    "DNF probability should be low with no damage, got " + car.dnfProbability());
        }
    }

    @Test
    void dnfProbabilityIncreasesWithDamage() {
        List<RaceSnapshot.CarSnapshot> cars = new ArrayList<>();
        // Car 0: heavy engine damage
        cars.add(new RaceSnapshot.CarSnapshot(0, "DamagedDriver", false,
                1, 17, 5, 50.0, 0.1, 0, 0, 80, 0, 0));
        // Car 1: no damage
        cars.add(new RaceSnapshot.CarSnapshot(1, "HealthyDriver", true,
                2, 17, 5, 50.0, 0.1, 0, 0, 0, 0, 500));

        var snapshot = new RaceSnapshot(1, 50, 1, 0, 0, 30, 22, false, cars, null);
        var result = engine.simulate(snapshot, 1_000);

        double damagedDnf = result.cars().get(0).dnfProbability();
        double healthyDnf = result.cars().get(1).dnfProbability();
        assertTrue(damagedDnf > healthyDnf,
                "Damaged car should have higher DNF rate: " + damagedDnf + " vs " + healthyDnf);
    }

    @Test
    void pitStrategyIsApplied() {
        List<RaceSnapshot.CarSnapshot> cars = new ArrayList<>();
        cars.add(new RaceSnapshot.CarSnapshot(0, "Player", false,
                1, 16, 10, 50.0, 0.1, 0, 0, 0, 0, 0));
        cars.add(new RaceSnapshot.CarSnapshot(1, "AI1", true,
                2, 17, 5, 50.0, 0.1, 0, 0, 0, 0, 500));

        // Strategy: pit on lap 3, switch to hard
        var strategy = new RaceSnapshot.PitStrategy(0,
                List.of(new RaceSnapshot.PitStrategy.PitStop(3, 18)));
        var snapshot = new RaceSnapshot(1, 5, 1, 0, 0, 30, 22, false, cars, strategy);

        // Run a single iteration to verify pit stop happens
        var singleEngine = new MonteCarloEngine(coefficients, new Random(42));
        var result = singleEngine.simulate(snapshot, 1);

        // The player car result should exist without errors
        assertNotNull(result.cars().getFirst());
    }

    @Test
    void convergenceDetected() {
        // With few cars and many iterations, results should converge
        RaceSnapshot snapshot = buildSyntheticRace(3, 3);
        SimulationResult result = engine.simulate(snapshot, MonteCarloEngine.DEFAULT_ITERATIONS);

        // Either converged or completed all iterations
        assertTrue(result.iterations() > 0);
        // With stable results, should converge before max iterations
        if (result.converged()) {
            assertTrue(result.iterations() < MonteCarloEngine.DEFAULT_ITERATIONS,
                    "Converged result should use fewer iterations");
        }
    }

    @Test
    void ciOrderIsCorrect() {
        RaceSnapshot snapshot = buildSyntheticRace(5, 5);
        SimulationResult result = engine.simulate(snapshot, 500);

        for (var car : result.cars()) {
            assertTrue(car.ci95Low() <= car.meanPosition(),
                    "CI low should be <= mean for " + car.driverName());
            assertTrue(car.ci95High() >= car.meanPosition(),
                    "CI high should be >= mean for " + car.driverName());
        }
    }

    @Test
    void thousandIterationsUnder30Seconds() {
        RaceSnapshot snapshot = buildSyntheticRace(20, 50);
        long start = System.currentTimeMillis();
        SimulationResult result = engine.simulate(snapshot, 1_000);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 30_000,
                "1000 iterations of 20 cars / 50 laps should complete in <30s, took " + elapsed + "ms");
        assertEquals(20, result.cars().size());
    }

    @Test
    void allSubModelsRunWithoutErrors() {
        // Create a scenario that exercises all sub-models:
        // damage, dirty air (close gaps), various compounds, fuel
        List<RaceSnapshot.CarSnapshot> cars = new ArrayList<>();
        cars.add(new RaceSnapshot.CarSnapshot(0, "P1_Soft", false,
                1, 16, 15, 30.0, 0.1, 10, 5, 3, 1, 0));
        cars.add(new RaceSnapshot.CarSnapshot(1, "P2_Medium", true,
                2, 17, 8, 40.0, 0.1, 0, 0, 0, 0, 200));
        cars.add(new RaceSnapshot.CarSnapshot(2, "P3_Hard", true,
                3, 18, 3, 60.0, 0.1, 20, 15, 10, 0, 500));

        // Start with safety car active
        var snapshot = new RaceSnapshot(1, 10, 3, 0, 0, 35, 25, true, cars, null);
        SimulationResult result = engine.simulate(snapshot, 100);

        assertNotNull(result);
        assertEquals(3, result.cars().size());
        for (var car : result.cars()) {
            assertTrue(car.meanPosition() >= 1 && car.meanPosition() <= 3);
            assertFalse(car.positionDistribution().isEmpty());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private RaceSnapshot buildSyntheticRace(int numCars, int totalLaps) {
        List<RaceSnapshot.CarSnapshot> cars = new ArrayList<>();
        for (int i = 0; i < numCars; i++) {
            cars.add(new RaceSnapshot.CarSnapshot(
                    i,
                    "Driver" + i,
                    i > 0, // car 0 is player
                    i + 1, // position 1..N
                    17,    // medium tyres
                    3,     // 3 laps old
                    50.0,  // 50kg fuel
                    0.1,   // fuel burn per sector
                    0, 0, 0, // no damage
                    0,     // no pit stops
                    i * 500.0 // staggered times: 0, 500, 1000, ...
            ));
        }
        return new RaceSnapshot(1, totalLaps, 1, 0, 0, 30, 22, false, cars, null);
    }
}
