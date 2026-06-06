package dev.victormartin.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure calibration-readiness logic: classify a sector row into good/bad reason,
 * and turn clean-sector counts into confidence (mapped to the calibration gates).
 * No DB access — the controller projects rows and feeds them here.
 */
public final class ReadinessCalculator {

    private ReadinessCalculator() {}

    // Gates mirrored from calibration/pipeline.py.
    public static final int BASE_SAMPLES = 3;   // MIN_SECTOR_BASELINE_SAMPLES
    public static final int DEG_SAMPLES = 10;   // MIN_TYRE_DEG_SAMPLES
    public static final double PACE_WEIGHT = 0.5;

    private static final int[] DRY_COMPOUNDS = {16, 17, 18};
    private static final int[] WET_COMPOUNDS = {7, 8};

    public enum Reason { GOOD, PIT, SAFETY_CAR, INVALID, CORNER_CUT, DAMAGE, STANDING_START, OUTLIER }

    /** Projected columns of one sector_snapshots row (damaged precomputed in SQL). */
    public record SectorRow(
            int compound, int sector, int pitStatus, int safetyCarStatus,
            int lapInvalid, int cornerCuttingWarnings, int lapNumber, int outlier,
            Long sectorTimeMs, boolean damaged, int sessionType) {}

    /** Race session types (F1 25: 10-12 and 15-17). Used to scope the standing-start
     * exclusion — in launch sessions (FP/Qualy) lap 1 / sector 0 is a clean flying
     * sector, not a standing start. */
    private static boolean isRaceType(int sessionType) {
        return (sessionType >= 10 && sessionType <= 12) || (sessionType >= 15 && sessionType <= 17);
    }

    public record Reasons(int outlier, int invalid, int cornerCut, int pit,
                          int safetyCar, int damage, int standingStart) {}

    public record SectorReadiness(int sector, int good, int total, double confidence) {}

    public record CompoundReadiness(int compound, String name, int total, int good,
                                    Reasons reasons, double confidence,
                                    List<SectorReadiness> sectors) {}

    public static String compoundName(int compound) {
        return switch (compound) {
            case 16 -> "Soft";
            case 17 -> "Medium";
            case 18 -> "Hard";
            case 7 -> "Intermediate";
            case 8 -> "Wet";
            default -> "C" + compound;
        };
    }

    /** First matching reason by priority; GOOD if the row passes the clean filter. */
    public static Reason classify(SectorRow r) {
        if (r.pitStatus() != 0) return Reason.PIT;
        if (r.safetyCarStatus() != 0) return Reason.SAFETY_CAR;
        if (r.lapInvalid() != 0) return Reason.INVALID;
        if (r.cornerCuttingWarnings() != 0) return Reason.CORNER_CUT;
        if (r.damaged()) return Reason.DAMAGE;
        if (r.lapNumber() == 1 && r.sector() == 0 && isRaceType(r.sessionType())) return Reason.STANDING_START;
        if (r.outlier() != 0) return Reason.OUTLIER;
        if (r.sectorTimeMs() == null || r.sectorTimeMs() <= 0) return Reason.INVALID;
        return Reason.GOOD;
    }

    public static double sectorConfidence(int goodCount) {
        double pace = Math.min((double) goodCount / BASE_SAMPLES, 1.0);
        double deg = Math.min((double) goodCount / DEG_SAMPLES, 1.0);
        return PACE_WEIGHT * pace + (1.0 - PACE_WEIGHT) * deg;
    }

    public static double compoundConfidence(double[] sectorConfidences) {
        if (sectorConfidences.length == 0) return 0.0;
        double sum = 0;
        for (double c : sectorConfidences) sum += c;
        return sum / sectorConfidences.length;
    }

    public static double overallConfidence(List<Double> compoundConfidencesWithData) {
        if (compoundConfidencesWithData.isEmpty()) return 0.0;
        double sum = 0;
        for (double c : compoundConfidencesWithData) sum += c;
        return sum / compoundConfidencesWithData.size();
    }

    /**
     * Aggregate projected rows into per-compound readiness. Dry compounds (S/M/H)
     * are always emitted; wet compounds (I/W) only when they have rows. Each
     * compound carries its 3 sectors (0/1/2) even when empty.
     */
    public static List<CompoundReadiness> aggregate(List<SectorRow> rows) {
        // Per-compound mutable tallies, indexed by sector (0/1/2).
        Map<Integer, int[]> goodBySector = new LinkedHashMap<>();     // [sector] -> good count
        Map<Integer, int[]> totalBySector = new LinkedHashMap<>();    // [sector] -> total count
        Map<Integer, int[][]> reasonBySector = new LinkedHashMap<>(); // [sector][Reason.ordinal()-1]

        for (SectorRow r : rows) {
            int c = r.compound();
            int s = r.sector();
            if (s < 0 || s > 2) continue;
            goodBySector.computeIfAbsent(c, k -> new int[3]);
            totalBySector.computeIfAbsent(c, k -> new int[3]);
            reasonBySector.computeIfAbsent(c, k -> new int[3][7]); // 7 bad reasons
            totalBySector.get(c)[s]++;
            Reason reason = classify(r);
            if (reason == Reason.GOOD) {
                goodBySector.get(c)[s]++;
            } else {
                reasonBySector.get(c)[s][reason.ordinal() - 1]++; // GOOD is ordinal 0
            }
        }

        List<Integer> compounds = new ArrayList<>();
        for (int c : DRY_COMPOUNDS) compounds.add(c);
        for (int c : WET_COMPOUNDS) if (totalBySector.containsKey(c)) compounds.add(c);

        List<CompoundReadiness> out = new ArrayList<>();
        for (int c : compounds) {
            int[] g = goodBySector.getOrDefault(c, new int[3]);
            int[] t = totalBySector.getOrDefault(c, new int[3]);
            int[][] rc = reasonBySector.getOrDefault(c, new int[3][7]);
            List<SectorReadiness> sectors = new ArrayList<>(3);
            double[] confs = new double[3];
            int totalAll = 0;
            int goodAll = 0;
            int[] reasonAll = new int[7];
            for (int s = 0; s < 3; s++) {
                confs[s] = sectorConfidence(g[s]);
                sectors.add(new SectorReadiness(s, g[s], t[s], confs[s]));
                totalAll += t[s];
                goodAll += g[s];
                for (int i = 0; i < 7; i++) reasonAll[i] += rc[s][i];
            }
            // reasonAll follows Reason ordinals 1..7:
            // PIT, SAFETY_CAR, INVALID, CORNER_CUT, DAMAGE, STANDING_START, OUTLIER
            Reasons reasons = new Reasons(
                    reasonAll[6],  // outlier
                    reasonAll[2],  // invalid
                    reasonAll[3],  // cornerCut
                    reasonAll[0],  // pit
                    reasonAll[1],  // safetyCar
                    reasonAll[4],  // damage
                    reasonAll[5]); // standingStart
            out.add(new CompoundReadiness(
                    c, compoundName(c), totalAll, goodAll, reasons,
                    compoundConfidence(confs), sectors));
        }
        return out;
    }
}
