package udp.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutlierDetectorTest {

    private final OutlierDetector detector = new OutlierDetector();

    // Helper to build a sector entry
    private static OutlierDetector.SectorEntry entry(
            long sessionUid, int carIndex, int lap, int sector,
            long timeMs, String driver, int trackId, int compound, boolean ai) {
        return new OutlierDetector.SectorEntry(
                sessionUid, carIndex, lap, sector, timeMs, driver, trackId, compound, ai);
    }

    @Test
    void iqrFlagsObviousOutlierWithTenSamples() {
        // 10 entries: 9 clustered around 30000ms, 1 extreme at 45000ms
        List<OutlierDetector.SectorEntry> entries = new ArrayList<>();
        long[] times = {29500, 29800, 30000, 30100, 30200, 30300, 30400, 30500, 30600, 45000};
        for (int i = 0; i < times.length; i++) {
            entries.add(entry(1L, 0, i + 2, 1, times[i], "Hamilton", 1, 16, false));
        }

        List<OutlierDetector.SectorKey> outliers = detector.detectOutliers(entries, List.of());

        assertEquals(1, outliers.size());
        assertEquals(45000, entries.stream()
                .filter(e -> e.lapNumber() == outliers.getFirst().lapNumber())
                .findFirst().orElseThrow().sectorTimeMs());
    }

    @Test
    void iqrWithZeroVarianceProducesNoOutliers() {
        // All identical times → IQR = 0 → skip
        List<OutlierDetector.SectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(entry(1L, 0, i + 2, 1, 30000, "Verstappen", 1, 16, false));
        }

        List<OutlierDetector.SectorKey> outliers = detector.detectOutliers(entries, List.of());

        assertTrue(outliers.isEmpty());
    }

    @Test
    void aiMultiplierIsTighterThanHuman() {
        // Same data, AI should flag more outliers due to tighter 1.5x multiplier
        long[] times = {29500, 29800, 30000, 30100, 30200, 30300, 30400, 30500, 30600, 33000};

        List<OutlierDetector.SectorEntry> aiEntries = new ArrayList<>();
        List<OutlierDetector.SectorEntry> humanEntries = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            aiEntries.add(entry(1L, 0, i + 2, 1, times[i], "AI_Driver", 1, 16, true));
            humanEntries.add(entry(1L, 1, i + 2, 1, times[i], "Human_Driver", 1, 16, false));
        }

        List<OutlierDetector.SectorKey> aiOutliers = detector.detectOutliers(aiEntries, List.of());
        List<OutlierDetector.SectorKey> humanOutliers = detector.detectOutliers(humanEntries, List.of());

        assertTrue(aiOutliers.size() >= humanOutliers.size(),
                "AI (1.5x) should flag at least as many outliers as human (2.0x)");
    }

    @Test
    void coldStartFallbackUsesSkillRating() {
        // 5 entries (< 10, so cold-start path), skill_rating = 80 → tolerance = 750ms
        // Cross-driver median needs more entries from OTHER drivers to establish baseline
        List<OutlierDetector.SectorEntry> entries = new ArrayList<>();
        // Add 15 entries from other drivers to establish a cross-driver median of ~30000
        for (int i = 0; i < 15; i++) {
            entries.add(entry(1L, i + 1, 2, 1, 29800 + i * 30, "OtherDriver" + i, 1, 16, false));
        }
        // Target driver with 5 entries: 4 normal, 1 way above median + tolerance
        entries.add(entry(1L, 0, 2, 1, 30000, "TestDriver", 1, 16, false));
        entries.add(entry(1L, 0, 3, 1, 30100, "TestDriver", 1, 16, false));
        entries.add(entry(1L, 0, 4, 1, 30200, "TestDriver", 1, 16, false));
        entries.add(entry(1L, 0, 5, 1, 30300, "TestDriver", 1, 16, false));
        entries.add(entry(1L, 0, 6, 1, 32000, "TestDriver", 1, 16, false));

        List<OutlierDetector.DriverRating> ratings = List.of(
                new OutlierDetector.DriverRating("TestDriver", -1, 80));

        List<OutlierDetector.SectorKey> outliers = detector.detectOutliers(entries, ratings);

        // With skill_rating=80: tolerance = 1500 * (110-80)/60 = 750ms
        // Median across all is ~30000ms. 32000 > 30000+750=30750 → flagged
        boolean testDriverFlagged = outliers.stream()
                .anyMatch(k -> k.carIndex() == 0 && k.lapNumber() == 6);
        assertTrue(testDriverFlagged, "32000ms should be flagged with tolerance 750ms above ~30000ms median");
    }

    @Test
    void coldStartDefaultRatingWhenNoRatingExists() {
        // No ratings provided → default skill_rating = 50 → tolerance = 1500 * (110-50)/60 = 1500ms
        List<OutlierDetector.SectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            entries.add(entry(1L, i + 1, 2, 1, 30000, "Other" + i, 1, 16, false));
        }
        // Target with 3 entries: one at median + 1600ms (should be flagged with default tolerance 1500ms)
        entries.add(entry(1L, 0, 2, 1, 30000, "Norris", 1, 16, false));
        entries.add(entry(1L, 0, 3, 1, 30100, "Norris", 1, 16, false));
        entries.add(entry(1L, 0, 4, 1, 31600, "Norris", 1, 16, false));

        List<OutlierDetector.SectorKey> outliers = detector.detectOutliers(entries, List.of());

        boolean flagged = outliers.stream()
                .anyMatch(k -> k.carIndex() == 0 && k.lapNumber() == 4);
        assertTrue(flagged, "31600ms should be flagged with default tolerance 1500ms above 30000ms median");
    }

    @Test
    void trackSpecificRatingOverridesGlobal() {
        int trackId = 5;
        List<OutlierDetector.DriverRating> ratings = List.of(
                new OutlierDetector.DriverRating("Driver1", -1, 50),   // global
                new OutlierDetector.DriverRating("Driver1", trackId, 95));  // track-specific

        var index = OutlierDetector.indexRatings(ratings);
        int rating = OutlierDetector.lookupSkillRating(index, "Driver1", trackId);

        assertEquals(95, rating, "Track-specific rating should override global");
    }

    @Test
    void percentileComputationIsCorrect() {
        List<Long> values = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        long q1 = OutlierDetector.percentile(values, 25);
        long q3 = OutlierDetector.percentile(values, 75);

        assertEquals(3, q1);
        assertEquals(8, q3);
    }

    @Test
    void medianOfEvenCountInterpolates() {
        List<Long> values = List.of(10L, 20L, 30L, 40L);
        long med = OutlierDetector.median(values);
        assertEquals(25, med);
    }

    @Test
    void emptyInputReturnsNoOutliers() {
        List<OutlierDetector.SectorKey> outliers = detector.detectOutliers(List.of(), List.of());
        assertTrue(outliers.isEmpty());
    }
}
