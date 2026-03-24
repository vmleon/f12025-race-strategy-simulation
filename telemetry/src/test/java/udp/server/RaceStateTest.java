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
}
