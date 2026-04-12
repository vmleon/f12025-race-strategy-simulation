package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServiceSessionTransitionTest {

    private List<String> broadcasts;
    private RaceEngineerService service;

    @BeforeEach
    void setUp() {
        CircuitSafeZoneService safeZoneService = new CircuitSafeZoneService();
        safeZoneService.loadCircuits();
        broadcasts = new ArrayList<>();
        RaceEngineerWebSocketHandler handler = new RaceEngineerWebSocketHandler() {
            @Override
            public void broadcast(String jsonLine) {
                broadcasts.add(jsonLine);
            }
        };
        service = new RaceEngineerService(safeZoneService, handler);
    }

    /**
     * When a new session starts for the same track without the previous session ending,
     * the stale session must be removed so race-only detectors run correctly.
     */
    @Test
    void newSessionReplacesStaleSessionForSameTrack() {
        // Start a qualifying session (sessionType 5 = Q1) on Melbourne (trackId 0)
        service.onSessionStarted("quali-uid", 0, 5, 1, 1);
        broadcasts.clear();

        // Transition to race (sessionType 10) on same track WITHOUT calling onSessionEnded
        service.onSessionStarted("race-uid", 0, 10, 1, 1);
        broadcasts.clear();

        // Send a race state update — race-only detectors (e.g. periodic awareness) should run.
        // We verify by checking that position change detection works (race-only).
        // First update establishes baseline position.
        service.onStateUpdate(raceState(5, 0, 0));
        // Second update: position gained — this only fires if isRace()==true.
        service.onStateUpdate(raceState(4, 0, 0));
        // Flush queue through safe zones
        for (int i = 0; i < 4; i++) {
            service.onStateUpdate(raceState(4, 0, 0));
        }

        List<String> positionMessages = broadcasts.stream()
                .filter(b -> b.contains("is next"))
                .toList();
        assertFalse(positionMessages.isEmpty(),
                "Race-only position gain should fire after session transition, got: " + broadcasts);
    }

    @Test
    void normalSessionEndThenStartWorksCorrectly() {
        // Normal flow: start qualifying, end it, start race
        service.onSessionStarted("quali-uid", 0, 5, 1, 1);
        service.onSessionEnded("quali-uid");
        service.onSessionStarted("race-uid", 0, 10, 1, 1);
        broadcasts.clear();

        // Race detectors should work
        service.onStateUpdate(raceState(5, 0, 0));
        service.onStateUpdate(raceState(4, 0, 0));
        for (int i = 0; i < 4; i++) {
            service.onStateUpdate(raceState(4, 0, 0));
        }

        List<String> positionMessages = broadcasts.stream()
                .filter(b -> b.contains("is next"))
                .toList();
        assertFalse(positionMessages.isEmpty(),
                "Race position gain should fire after normal session transition");
    }

    /** Minimal race state JSON with a player at the given position on Melbourne (trackId 0). */
    private String raceState(int playerPos, int pitStatus, int pitCount) {
        return "{\"trackId\":0,\"totalLaps\":50,\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":["
                + "{\"ai\":false,\"pos\":" + playerPos
                + ",\"lap\":3,\"lapDist\":200.0,\"speed\":200,\"drsAllowed\":0,\"ersMode\":0"
                + ",\"tyre\":\"M\",\"tyreAge\":5,\"fuel\":50.0"
                + ",\"pitStatus\":" + pitStatus + ",\"pits\":" + pitCount
                + ",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0"
                + ",\"tyreWear\":[10,10,10,10],\"name\":\"Player\",\"resultStatus\":2}"
                + ",{\"ai\":true,\"pos\":" + (playerPos - 1)
                + ",\"lap\":3,\"lapDist\":310.0,\"speed\":200,\"drsAllowed\":0,\"ersMode\":0"
                + ",\"tyre\":\"M\",\"tyreAge\":5,\"fuel\":50.0"
                + ",\"pitStatus\":0,\"pits\":0,\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0"
                + ",\"tyreWear\":[10,10,10,10],\"name\":\"Norris\",\"resultStatus\":2}"
                + "]}";
    }
}
