package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SimulationAccuracyEvaluatorTest {

    @Test
    void absErrorIsAbsoluteDifference() {
        // Predicted P20.0, finished P2 -> 18 places of error (the postmortem case).
        assertEquals(18.0, SimulationAccuracyEvaluator.absError(20.0, 2), 1e-9);
        assertEquals(0.5, SimulationAccuracyEvaluator.absError(1.5, 1), 1e-9);
        assertEquals(3.2, SimulationAccuracyEvaluator.absError(4.8, 8), 1e-9);
    }

    @Test
    void parseUidDecodesUnsignedHex() {
        assertEquals(-8511303853535344022L,
                SimulationAccuracyEvaluator.parseUid("89e1c63d729cca6a"));
    }

    @Test
    void parseUidReturnsNullForPlaceholders() {
        assertNull(SimulationAccuracyEvaluator.parseUid(null));
        assertNull(SimulationAccuracyEvaluator.parseUid(""));
        assertNull(SimulationAccuracyEvaluator.parseUid("-"));
    }
}
