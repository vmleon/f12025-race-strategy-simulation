package dev.victormartin.telemetry.engineer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SentenceSeparatorTest {

    @Test
    void insertsSeparatorBetweenSentences() {
        assertEquals("Lap 1 done.|You are P4.",
                RaceEngineerService.markSentenceBoundaries("Lap 1 done. You are P4."));
    }

    @Test
    void doesNotSplitDecimals() {
        String out = RaceEngineerService.markSentenceBoundaries(
                "Fastest lap, 1 minute 17.6 seconds. You're 0.3 seconds off the pace.");
        assertFalse(out.contains("17|6"), "decimal must not be split");
        assertFalse(out.contains("0|3"), "decimal must not be split");
        assertTrue(out.contains("seconds.|You're"), "real sentence boundary is marked");
    }

    @Test
    void preservesExclamationAndQuestion() {
        assertEquals("Nice one!|Box now?|Confirm.",
                RaceEngineerService.markSentenceBoundaries("Nice one! Box now? Confirm."));
    }

    @Test
    void nullSafe() {
        assertNull(RaceEngineerService.markSentenceBoundaries(null));
    }
}
