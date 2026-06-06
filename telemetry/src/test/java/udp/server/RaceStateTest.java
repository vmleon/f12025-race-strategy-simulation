package udp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceStateTest {

    @Test
    void toJsonLineIncludesEnrichedFields() {
        RaceState state = new RaceState();
        state.fillTestData();

        String json = state.toJsonLine();
        assertNotNull(json);

        // Session-level fields
        assertTrue(json.contains("\"totalLaps\":50"));
        assertTrue(json.contains("\"currentLap\":"));
        assertTrue(json.contains("\"trackId\":3"));
        assertTrue(json.contains("\"safetyCarStatus\":0"));

        // Per-car enriched fields
        assertTrue(json.contains("\"name\":\"Player\""));
        assertTrue(json.contains("\"ai\":false"));
        assertTrue(json.contains("\"ai\":true"));
        assertTrue(json.contains("\"fuel\":"));
        assertTrue(json.contains("\"pits\":0"));
        assertTrue(json.contains("\"fwDmg\":0"));
        assertTrue(json.contains("\"flDmg\":0"));
        assertTrue(json.contains("\"engDmg\":0"));
        assertTrue(json.contains("\"resultStatus\":2"));
        assertTrue(json.contains("\"throttle\":0.80"));
    }

    @Test
    void toJsonLineIncludesYellowSectors() {
        RaceState state = new RaceState();
        state.updateFromSession(0x1234L, yellowSessionDataSector1());

        String json = state.toJsonLine();
        assertNotNull(json);
        assertTrue(json.contains("\"yellowSectors\":[1]"),
                "state line should carry the yellow sector; got: " + json);
    }

    /** A SessionData packet with a single yellow marshal zone mapping to sector 1. */
    private static SessionData yellowSessionDataSector1() {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(753);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.position(PacketHeader.HEADER_SIZE);
        buf.put((byte) 0);          // weather
        buf.put((byte) 25);         // trackTemperature
        buf.put((byte) 20);         // airTemperature
        buf.put((byte) 50);         // totalLaps
        buf.putShort((short) 5303); // trackLength
        buf.put((byte) 10);         // sessionType
        buf.put((byte) 5);          // trackId
        buf.put((byte) 0);          // formula
        buf.putShort((short) 0);    // sessionTimeLeft
        buf.putShort((short) 0);    // sessionDuration
        for (int i = 0; i < 5; i++) buf.put((byte) 0); // pit/spectate/sli skips
        buf.put((byte) 1);          // numMarshalZones
        buf.putFloat(0.5f); buf.put((byte) 3); // yellow → sector 1 (thirds fallback)
        for (int i = 1; i < 21; i++) { buf.putFloat(0f); buf.put((byte) 0); }
        byte[] data = buf.array();
        return SessionData.parse(data, data.length);
    }

    @Test
    void driverNameJsonResolvesIndexAndGuardsRange() {
        RaceState state = new RaceState();
        state.fillTestData();
        assertEquals("Player", state.driverNameJson(0));
        assertEquals("", state.driverNameJson(99));
        assertEquals("", state.driverNameJson(-1));
    }

    @Test
    void eventQueuePolling() {
        RaceState state = new RaceState();
        assertNull(state.pollEvent());

        state.queueEvent("{\"type\":\"event\",\"event\":\"SCAR\"}");
        state.queueEvent("{\"type\":\"event\",\"event\":\"RTMT\"}");

        assertEquals("{\"type\":\"event\",\"event\":\"SCAR\"}", state.pollEvent());
        assertEquals("{\"type\":\"event\",\"event\":\"RTMT\"}", state.pollEvent());
        assertNull(state.pollEvent());
    }

    @Test
    void toJsonLineReturnsNullWhenInactive() {
        RaceState state = new RaceState();
        assertNull(state.toJsonLine());
    }

    @Test
    void sessionLifecyclePolling() {
        RaceState state = new RaceState();

        // No session yet
        assertNull(state.pollSessionStarted());
        assertNull(state.pollSessionEnded());

        // Simulate session start via fillTestData (sets sessionActive=true)
        state.fillTestData();
        String started = state.pollSessionStarted();
        assertNotNull(started);
        assertTrue(started.contains("sessionStarted"));

        // Second poll should return null (already sent)
        assertNull(state.pollSessionStarted());

        // End session
        state.markSessionEnded();
        String ended = state.pollSessionEnded();
        assertNotNull(ended);
        assertTrue(ended.contains("sessionEnded"));
        assertNull(state.pollSessionEnded());
    }

    @Test
    void sessionUidChangeWithoutFinalClassificationFiresNewStart() {
        RaceState state = new RaceState();

        // Simulate FP session
        state.fillTestData(0x1111L, 3, 1); // FP1 on Bahrain
        String fpStarted = state.pollSessionStarted();
        assertNotNull(fpStarted, "FP sessionStarted should fire");
        assertTrue(fpStarted.contains("\"sessionUid\":\"1111\""));

        // Transition directly to qualifying (no FinalClassification / markSessionEnded)
        state.fillTestData(0x2222L, 3, 5); // Q1 on same track
        String qStarted = state.pollSessionStarted();
        assertNotNull(qStarted, "Qualifying sessionStarted should fire after UID change");
        assertTrue(qStarted.contains("\"sessionUid\":\"2222\""));
    }

    @Test
    void normalFinalClassificationFlowStillWorks() {
        RaceState state = new RaceState();

        // Session start
        state.fillTestData(0xAAAAL, 3, 10);
        assertNotNull(state.pollSessionStarted());

        // Normal end via FinalClassification
        state.markSessionEnded();
        String ended = state.pollSessionEnded();
        assertNotNull(ended, "sessionEnded should fire");
        assertTrue(ended.contains("\"sessionUid\":\"aaaa\""));

        // New session starts normally
        state.fillTestData(0xBBBBL, 3, 10);
        String started = state.pollSessionStarted();
        assertNotNull(started, "New sessionStarted should fire after proper end");
        assertTrue(started.contains("\"sessionUid\":\"bbbb\""));
    }
}
