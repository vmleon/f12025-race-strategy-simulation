package udp.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CalibrationPipelineTest {

    // ── linear regression ────────────────────────────────────────────────

    @Test
    void linearRegressionPerfectLine() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {2, 4, 6, 8, 10};
        var result = CalibrationPipeline.linearRegression(x, y);

        assertEquals(2.0, result.slope(), 1e-9);
        assertEquals(0.0, result.intercept(), 1e-9);
        assertEquals(1.0, result.rSquared(), 1e-9);
        assertEquals(0.0, result.slopeStdError(), 1e-9);
        assertEquals(5, result.n());
    }

    @Test
    void linearRegressionWithOffset() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {103, 106, 109, 112, 115};  // y = 100 + 3x
        var result = CalibrationPipeline.linearRegression(x, y);

        assertEquals(3.0, result.slope(), 1e-9);
        assertEquals(100.0, result.intercept(), 1e-9);
        assertEquals(1.0, result.rSquared(), 1e-9);
    }

    @Test
    void linearRegressionWithNoise() {
        // y ≈ 50x + 1000, with small noise
        double[] x = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] y = {1052, 1098, 1153, 1199, 1248, 1302, 1351, 1398, 1452, 1501};
        var result = CalibrationPipeline.linearRegression(x, y);

        assertEquals(50.0, result.slope(), 2.0);
        assertTrue(result.rSquared() > 0.99);
        assertTrue(result.slopeStdError() > 0);
        assertEquals(10, result.n());
    }

    @Test
    void linearRegressionConstantX() {
        double[] x = {5, 5, 5, 5};
        double[] y = {10, 20, 30, 40};
        var result = CalibrationPipeline.linearRegression(x, y);

        assertEquals(0.0, result.slope());
        assertEquals(0.0, result.rSquared());
    }

    // ── mean and variance ────────────────────────────────────────────────

    @Test
    void meanComputation() {
        double[] values = {10, 20, 30, 40, 50};
        assertEquals(30.0, CalibrationPipeline.mean(values), 1e-9);
    }

    @Test
    void varianceComputation() {
        double[] values = {10, 20, 30, 40, 50};
        double mean = CalibrationPipeline.mean(values);
        double variance = CalibrationPipeline.variance(values, mean);
        assertEquals(200.0, variance, 1e-9);  // population variance
    }

    // ── base pace ────────────────────────────────────────────────────────

    @Test
    void basePaceFitterUsesCleanConditions() {
        // Create data: some clean (low tyre age, clean air, no damage) and some dirty
        List<DbReader.CalibrationRow> data = new ArrayList<>();

        // 6 clean rows for sector 0 with sectorTimeMs ~ 30000
        for (int i = 0; i < 6; i++) {
            data.add(calibrationRow(0, 30000 + i * 10, 2 + i, 3, 0, 3000, 16));
        }
        // 3 dirty rows (high tyre age) that should be filtered out
        for (int i = 0; i < 3; i++) {
            data.add(calibrationRow(0, 32000, 2 + i, 20, 0, 3000, 16));
        }

        var bySector = CalibrationPipeline.groupBySector(data);
        List<DbReader.CalibrationRow> sectorData = bySector.get(0);
        List<DbReader.CalibrationRow> clean = sectorData.stream()
                .filter(r -> r.tyreAgeLaps() <= CalibrationPipeline.MAX_TYRE_AGE_CLEAN)
                .filter(r -> r.gapToCarAheadMs() > CalibrationPipeline.MIN_GAP_CLEAN_AIR_MS || r.gapToCarAheadMs() == 0)
                .filter(CalibrationPipeline::hasNoDamage)
                .toList();

        assertEquals(6, clean.size());
        double[] times = clean.stream().mapToDouble(DbReader.CalibrationRow::sectorTimeMs).toArray();
        double mean = CalibrationPipeline.mean(times);
        assertEquals(30025.0, mean, 1e-9);
    }

    // ── tyre degradation ─────────────────────────────────────────────────

    @Test
    void tyreDegFitterExtractsLinearSlope() {
        // Create 15 rows where sectorTimeMs = 30000 + 50 * tyreAgeLaps (sector 1, soft)
        List<DbReader.CalibrationRow> data = new ArrayList<>();
        for (int lap = 1; lap <= 15; lap++) {
            data.add(calibrationRow(1, 30000 + 50 * lap, 2 + lap, lap, 0, 3000, 16));
        }

        double[] x = data.stream().mapToDouble(DbReader.CalibrationRow::tyreAgeLaps).toArray();
        double[] y = data.stream().mapToDouble(DbReader.CalibrationRow::sectorTimeMs).toArray();
        var result = CalibrationPipeline.linearRegression(x, y);

        assertEquals(50.0, result.slope(), 1e-9);
        assertEquals(1.0, result.rSquared(), 1e-9);
    }

    // ── fuel effect ──────────────────────────────────────────────────────

    @Test
    void fuelEffectFitterExtractsSlope() {
        // Create rows where sectorTimeMs = 28000 + 30 * fuelInTankKg
        List<DbReader.CalibrationRow> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double fuel = 100.0 - i * 10;
            long time = (long) (28000 + 30 * fuel);
            data.add(calibrationRow(0, time, 2 + i, 3, fuel, 3000, 16));
        }

        double[] x = data.stream().mapToDouble(DbReader.CalibrationRow::fuelInTankKg).toArray();
        double[] y = data.stream().mapToDouble(DbReader.CalibrationRow::sectorTimeMs).toArray();
        var result = CalibrationPipeline.linearRegression(x, y);

        assertEquals(30.0, result.slope(), 1e-9);
        assertEquals(1.0, result.rSquared(), 1e-9);
    }

    // ── insufficient data ────────────────────────────────────────────────

    @Test
    void insufficientDataForBasePace() {
        // Fewer than MIN_BASE_PACE_SAMPLES
        List<DbReader.CalibrationRow> data = new ArrayList<>();
        for (int i = 0; i < CalibrationPipeline.MIN_BASE_PACE_SAMPLES - 1; i++) {
            data.add(calibrationRow(0, 30000, 2 + i, 3, 0, 3000, 16));
        }
        var bySector = CalibrationPipeline.groupBySector(data);
        assertTrue(bySector.get(0).size() < CalibrationPipeline.MIN_BASE_PACE_SAMPLES);
    }

    @Test
    void insufficientDataForTyreDeg() {
        List<DbReader.CalibrationRow> data = new ArrayList<>();
        for (int i = 0; i < CalibrationPipeline.MIN_TYRE_DEG_SAMPLES - 1; i++) {
            data.add(calibrationRow(0, 30000, 2 + i, i, 0, 3000, 16));
        }
        assertTrue(data.size() < CalibrationPipeline.MIN_TYRE_DEG_SAMPLES);
    }

    // ── compound mapping ─────────────────────────────────────────────────

    @Test
    void compoundMappingIsCorrect() {
        assertEquals("tyre_deg_soft", CalibrationPipeline.COMPOUND_KNOB_NAMES.get(16));
        assertEquals("tyre_deg_medium", CalibrationPipeline.COMPOUND_KNOB_NAMES.get(17));
        assertEquals("tyre_deg_hard", CalibrationPipeline.COMPOUND_KNOB_NAMES.get(18));
        assertEquals(3, CalibrationPipeline.COMPOUND_KNOB_NAMES.size());
    }

    // ── settings hash ────────────────────────────────────────────────────

    @Test
    void settingsHashIsDeterministic() {
        DbReader.CalibrationRow row1 = calibrationRow(0, 30000, 2, 3, 50, 3000, 16);
        DbReader.CalibrationRow row2 = calibrationRow(1, 31000, 5, 8, 40, 2000, 17);

        // Same settings fields → same hash
        assertEquals(
                CalibrationPipeline.computeSettingsHash(row1),
                CalibrationPipeline.computeSettingsHash(row2));
    }

    @Test
    void settingsHashDiffersForDifferentSettings() {
        DbReader.CalibrationRow row1 = calibrationRow(0, 30000, 2, 3, 50, 3000, 16);
        // Different AI difficulty
        DbReader.CalibrationRow row2 = new DbReader.CalibrationRow(
                1L, 0, 5, 1, 31000, 17, 8, 40.0, 2000L, 0,
                0, 25, 20,
                0, 0, 0, 0, 0, 0, 0, 0,
                90, 90, 90, 90, 80, 80, 80, 80,
                99, 1, 1, 0);  // aiDifficulty=99 vs 95

        assertNotEquals(
                CalibrationPipeline.computeSettingsHash(row1),
                CalibrationPipeline.computeSettingsHash(row2));
    }

    // ── damage detection ─────────────────────────────────────────────────

    @Test
    void hasNoDamageDetectsClean() {
        DbReader.CalibrationRow clean = calibrationRow(0, 30000, 2, 3, 50, 3000, 16);
        assertTrue(CalibrationPipeline.hasNoDamage(clean));
    }

    @Test
    void hasNoDamageDetectsDamaged() {
        // Row with floor damage
        DbReader.CalibrationRow damaged = new DbReader.CalibrationRow(
                1L, 0, 2, 0, 30000, 16, 3, 50.0, 3000L, 0,
                0, 25, 20,
                0, 0, 0, 15, 0, 0, 0, 0,  // floor_damage = 15
                90, 90, 90, 90, 80, 80, 80, 80,
                95, 1, 1, 0);
        assertFalse(CalibrationPipeline.hasNoDamage(damaged));
    }

    // ── test helpers ─────────────────────────────────────────────────────

    /**
     * Creates a CalibrationRow with the specified key fields and sensible defaults.
     * All damage fields are 0, settings are ai_difficulty=95, car_damage=1, low_fuel=0.
     */
    private static DbReader.CalibrationRow calibrationRow(
            int sectorNumber, long sectorTimeMs, int lapNumber, int tyreAgeLaps,
            double fuelInTankKg, long gapToCarAheadMs, int tyreCompoundActual) {
        return new DbReader.CalibrationRow(
                1L,             // sessionUid
                0,              // carIndex
                lapNumber,
                sectorNumber,
                sectorTimeMs,
                tyreCompoundActual,
                tyreAgeLaps,
                fuelInTankKg,
                gapToCarAheadMs,
                0,              // drsAllowed
                0,              // weather (dry)
                25,             // trackTemp
                20,             // airTemp
                0, 0, 0,        // wing damage
                0, 0, 0,        // floor, diffuser, sidepod damage
                0, 0,           // engine, gearbox damage
                90, 90, 90, 90, // tyre surface temps
                80, 80, 80, 80, // tyre inner temps
                95,             // aiDifficulty
                1,              // carDamageSetting
                1,              // carDamageRate
                0);             // lowFuelMode
    }
}
