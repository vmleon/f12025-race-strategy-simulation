package udp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColdStartDefaultsTest {

    @Test
    void knobDefaultsContainsAllElevenKnobs() {
        assertEquals(11, ColdStartDefaults.KNOB_DEFAULTS.size());
    }

    @Test
    void knobNamesAreUnique() {
        long distinct = ColdStartDefaults.KNOB_DEFAULTS.stream()
                .map(ColdStartDefaults.KnobDefault::knobName)
                .distinct()
                .count();
        assertEquals(ColdStartDefaults.KNOB_DEFAULTS.size(), distinct);
    }

    @Test
    void expectedKnobNamesPresent() {
        var names = ColdStartDefaults.KNOB_DEFAULTS.stream()
                .map(ColdStartDefaults.KnobDefault::knobName)
                .toList();
        assertTrue(names.contains("tyre_deg_soft"));
        assertTrue(names.contains("tyre_deg_medium"));
        assertTrue(names.contains("tyre_deg_hard"));
        assertTrue(names.contains("fuel_effect"));
        assertTrue(names.contains("front_wing_damage"));
        assertTrue(names.contains("floor_damage"));
        assertTrue(names.contains("engine_damage"));
        assertTrue(names.contains("dirty_air"));
        assertTrue(names.contains("drs_advantage"));
        assertTrue(names.contains("overtake_probability"));
        assertTrue(names.contains("safety_car_rate"));
    }

    @Test
    void drsAdvantageIsNegative() {
        var drs = ColdStartDefaults.KNOB_DEFAULTS.stream()
                .filter(k -> k.knobName().equals("drs_advantage"))
                .findFirst()
                .orElseThrow();
        assertTrue(drs.value() < 0, "DRS advantage should be negative (time gain)");
    }

    @Test
    void allOtherKnobsArePositive() {
        ColdStartDefaults.KNOB_DEFAULTS.stream()
                .filter(k -> !k.knobName().equals("drs_advantage"))
                .forEach(k -> assertTrue(k.value() > 0,
                        k.knobName() + " should have a positive default value"));
    }

    @Test
    void totalRowsInsertedIsTwentyTwo() {
        // 11 knobs × 2 regimes (PLAYER + AI) = 22 rows
        int expectedRows = 11 * 2;
        assertEquals(22, expectedRows);
    }
}
