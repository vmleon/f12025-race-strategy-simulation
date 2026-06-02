package dev.victormartin.telemetry.engineer.log;

import java.sql.Timestamp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcRadioMessageLogTest {

    @Test
    void bindArgsMapsFieldsToColumnOrder() {
        RadioMessageLogEntry e = new RadioMessageLogEntry(
                "12345", 7, 15, 3, 58, 5, 1234.5, 1,
                "ON_TRACK", "Soft", 4, "NORMAL", "Box this lap", "[{\"rank\":1}]",
                1_700_000_000_000L);

        Object[] args = JdbcRadioMessageLog.bindArgs(e);

        assertEquals(15, args.length);
        assertEquals("12345", args[0]);
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
        assertEquals("[{\"rank\":1}]", args[13]);
        assertEquals(new Timestamp(1_700_000_000_000L), args[14]);
    }
}
