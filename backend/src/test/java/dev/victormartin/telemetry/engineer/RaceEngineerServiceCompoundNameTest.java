package dev.victormartin.telemetry.engineer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServiceCompoundNameTest {

    @Test
    void softAbbreviation() {
        assertEquals("soft", RaceEngineerService.abbreviationToSpokenName("S"));
    }

    @Test
    void mediumAbbreviation() {
        assertEquals("medium", RaceEngineerService.abbreviationToSpokenName("M"));
    }

    @Test
    void hardAbbreviation() {
        assertEquals("hard", RaceEngineerService.abbreviationToSpokenName("H"));
    }

    @Test
    void intermediateAbbreviation() {
        assertEquals("intermediate", RaceEngineerService.abbreviationToSpokenName("I"));
    }

    @Test
    void wetAbbreviation() {
        assertEquals("wet", RaceEngineerService.abbreviationToSpokenName("W"));
    }

    @Test
    void unknownAbbreviation() {
        assertEquals("unknown", RaceEngineerService.abbreviationToSpokenName("?"));
    }

    @Test
    void nullAbbreviation() {
        assertEquals("unknown", RaceEngineerService.abbreviationToSpokenName(null));
    }
}
