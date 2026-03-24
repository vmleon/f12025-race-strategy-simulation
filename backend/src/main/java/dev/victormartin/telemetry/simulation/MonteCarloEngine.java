package dev.victormartin.telemetry.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Monte Carlo race strategy simulation engine.
 * Runs N iterations of the remaining race at per-sector granularity,
 * producing position probability distributions for each car.
 */
public class MonteCarloEngine {

    static final int DEFAULT_ITERATIONS = 1_000;
    static final int MAX_ITERATIONS = 10_000;
    static final double CONVERGENCE_THRESHOLD = 0.1; // positions
    static final int CONVERGENCE_CHECK_INTERVAL = 200;
    static final int SECTORS_PER_LAP = 3;

    // Pit stop time penalty in ms (pit lane transit + tyre change)
    static final double PIT_STOP_TIME_MS = 22_000;

    // DNF: base mechanical failure rate per sector (Poisson lambda)
    static final double BASE_DNF_RATE_PER_SECTOR = 0.0001;

    private final Coefficients coefficients;
    private final Random random;

    public MonteCarloEngine(Coefficients coefficients) {
        this(coefficients, new Random());
    }

    public MonteCarloEngine(Coefficients coefficients, Random random) {
        this.coefficients = coefficients;
        this.random = random;
    }

    public SimulationResult simulate(RaceSnapshot snapshot) {
        return simulate(snapshot, DEFAULT_ITERATIONS);
    }

    public SimulationResult simulate(RaceSnapshot snapshot, int maxIterations) {
        int numCars = snapshot.cars().size();
        long startTime = System.currentTimeMillis();

        // position counts: [carIdx][position] where position is 0-based
        int[][] positionCounts = new int[numCars][numCars];
        int[] dnfCounts = new int[numCars];
        double[] meanPositions = new double[numCars];

        double[] prevMeans = null;
        boolean converged = false;
        int iterations = 0;

        for (int iter = 0; iter < maxIterations; iter++) {
            iterations++;
            List<CarState> cars = initCars(snapshot);
            runIteration(cars, snapshot);
            recordResults(cars, positionCounts, dnfCounts, iterations);

            // Convergence check
            if (iterations % CONVERGENCE_CHECK_INTERVAL == 0 && iterations >= CONVERGENCE_CHECK_INTERVAL * 2) {
                computeMeans(positionCounts, iterations, meanPositions);
                if (prevMeans != null && isConverged(meanPositions, prevMeans)) {
                    converged = true;
                    break;
                }
                prevMeans = Arrays.copyOf(meanPositions, meanPositions.length);
            }
        }

        long wallClock = System.currentTimeMillis() - startTime;
        computeMeans(positionCounts, iterations, meanPositions);
        List<SimulationResult.CarResult> results = buildResults(snapshot, positionCounts, dnfCounts, iterations);
        return new SimulationResult(iterations, converged, wallClock, results);
    }

    // ── iteration ─────────────────────────────────────────────────────────

    private List<CarState> initCars(RaceSnapshot snapshot) {
        List<CarState> cars = new ArrayList<>(snapshot.cars().size());
        for (var cs : snapshot.cars()) {
            cars.add(new CarState(
                    cs.carIndex(), cs.driverName(), cs.aiControlled(),
                    cs.position(), cs.tyreCompound(), cs.tyreAgeLaps(),
                    cs.fuelKg(), cs.fuelBurnPerSectorKg(),
                    cs.frontWingDamage(), cs.floorDamage(), cs.engineDamage(),
                    cs.numPitStops(), cs.totalTimeMs(), snapshot.currentLap()));
        }
        return cars;
    }

    void runIteration(List<CarState> cars, RaceSnapshot snapshot) {
        boolean safetyCar = snapshot.safetyCar();
        int startSector = snapshot.currentSector();

        for (int lap = snapshot.currentLap(); lap <= snapshot.totalLaps(); lap++) {
            int sectorStart = (lap == snapshot.currentLap()) ? startSector : 0;

            for (int sector = sectorStart; sector < SECTORS_PER_LAP; sector++) {
                // Safety car / red flag events (stochastic per lap, checked once per sector 0)
                if (sector == 0) {
                    safetyCar = checkSafetyCarEvent(safetyCar, cars, snapshot);
                    if (checkRedFlag(snapshot)) {
                        handleRedFlag(cars);
                    }
                }

                // Process each car in position order
                cars.sort(Comparator.comparingInt(c -> c.position));

                for (CarState car : cars) {
                    if (car.retired) continue;

                    // Check pit stop (strategy-driven or AI heuristic)
                    if (sector == 0) {
                        checkPitStop(car, lap, snapshot);
                    }

                    // Predict sector time
                    double sectorTime = predictSectorTime(car, sector, safetyCar, cars, snapshot);
                    car.totalTimeMs += sectorTime;

                    // Update car state
                    consumeFuel(car);
                    ageTyres(car, sector);

                    // DNF check
                    if (checkDnf(car)) {
                        car.retired = true;
                        car.position = cars.size(); // last position
                    }
                }

                // Resolve overtakes after all cars have run the sector
                resolveOvertakes(cars, sector, snapshot);
            }

            // Increment lap counter
            for (CarState car : cars) {
                if (!car.retired) car.currentLap = lap + 1;
            }
        }

        // Final position assignment by total time
        assignFinalPositions(cars);
    }

