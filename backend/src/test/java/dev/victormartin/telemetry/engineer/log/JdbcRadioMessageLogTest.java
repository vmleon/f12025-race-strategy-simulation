package dev.victormartin.telemetry.engineer.log;

import java.sql.Timestamp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdbcRadioMessageLogTest {

    @Test
    void bindArgsMapsFieldsToColumnOrder() {
        // Telemetry serializes the session uid as unsigned hex (Long.toHexString),
        // so the entry carries the hex string; it must be bound as the signed long
        // matching sessions.session_uid (NUMBER).
        long uid = -8511303853535344022L;
        String hexUid = Long.toHexString(uid); // "89e1c63d729cca6a"

        RadioMessageLogEntry e = new RadioMessageLogEntry(
                hexUid, 7, 15, 3, 58, 5, 1234.5, 1,
                "ON_TRACK", "Soft", 4, "NORMAL", "Box this lap", "Box, box",
                "[{\"rank\":1}]", "a1b2c3d4", 1_700_000_000_000L);

        Object[] args = JdbcRadioMessageLog.bindArgs(e);

        assertEquals(17, args.length);
        assertEquals(uid, args[0]);
        assertEquals(7, args[1]);
        assertEquals(15, args[2]);
        assertEquals(3, args[3]);
        assertEquals(58, args[4]);
        assertEquals(5, args[5]);
        assertEquals(1234.5, args[6]);
        assertEquals(1, args[7]);
        assertEquals("ON_TRACK", args[8]);
        assertEquals("Soft", args[9]);
        assertEquals(4, args[10]);
        assertEquals("NORMAL", args[11]);
        assertEquals("Box this lap", args[12]);
        assertEquals("Box, box", args[13]);
        assertEquals("[{\"rank\":1}]", args[14]);
        assertEquals("a1b2c3d4", args[15]);
        assertEquals(new Timestamp(1_700_000_000_000L), args[16]);
    }

    @Test
    void parseSessionUidConvertsUnsignedHexToSignedLong() {
        // Inverse of Long.toHexString used by the telemetry serializer.
        assertEquals(6148076303767893186L,
                JdbcRadioMessageLog.parseSessionUid(Long.toHexString(6148076303767893186L)));
        assertEquals(-8511303853535344022L,
                JdbcRadioMessageLog.parseSessionUid("89e1c63d729cca6a"));
    }

    @Test
    void parseSessionUidReturnsNullForPlaceholders() {
        assertNull(JdbcRadioMessageLog.parseSessionUid(null));
        assertNull(JdbcRadioMessageLog.parseSessionUid(""));
        assertNull(JdbcRadioMessageLog.parseSessionUid("-"));
    }
}
