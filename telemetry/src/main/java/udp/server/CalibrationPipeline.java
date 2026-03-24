package udp.server;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Batch calibration pipeline. Reads sector snapshots for a track,
 * fits POC knobs (base pace, tyre degradation, fuel effect), and
 * writes fitted coefficients to the database.
 *
 * Usage: ./gradlew runCalibration --args='<trackId>'
 */
public class CalibrationPipeline {

    static final int MIN_BASE_PACE_SAMPLES = 5;
    static final int MIN_TYRE_DEG_SAMPLES = 10;
    static final int MIN_FUEL_SAMPLES = 5;

    static final int MAX_TYRE_AGE_CLEAN = 5;
    static final long MIN_GAP_CLEAN_AIR_MS = 2000;

    static final Map<Integer, String> COMPOUND_KNOB_NAMES = Map.of(
            16, "tyre_deg_soft",
            17, "tyre_deg_medium",
            18, "tyre_deg_hard"
    );

    record RegressionResult(double slope, double intercept, double rSquared, double slopeStdError, int n) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: CalibrationPipeline <trackId>");
            System.exit(1);
        }
        int trackId = Integer.parseInt(args[0]);

        Properties config = new Properties();
        try (InputStream is = CalibrationPipeline.class.getClassLoader().getResourceAsStream("config.properties")) {
            config.load(is);
        }

        var connFactory = new ConnectionFactory(config);
        var dbWriter = new DbWriter();
        var dbReader = new DbReader();

        try (Connection conn = connFactory.getConnection()) {
            conn.setAutoCommit(false);
            run(conn, dbWriter, dbReader, trackId);
            conn.commit();
        }

        System.out.println("Calibration complete for track " + trackId);
    }

    static void run(Connection conn, DbWriter dbWriter, DbReader dbReader, int trackId) throws Exception {
        // Step 0: ensure cold-start defaults
        var coldStart = new ColdStartDefaults(dbWriter, dbReader);
        int defaultsInserted = coldStart.ensureDefaults(conn, trackId);
        if (defaultsInserted > 0) {
            System.out.println("Inserted " + defaultsInserted + " cold-start defaults");
        }

        // Step 1: recompute outlier flags
        var entries = dbReader.getSectorsForOutlierDetection(conn, trackId);
        var ratings = dbReader.getDriverRatings(conn);
        var outliers = new OutlierDetector().detectOutliers(entries, ratings);
        dbWriter.updateOutlierFlags(conn, trackId, outliers);
        conn.commit();
        System.out.println("Outlier detection: " + outliers.size() + " flagged out of " + entries.size() + " sectors");

        int sessionCount = dbReader.getSessionCountForTrack(conn, trackId);

        // Step 2: fit each regime
        for (String regime : List.of("PLAYER", "AI")) {
            List<DbReader.CalibrationRow> data = dbReader.getCalibrationData(conn, trackId, regime);
            if (data.isEmpty()) {
                System.out.println("No data for regime " + regime + ", skipping");
                continue;
            }

            String settingsHash = computeSettingsHash(data.getFirst());
            Timestamp now = Timestamp.from(Instant.now());
            System.out.printf("Regime %s: %d data points, %d sessions%n", regime, data.size(), sessionCount);

            fitBasePace(dbWriter, conn, data, trackId, regime, settingsHash, sessionCount, now);
            fitTyreDegradation(dbWriter, conn, data, trackId, regime, settingsHash, sessionCount, now);
            fitFuelEffect(dbWriter, conn, data, trackId, regime, settingsHash, sessionCount, now);
        }
    }

    // ── base pace ────────────────────────────────────────────────────────

    static void fitBasePace(DbWriter dbWriter, Connection conn,
                            List<DbReader.CalibrationRow> data, int trackId, String regime,
                            String settingsHash, int sessionCount, Timestamp now) throws Exception {

        Map<Integer, List<DbReader.CalibrationRow>> bySector = groupBySector(data);

        for (var entry : bySector.entrySet()) {
            int sector = entry.getKey();
            List<DbReader.CalibrationRow> sectorData = entry.getValue();

            // Filter to clean conditions: fresh tyres, clean air, no damage
            List<DbReader.CalibrationRow> clean = sectorData.stream()
                    .filter(r -> r.tyreAgeLaps() <= MAX_TYRE_AGE_CLEAN)
                    .filter(r -> r.gapToCarAheadMs() > MIN_GAP_CLEAN_AIR_MS || r.gapToCarAheadMs() == 0)
                    .filter(CalibrationPipeline::hasNoDamage)
                    .toList();

            // Fall back to unfiltered if too few clean samples
            List<DbReader.CalibrationRow> source = clean.size() >= MIN_BASE_PACE_SAMPLES ? clean : sectorData;
            if (source.size() < MIN_BASE_PACE_SAMPLES) {
                System.out.printf("  base_pace sector %d: insufficient data (%d), skipping%n", sector, source.size());
                continue;
            }

            double[] times = source.stream().mapToDouble(DbReader.CalibrationRow::sectorTimeMs).toArray();
            double mean = mean(times);
            double variance = variance(times, mean);
            double stdError = Math.sqrt(variance / times.length);

            dbWriter.insertCalibrationCoefficient(conn, new DbWriter.CalibrationCoefficient(
                    trackId, "base_pace_mean", regime, sector, "mean", mean,
                    stdError, null, 0, sessionCount, times.length, settingsHash, now));
            dbWriter.insertCalibrationCoefficient(conn, new DbWriter.CalibrationCoefficient(
                    trackId, "base_pace_variance", regime, sector, "variance", variance,
                    null, null, 0, sessionCount, times.length, settingsHash, now));

            System.out.printf("  base_pace sector %d: mean=%.1fms, var=%.1f, n=%d (clean=%d)%n",
                    sector, mean, variance, times.length, clean.size());
        }
    }

    // ── tyre degradation ─────────────────────────────────────────────────

    static void fitTyreDegradation(DbWriter dbWriter, Connection conn,
                                   List<DbReader.CalibrationRow> data, int trackId, String regime,
                                   String settingsHash, int sessionCount, Timestamp now) throws Exception {

        // Group by (sector, compound)
        Map<String, List<DbReader.CalibrationRow>> groups = new HashMap<>();
        for (DbReader.CalibrationRow r : data) {
            String knobName = COMPOUND_KNOB_NAMES.get(r.tyreCompoundActual());
            if (knobName == null) continue;
            String key = r.sectorNumber() + "|" + knobName;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        for (var entry : groups.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            int sector = Integer.parseInt(parts[0]);
            String knobName = parts[1];
            List<DbReader.CalibrationRow> group = entry.getValue();

            if (group.size() < MIN_TYRE_DEG_SAMPLES) {
                System.out.printf("  %s sector %d: insufficient data (%d), skipping%n", knobName, sector, group.size());
                continue;
            }

            double[] x = group.stream().mapToDouble(DbReader.CalibrationRow::tyreAgeLaps).toArray();
            double[] y = group.stream().mapToDouble(DbReader.CalibrationRow::sectorTimeMs).toArray();
            RegressionResult reg = linearRegression(x, y);

            dbWriter.insertCalibrationCoefficient(conn, new DbWriter.CalibrationCoefficient(
                    trackId, knobName, regime, sector, "linear_regression", reg.slope,
                    reg.slopeStdError, reg.rSquared, 0, sessionCount, reg.n, settingsHash, now));

            System.out.printf("  %s sector %d: slope=%.2f ms/lap, R²=%.4f, n=%d%n",
                    knobName, sector, reg.slope, reg.rSquared, reg.n);
        }
    }

    // ── fuel effect ──────────────────────────────────────────────────────

    static void fitFuelEffect(DbWriter dbWriter, Connection conn,
                              List<DbReader.CalibrationRow> data, int trackId, String regime,
                              String settingsHash, int sessionCount, Timestamp now) throws Exception {

        // Filter to rows with non-zero fuel
        List<DbReader.CalibrationRow> withFuel = data.stream()
                .filter(r -> r.fuelInTankKg() > 0)
                .toList();

        if (withFuel.size() < MIN_FUEL_SAMPLES) {
            System.out.printf("  fuel_effect: insufficient data (%d), skipping%n", withFuel.size());
            return;
        }

        double[] x = withFuel.stream().mapToDouble(DbReader.CalibrationRow::fuelInTankKg).toArray();
        double[] y = withFuel.stream().mapToDouble(DbReader.CalibrationRow::sectorTimeMs).toArray();
        RegressionResult reg = linearRegression(x, y);

        dbWriter.insertCalibrationCoefficient(conn, new DbWriter.CalibrationCoefficient(
                trackId, "fuel_effect", regime, null, "linear_regression", reg.slope,
                reg.slopeStdError, reg.rSquared, 0, sessionCount, reg.n, settingsHash, now));

        System.out.printf("  fuel_effect: slope=%.2f ms/kg, R²=%.4f, n=%d%n",
                reg.slope, reg.rSquared, reg.n);
    }

    // ── statistics ───────────────────────────────────────────────────────

    static RegressionResult linearRegression(double[] x, double[] y) {
        int n = x.length;
        double meanX = mean(x);
        double meanY = mean(y);

        double ssXY = 0, ssXX = 0, ssYY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            ssXY += dx * dy;
            ssXX += dx * dx;
            ssYY += dy * dy;
        }

        if (ssXX == 0) {
            return new RegressionResult(0, meanY, 0, 0, n);
        }

        double slope = ssXY / ssXX;
        double intercept = meanY - slope * meanX;
        double rSquared = ssYY > 0 ? (ssXY * ssXY) / (ssXX * ssYY) : 0;

        double residualVariance = n > 2 ? (ssYY - slope * ssXY) / (n - 2) : 0;
        double slopeStdError = residualVariance > 0 ? Math.sqrt(residualVariance / ssXX) : 0;

        return new RegressionResult(slope, intercept, rSquared, slopeStdError, n);
    }

    static double mean(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    static double variance(double[] values, double mean) {
        double sum = 0;
        for (double v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return sum / values.length;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    static String computeSettingsHash(DbReader.CalibrationRow row) {
        String key = row.aiDifficulty() + "|" + row.carDamageSetting() + "|"
                + row.carDamageRate() + "|" + row.lowFuelMode();
        return Integer.toHexString(key.hashCode());
    }

    static Map<Integer, List<DbReader.CalibrationRow>> groupBySector(List<DbReader.CalibrationRow> data) {
        Map<Integer, List<DbReader.CalibrationRow>> map = new HashMap<>();
        for (DbReader.CalibrationRow r : data) {
            map.computeIfAbsent(r.sectorNumber(), k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    static boolean hasNoDamage(DbReader.CalibrationRow r) {
        return r.frontWingDamageL() == 0 && r.frontWingDamageR() == 0
                && r.rearWingDamage() == 0 && r.floorDamage() == 0
                && r.diffuserDamage() == 0 && r.sidepodDamage() == 0
                && r.engineDamage() == 0 && r.gearboxDamage() == 0;
    }
}
