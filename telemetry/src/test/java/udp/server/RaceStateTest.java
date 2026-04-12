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
