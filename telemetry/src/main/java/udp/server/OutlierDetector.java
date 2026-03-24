package udp.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-driver IQR outlier detection for sector times.
 * Runs as Step 0 of Tier 1 batch calibration — after hard filters, before knob fitting.
 */
public class OutlierDetector {

    static final int MIN_SAMPLES_FOR_IQR = 10;
    static final double AI_MULTIPLIER = 1.5;
    static final double HUMAN_MULTIPLIER = 2.0;
    static final int DEFAULT_SKILL_RATING = 50;

    public record SectorKey(long sessionUid, int carIndex, int lapNumber, int sectorNumber) {}

    public record SectorEntry(
            long sessionUid, int carIndex, int lapNumber, int sectorNumber,
            long sectorTimeMs, String driverName, int trackId,
            int tyreCompoundActual, boolean aiControlled) {}

    public record DriverRating(String driverName, int trackId, int skillRating) {}

    /**
     * Detect outliers across a set of hard-filtered sector entries.
     *
     * @param entries       sectors already filtered by hard rules (lap_invalid, pit, SC, lap 1)
     * @param driverRatings available driver ratings (track-specific and global)
     * @return list of sector keys that should be flagged as outliers
     */
    public List<SectorKey> detectOutliers(List<SectorEntry> entries, List<DriverRating> driverRatings) {
        // Index driver ratings by (driverName, trackId)
        Map<String, Map<Integer, Integer>> ratingIndex = indexRatings(driverRatings);

        // Group by (driverName, trackId, sectorNumber, tyreCompoundActual)
        Map<String, List<SectorEntry>> groups = new HashMap<>();
        for (SectorEntry e : entries) {
            String key = e.driverName + "|" + e.trackId + "|" + e.sectorNumber + "|" + e.tyreCompoundActual;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        // Compute cross-driver medians per (trackId, sectorNumber, tyreCompoundActual)
        Map<String, Long> crossDriverMedians = computeCrossDriverMedians(entries);

        List<SectorKey> outliers = new ArrayList<>();

        for (List<SectorEntry> group : groups.values()) {
            if (group.isEmpty()) continue;

            if (group.size() >= MIN_SAMPLES_FOR_IQR) {
                outliers.addAll(detectByIqr(group));
            } else {
                outliers.addAll(detectByColdStart(group, ratingIndex, crossDriverMedians));
            }
        }

        return outliers;
    }

    List<SectorKey> detectByIqr(List<SectorEntry> group) {
        List<Long> times = new ArrayList<>();
        for (SectorEntry e : group) {
            times.add(e.sectorTimeMs);
        }
        Collections.sort(times);

        long q1 = percentile(times, 25);
        long q3 = percentile(times, 75);
        long iqr = q3 - q1;

        if (iqr == 0) return List.of();

        boolean ai = group.getFirst().aiControlled;
        double multiplier = ai ? AI_MULTIPLIER : HUMAN_MULTIPLIER;
        double lowerFence = q1 - multiplier * iqr;
        double upperFence = q3 + multiplier * iqr;

        List<SectorKey> outliers = new ArrayList<>();
        for (SectorEntry e : group) {
            if (e.sectorTimeMs < lowerFence || e.sectorTimeMs > upperFence) {
                outliers.add(new SectorKey(e.sessionUid, e.carIndex, e.lapNumber, e.sectorNumber));
            }
        }
        return outliers;
    }

    List<SectorKey> detectByColdStart(
            List<SectorEntry> group,
            Map<String, Map<Integer, Integer>> ratingIndex,
            Map<String, Long> crossDriverMedians) {

        SectorEntry sample = group.getFirst();
        int skillRating = lookupSkillRating(ratingIndex, sample.driverName, sample.trackId);
        String medianKey = sample.trackId + "|" + sample.sectorNumber + "|" + sample.tyreCompoundActual;
        Long referenceMedian = crossDriverMedians.get(medianKey);

        if (referenceMedian == null) return List.of();

        double toleranceMs = 1500.0 * (110 - skillRating) / 60.0;

        List<SectorKey> outliers = new ArrayList<>();
        for (SectorEntry e : group) {
            if (e.sectorTimeMs > referenceMedian + toleranceMs) {
                outliers.add(new SectorKey(e.sessionUid, e.carIndex, e.lapNumber, e.sectorNumber));
            }
        }
        return outliers;
    }

    static int lookupSkillRating(Map<String, Map<Integer, Integer>> ratingIndex, String driverName, int trackId) {
        Map<Integer, Integer> tracks = ratingIndex.get(driverName);
        if (tracks == null) return DEFAULT_SKILL_RATING;
        // Track-specific first, then global (-1)
        Integer rating = tracks.get(trackId);
        if (rating != null) return rating;
        rating = tracks.get(-1);
        return rating != null ? rating : DEFAULT_SKILL_RATING;
    }

    static Map<String, Map<Integer, Integer>> indexRatings(List<DriverRating> ratings) {
        Map<String, Map<Integer, Integer>> index = new HashMap<>();
        for (DriverRating r : ratings) {
            index.computeIfAbsent(r.driverName, k -> new HashMap<>()).put(r.trackId, r.skillRating);
        }
        return index;
    }

    static Map<String, Long> computeCrossDriverMedians(List<SectorEntry> entries) {
        Map<String, List<Long>> grouped = new HashMap<>();
        for (SectorEntry e : entries) {
            String key = e.trackId + "|" + e.sectorNumber + "|" + e.tyreCompoundActual;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(e.sectorTimeMs);
        }
        Map<String, Long> medians = new HashMap<>();
        for (var entry : grouped.entrySet()) {
            medians.put(entry.getKey(), median(entry.getValue()));
        }
        return medians;
    }

    static long percentile(List<Long> sorted, int pct) {
        if (sorted.size() == 1) return sorted.getFirst();
        double index = pct / 100.0 * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted.get(lower);
        double fraction = index - lower;
        return Math.round(sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower)));
    }

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return percentile(sorted, 50);
    }
}
