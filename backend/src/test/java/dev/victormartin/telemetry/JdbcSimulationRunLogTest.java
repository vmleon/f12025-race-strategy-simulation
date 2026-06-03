package dev.victormartin.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdbcSimulationRunLogTest {

    @Test
    void parseSessionUidConvertsUnsignedHexToSignedLong() {
        // The orchestrator passes the telemetry session uid, which is serialized
        // as unsigned hex (Long.toHexString); decode it back to the signed long
        // stored in sessions.session_uid.
        assertEquals(6148076303767893186L,
                JdbcSimulationRunLog.parseSessionUid(Long.toHexString(6148076303767893186L)));
        assertEquals(-8511303853535344022L,
                JdbcSimulationRunLog.parseSessionUid("89e1c63d729cca6a"));
    }

    @Test
    void parseSessionUidReturnsNullForPlaceholders() {
        assertNull(JdbcSimulationRunLog.parseSessionUid(null));
        assertNull(JdbcSimulationRunLog.parseSessionUid(""));
        assertNull(JdbcSimulationRunLog.parseSessionUid("-"));
    }
}