    // ── sector time prediction (additive model) ───────────────────────────

    double predictSectorTime(CarState car, int sector, boolean safetyCar,
                             List<CarState> cars, RaceSnapshot snapshot) {
        String regime = car.regime();

        // Base pace
        double basePace = coefficients.get("base_pace_mean", regime, sector);
        if (basePace <= 0) {
            // Fallback: use a reasonable default (~30s per sector)
            basePace = 30_000;
        }

        // Base pace variance -> residual noise
        double variance = coefficients.get("base_pace_variance", regime, sector);
        double noise = variance > 0 ? random.nextGaussian() * Math.sqrt(variance) : random.nextGaussian() * 200;

        // Tyre degradation
        double tyreDeg = tyreDegradation(car, regime);

        // Fuel effect
        double fuelEffect = coefficients.get("fuel_effect", regime, sector) * car.fuelKg;

        // Damage effects
        double damageEffect = damageEffect(car, regime, sector);

        // Dirty air effect
        double dirtyAir = dirtyAirEffect(car, cars, regime, sector);

        // DRS effect
        double drs = drsEffect(car, cars, sector, regime);

        // Safety car compression
        if (safetyCar) {
            // Under safety car, everyone laps at ~40% slower pace, gaps compress
            return basePace * 1.4;
        }

        double sectorTime = basePace + tyreDeg + fuelEffect + damageEffect + dirtyAir + drs + noise;
        return Math.max(sectorTime, basePace * 0.9); // floor: can't be faster than 90% of base
    }

    private double tyreDegradation(CarState car, String regime) {
        String knob = switch (car.tyreCompound) {
            case 16 -> "tyre_deg_soft";
            case 17 -> "tyre_deg_medium";
            case 18 -> "tyre_deg_hard";
            default -> "tyre_deg_medium";
        };
        double degPerLap = coefficients.get(knob, regime);
        // Convert from ms/lap to ms for current tyre age
        return degPerLap * car.tyreAgeLaps;
    }

    private double damageEffect(CarState car, String regime, int sector) {
        double frontWing = coefficients.get("front_wing_damage", regime, sector) * car.frontWingDamage;
        double floor = coefficients.get("floor_damage", regime, sector) * car.floorDamage;
        double engine = coefficients.get("engine_damage", regime, sector) * car.engineDamage;
        return frontWing + floor + engine;
    }

    private double dirtyAirEffect(CarState car, List<CarState> cars, String regime, int sector) {
        // Find gap to car ahead
        CarState carAhead = null;
        for (CarState other : cars) {
            if (other.retired) continue;
            if (other.position == car.position - 1) {
                carAhead = other;
                break;
            }
        }
        if (carAhead == null) return 0; // leader has clean air

        double gapMs = car.totalTimeMs - carAhead.totalTimeMs;
        if (gapMs > 2000) return 0; // clean air beyond 2s

        // Dirty air effect scales inversely with gap (max effect at 0 gap)
        double dirtyAirCoeff = coefficients.get("dirty_air", regime, sector);
        double scale = 1.0 - (gapMs / 2000.0);
        return dirtyAirCoeff * Math.max(0, scale);
    }

    private double drsEffect(CarState car, List<CarState> cars, int sector, String regime) {
        // DRS: within 1s of car ahead
        CarState carAhead = null;
        for (CarState other : cars) {
            if (other.retired) continue;
            if (other.position == car.position - 1) {
                carAhead = other;
                break;
            }
        }
        if (carAhead == null) return 0;

        double gapMs = car.totalTimeMs - carAhead.totalTimeMs;
        if (gapMs > 1000) return 0; // no DRS beyond 1s

        return coefficients.get("drs_advantage", regime, sector); // negative = time gain
    }

    // ── sub-models ────────────────────────────────────────────────────────

    private void consumeFuel(CarState car) {
        car.fuelKg = Math.max(0, car.fuelKg - car.fuelBurnPerSectorKg);
    }

