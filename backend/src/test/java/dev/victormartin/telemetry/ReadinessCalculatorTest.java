package dev.victormartin.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.ReadinessCalculator.CompoundReadiness;
import dev.victormartin.telemetry.ReadinessCalculator.Reason;
import dev.victormartin.telemetry.ReadinessCalculator.SectorRow;

class ReadinessCalculatorTest {

    private SectorRow row(int compound, int sector, int pit, int sc, int invalid,
                          int cut, int lap, int outlier, Long timeMs, boolean dmg) {
        return new SectorRow(compound, sector, pit, sc, invalid, cut, lap, outlier, timeMs, dmg);
    }

    private SectorRow clean(int compound, int sector) {
        return row(compound, sector, 0, 0, 0, 0, 5, 0, 30_000L, false);
    }

    @Test
    void cleanSectorIsGood() {
        assertEquals(Reason.GOOD, ReadinessCalculator.classify(clean(16, 1)));
    }

    @Test
    void pitWinsOverEverything() {
        assertEquals(Reason.PIT,
                ReadinessCalculator.classify(row(16, 1, 1, 1, 1, 1, 5, 1, 30_000L, true)));
    }

    @Test
    void priorityChainAfterPit() {
        assertEquals(Reason.SAFETY_CAR, ReadinessCalculator.classify(row(16, 1, 0, 1, 1, 1, 5, 1, 30_000L, true)));
        assertEquals(Reason.INVALID, ReadinessCalculator.classify(row(16, 1, 0, 0, 1, 1, 5, 1, 30_000L, true)));
        assertEquals(Reason.CORNER_CUT, ReadinessCalculator.classify(row(16, 1, 0, 0, 0, 1, 5, 1, 30_000L, true)));
        assertEquals(Reason.DAMAGE, ReadinessCalculator.classify(row(16, 1, 0, 0, 0, 0, 5, 1, 30_000L, true)));
    }

    @Test
    void standingStartOnlyLap1Sector0() {
        assertEquals(Reason.STANDING_START, ReadinessCalculator.classify(row(16, 0, 0, 0, 0, 0, 1, 0, 30_000L, false)));
        assertEquals(Reason.GOOD, ReadinessCalculator.classify(row(16, 1, 0, 0, 0, 0, 1, 0, 30_000L, false)));
    }

    @Test
    void outlierThenZeroTime() {
        assertEquals(Reason.OUTLIER, ReadinessCalculator.classify(row(16, 1, 0, 0, 0, 0, 5, 1, 30_000L, false)));
        assertEquals(Reason.INVALID, ReadinessCalculator.classify(row(16, 1, 0, 0, 0, 0, 5, 0, 0L, false)));
        assertEquals(Reason.INVALID, ReadinessCalculator.classify(row(16, 1, 0, 0, 0, 0, 5, 0, null, false)));
    }

    @Test
    void sectorConfidenceBoundaries() {
        assertEquals(0.0, ReadinessCalculator.sectorConfidence(0), 1e-9);
        assertEquals(0.65, ReadinessCalculator.sectorConfidence(3), 1e-9);
        assertEquals(0.75, ReadinessCalculator.sectorConfidence(5), 1e-9);
        assertEquals(1.0, ReadinessCalculator.sectorConfidence(10), 1e-9);
        assertEquals(1.0, ReadinessCalculator.sectorConfidence(50), 1e-9);
    }

    @Test
    void compoundConfidenceIsMeanOfSectors() {
        assertEquals(1.0, ReadinessCalculator.compoundConfidence(new double[] {1.0, 1.0, 1.0}), 1e-9);
        assertEquals(0.5, ReadinessCalculator.compoundConfidence(new double[] {0.0, 0.5, 1.0}), 1e-9);
    }

    @Test
    void overallExcludesEmptyAndAveragesRest() {
        assertEquals(0.0, ReadinessCalculator.overallConfidence(List.of()), 1e-9);
        assertEquals(0.84, ReadinessCalculator.overallConfidence(List.of(0.68, 1.0)), 1e-9);
    }

    @Test
    void aggregateAlwaysEmitsDryCompoundsWithThreeSectors() {
        List<CompoundReadiness> out = ReadinessCalculator.aggregate(List.of());
        assertEquals(List.of(16, 17, 18), out.stream().map(CompoundReadiness::compound).toList());
        for (CompoundReadiness c : out) {
            assertEquals(3, c.sectors().size());
            assertEquals(0, c.total());
            assertEquals(0.0, c.confidence(), 1e-9);
        }
    }

    @Test
    void aggregateCountsGoodAndReasonsAndConfidence() {
        java.util.List<SectorRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(clean(16, 0));
        rows.add(row(16, 0, 0, 0, 0, 0, 5, 1, 30_000L, false));
        rows.add(row(16, 0, 0, 0, 0, 0, 5, 1, 30_000L, false));
        List<CompoundReadiness> out = ReadinessCalculator.aggregate(rows);
        CompoundReadiness soft = out.stream().filter(c -> c.compound() == 16).findFirst().orElseThrow();
        assertEquals(12, soft.total());
        assertEquals(10, soft.good());
        assertEquals(2, soft.reasons().outlier());
        assertEquals(1.0 / 3.0, soft.confidence(), 1e-9);
        assertTrue(out.stream().noneMatch(c -> c.compound() == 7),
                "Inter not emitted when it has no data");
    }

    @Test
    void aggregateEmitsWetCompoundsOnlyWhenPresent() {
        List<CompoundReadiness> out = ReadinessCalculator.aggregate(List.of(clean(8, 0)));
        assertTrue(out.stream().anyMatch(c -> c.compound() == 8), "Wet emitted when present");
    }
}
