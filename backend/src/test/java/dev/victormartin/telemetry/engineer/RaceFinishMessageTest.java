package dev.victormartin.telemetry.engineer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceFinishMessageTest {

    private static final int GRID = 20;

    @Test
    void podium_P1() {
        assertEquals("P1! Brilliant drive. Cooldown lap, bring it home.",
                RaceEngineerService.buildFinishMessage(1, GRID));
    }

    @Test
    void podium_P3() {
        assertTrue(RaceEngineerService.buildFinishMessage(3, GRID).startsWith("P3! Brilliant drive"));
    }

    @Test
    void points_P4() {
        assertEquals("P4. Solid points today. Good job.",
                RaceEngineerService.buildFinishMessage(4, GRID));
    }

    @Test
    void points_P10() {
        assertEquals("P10. Solid points today. Good job.",
                RaceEngineerService.buildFinishMessage(10, GRID));
    }

    @Test
    void midfield_P11() {
        assertEquals("P11. We'll take it. Plenty to review.",
                RaceEngineerService.buildFinishMessage(11, GRID));
    }

    @Test
    void midfield_P15_is_midfield_on_20_grid() {
        // grid * 3 / 4 = 15
        assertTrue(RaceEngineerService.buildFinishMessage(15, GRID).contains("We'll take it"));
    }

    @Test
    void backHalf_P16_on_20_grid() {
        assertEquals("P16. Tough one. We'll debrief and come back stronger.",
                RaceEngineerService.buildFinishMessage(16, GRID));
    }

    @Test
    void backHalf_P19_on_20_grid() {
        assertTrue(RaceEngineerService.buildFinishMessage(19, GRID).contains("Tough one"));
    }

    @Test
    void last_P20_on_20_grid() {
        assertEquals("P20. Rough day at the office. Box this lap and we regroup.",
                RaceEngineerService.buildFinishMessage(20, GRID));
    }

    @Test
    void last_on_22_grid() {
        // grid * 3 / 4 = 16; so P17–P21 are back half, P22 is last
        assertTrue(RaceEngineerService.buildFinishMessage(22, 22).contains("Rough day at the office"));
        assertTrue(RaceEngineerService.buildFinishMessage(21, 22).contains("Tough one"));
        assertTrue(RaceEngineerService.buildFinishMessage(16, 22).contains("We'll take it"));
    }

    @Test
    void invalid_position_falls_back() {
        assertEquals("Chequered flag. Box this lap.",
                RaceEngineerService.buildFinishMessage(0, GRID));
    }
}