    private void ageTyres(CarState car, int sector) {
        // Increment tyre age by 1/3 of a lap per sector (1 full lap = 3 sectors)
        if (sector == 2) {
            car.tyreAgeLaps++;
        }
    }

    // ── pit stop ──────────────────────────────────────────────────────────

    private void checkPitStop(CarState car, int lap, RaceSnapshot snapshot) {
        // Check if player strategy prescribes a stop on this lap
        if (snapshot.pitStrategy() != null && car.carIndex == snapshot.pitStrategy().targetCarIndex()) {
            for (var stop : snapshot.pitStrategy().stops()) {
                if (stop.onLap() == lap) {
                    executePitStop(car, stop.newCompound());
                    return;
                }
            }
        }

        // AI heuristic: pit when tyres are very old
        if (car.aiControlled) {
            int threshold = switch (car.tyreCompound) {
                case 16 -> 15 + random.nextInt(6);  // soft: 15-20 laps
                case 17 -> 25 + random.nextInt(6);  // medium: 25-30 laps
                case 18 -> 35 + random.nextInt(6);  // hard: 35-40 laps
                default -> 25;
            };
            if (car.tyreAgeLaps >= threshold) {
                // Switch to a different compound
                int newCompound = car.tyreCompound == 16 ? 18 : 17;
                executePitStop(car, newCompound);
            }
        }
    }

    private void executePitStop(CarState car, int newCompound) {
        car.totalTimeMs += PIT_STOP_TIME_MS;
        car.tyreCompound = newCompound;
        car.tyreAgeLaps = 0;
        car.numPitStops++;
    }

    // ── overtake resolution ───────────────────────────────────────────────

    void resolveOvertakes(List<CarState> cars, int sector, RaceSnapshot snapshot) {
        // Sort by total time to detect position inversions
        List<CarState> active = cars.stream()
                .filter(c -> !c.retired)
                .sorted(Comparator.comparingDouble(c -> c.totalTimeMs))
                .toList();

        for (int i = 0; i < active.size(); i++) {
            CarState car = active.get(i);
            int expectedPos = i + 1;
            if (car.position != expectedPos) {
                // Car has gained time — check overtake probability
                CarState defender = findCarAtPosition(cars, expectedPos);
                if (defender != null && !defender.retired) {
                    double paceDeltaMs = defender.totalTimeMs - car.totalTimeMs;
                    double overtakeProb = overtakeProbability(car, paceDeltaMs, sector);
                    if (random.nextDouble() < overtakeProb) {
                        // Swap positions
                        int oldPos = car.position;
                        car.position = defender.position;
                        defender.position = oldPos;
                    }
                }
            }
        }
    }

    private double overtakeProbability(CarState car, double paceDeltaMs, int sector) {
        double baseProb = coefficients.get("overtake_probability", car.regime(), sector);
        // Logistic scaling: higher pace delta = higher overtake probability
        // paceDeltaMs > 0 means attacker is faster
        double x = paceDeltaMs / 500.0; // normalize: 500ms delta -> x=1
        return baseProb * sigmoid(x);
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private CarState findCarAtPosition(List<CarState> cars, int position) {
        for (CarState car : cars) {
            if (car.position == position && !car.retired) return car;
        }
        return null;
    }

    // ── safety car / red flag ─────────────────────────────────────────────

    boolean checkSafetyCarEvent(boolean currentSafetyCar, List<CarState> cars, RaceSnapshot snapshot) {
        double scRate = coefficients.get("safety_car_rate", "AI"); // track-wide
        if (!currentSafetyCar) {
            // Chance of safety car deployment
            if (random.nextDouble() < scRate) {
                compressField(cars);
                return true;
            }
            return false;
        } else {
            // Safety car lasts 3-5 laps on average; 30% chance of ending each lap
            return random.nextDouble() > 0.30;
        }
    }

    private void compressField(List<CarState> cars) {
        // Compress gaps: set all cars to within 1s of leader
        List<CarState> active = cars.stream()
                .filter(c -> !c.retired)
                .sorted(Comparator.comparingInt(c -> c.position))
                .toList();
        if (active.isEmpty()) return;
        double leaderTime = active.getFirst().totalTimeMs;
        for (int i = 1; i < active.size(); i++) {
            active.get(i).totalTimeMs = leaderTime + (i * 500.0); // 0.5s gaps
        }
    }

    boolean checkRedFlag(RaceSnapshot snapshot) {
        // Very rare: ~0.1% per lap
        return random.nextDouble() < 0.001;
    }

    void handleRedFlag(List<CarState> cars) {
        // Red flag: free tyre change for everyone, compressed field
        for (CarState car : cars) {
            if (!car.retired) {
                car.tyreAgeLaps = 0; // free tyre change
            }
        }
        compressField(cars);
    }

    // ── DNF ───────────────────────────────────────────────────────────────

    boolean checkDnf(CarState car) {
        // Base mechanical failure rate, increased by damage
        double damageMultiplier = 1.0
                + car.engineDamage * 0.05   // engine damage is most dangerous
                + car.floorDamage * 0.01
                + car.frontWingDamage * 0.005;
        double dnfRate = BASE_DNF_RATE_PER_SECTOR * damageMultiplier;
        return random.nextDouble() < dnfRate;
    }

    // ── final positions ───────────────────────────────────────────────────

    private void assignFinalPositions(List<CarState> cars) {
        // Retired cars go to the back, sorted by laps completed (desc)
        List<CarState> active = new ArrayList<>();
        List<CarState> retired = new ArrayList<>();
        for (CarState car : cars) {
            if (car.retired) retired.add(car);
            else active.add(car);
        }

        active.sort(Comparator.comparingDouble(c -> c.totalTimeMs));
        retired.sort(Comparator.comparingInt((CarState c) -> c.currentLap).reversed());

        int pos = 1;
        for (CarState car : active) car.position = pos++;
        for (CarState car : retired) car.position = pos++;
    }

    // ── result collection ─────────────────────────────────────────────────

    private void recordResults(List<CarState> cars, int[][] positionCounts, int[] dnfCounts, int iterNum) {
        for (int i = 0; i < cars.size(); i++) {
            CarState car = cars.get(i);
            int pos = car.position - 1; // 0-based
            if (pos >= 0 && pos < positionCounts[i].length) {
                positionCounts[i][pos]++;
            }
            if (car.retired) {
                dnfCounts[i]++;
            }
        }
    }

    private void computeMeans(int[][] positionCounts, int iterations, double[] means) {
        for (int i = 0; i < positionCounts.length; i++) {
            double sum = 0;
            for (int p = 0; p < positionCounts[i].length; p++) {
                sum += (p + 1) * positionCounts[i][p];
            }
            means[i] = sum / iterations;
        }
    }

    private boolean isConverged(double[] current, double[] previous) {
        for (int i = 0; i < current.length; i++) {
            if (Math.abs(current[i] - previous[i]) > CONVERGENCE_THRESHOLD) {
                return false;
            }
        }
        return true;
    }

    private List<SimulationResult.CarResult> buildResults(RaceSnapshot snapshot,
                                                          int[][] positionCounts, int[] dnfCounts, int iterations) {
        List<SimulationResult.CarResult> results = new ArrayList<>();
        List<RaceSnapshot.CarSnapshot> snapshotCars = snapshot.cars();

        for (int i = 0; i < snapshotCars.size(); i++) {
            var cs = snapshotCars.get(i);
            int[] counts = positionCounts[i];

            // Mean and standard deviation
            double mean = 0;
            for (int p = 0; p < counts.length; p++) mean += (p + 1) * counts[p];
            mean /= iterations;

            double variance = 0;
            for (int p = 0; p < counts.length; p++) {
                double diff = (p + 1) - mean;
                variance += diff * diff * counts[p];
            }
            variance /= iterations;
            double stdDev = Math.sqrt(variance);

            // 95% CI (2.5th and 97.5th percentiles)
            double ci95Low = percentile(counts, iterations, 0.025);
            double ci95High = percentile(counts, iterations, 0.975);

            // DNF probability
            double dnfProb = (double) dnfCounts[i] / iterations;

            // Top 3 and points finish probabilities
            double top3 = 0;
            double pointsFinish = 0;
            for (int p = 0; p < counts.length; p++) {
                double prob = (double) counts[p] / iterations;
                if (p < 3) top3 += prob;
                if (p < 10) pointsFinish += prob;
            }

            // Position distribution
            Map<Integer, Double> distribution = new HashMap<>();
            for (int p = 0; p < counts.length; p++) {
                if (counts[p] > 0) {
                    distribution.put(p + 1, (double) counts[p] / iterations);
                }
            }

            results.add(new SimulationResult.CarResult(
                    cs.carIndex(), cs.driverName(),
                    mean, stdDev, ci95Low, ci95High,
                    dnfProb, top3, pointsFinish, distribution));
        }

        return results;
    }

    private double percentile(int[] counts, int iterations, double p) {
        int target = (int) Math.ceil(p * iterations);
        int cumulative = 0;
        for (int pos = 0; pos < counts.length; pos++) {
            cumulative += counts[pos];
            if (cumulative >= target) return pos + 1;
        }
        return counts.length;
    }
}
