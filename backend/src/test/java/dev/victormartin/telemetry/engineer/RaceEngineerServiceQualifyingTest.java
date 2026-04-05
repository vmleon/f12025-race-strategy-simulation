package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServiceQualifyingTest {

    private CircuitSafeZoneService safeZoneService;
    private List<String> broadcasts;
    private RaceEngineerService service;

    @BeforeEach
    void setUp() {
        safeZoneService = new CircuitSafeZoneService();
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

    private void startQualifying() {
        service.onSessionStarted("test-session", 0, 7 /* Q3 */, 1, 1);
    }

    private void startRace() {
        service.onSessionStarted("test-session", 0, 10 /* Race */, 1, 1);
    }

    private String stateJson(int playerPos, int playerLap, int sector, long sec1Ms, long sec2Ms,
                              long playerLastLapMs, long otherLastLapMs) {
        return "{\"trackId\":0,\"totalLaps\":3,\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":["
                + "{\"ai\":false,\"pos\":" + playerPos + ",\"lap\":" + playerLap
                + ",\"sector\":" + sector + ",\"lastSectorMs\":[" + sec1Ms + "," + sec2Ms + ",0]"
                + ",\"lastLapTimeMs\":" + playerLastLapMs
                + ",\"lapDist\":200.0,\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"S\",\"tyreAge\":2,\"fuel\":40.0"
                + ",\"pitStatus\":0,\"pits\":0,\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0,\"name\":\"Player\"}"
                + ",{\"ai\":true,\"pos\":1,\"lap\":" + playerLap
                + ",\"sector\":0,\"lastSectorMs\":[0,0,0]"
                + ",\"lastLapTimeMs\":" + otherLastLapMs
                + ",\"lapDist\":5000.0,\"drsAllowed\":0,\"ersMode\":0,\"tyre\":\"S\",\"tyreAge\":2,\"fuel\":40.0"
                + ",\"pitStatus\":0,\"pits\":0,\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0,\"name\":\"Verstappen\"}"
                + "]}";
    }

    private List<String> broadcastsContaining(String s) {
        return broadcasts.stream().filter(b -> b.contains(s)).toList();
    }

    /** Alternate lap distance to cycle safe zones and flush NORMAL messages. */
    private void flushTick(int playerPos, int playerLap, int sector, long sec1Ms, long sec2Ms,
                            long playerLastLapMs, long otherLastLapMs) {
        for (int i = 0; i < 4; i++) {
            service.onStateUpdate(stateJson(playerPos, playerLap, sector, sec1Ms, sec2Ms, playerLastLapMs, otherLastLapMs));
            service.onStateUpdate(stateJson(playerPos, playerLap, sector, sec1Ms, sec2Ms, playerLastLapMs, otherLastLapMs)
                    .replace("\"lapDist\":200.0", "\"lapDist\":1200.0"));
        }
    }

    // -- session-type gating ---------------------------------------------------

    @Test
    void qualifyingDoesNotEmitRaceSpecificMessages() {
        startQualifying();
        // Tick through laps that would trigger race-specific messages (10 laps to go etc.)
        // totalLaps=3 with currentLap advancing to 3 (last lap) would say "Last lap" in race.
        flushTick(5, 2, 0, 25000, 30000, 90000, 88000);
        flushTick(5, 3, 0, 25000, 30000, 89000, 88000);

        assertTrue(broadcastsContaining("Last lap").isEmpty(),
                "Qualifying should NOT say 'Last lap', got: " + broadcasts);
        assertTrue(broadcastsContaining("Bring it home").isEmpty(),
                "Qualifying should NOT say 'Bring it home', got: " + broadcasts);
        assertTrue(broadcastsContaining("Lost a place").isEmpty(),
                "Qualifying should NOT emit loss messages, got: " + broadcasts);
        assertTrue(broadcastsContaining("Box window").isEmpty(),
                "Qualifying should NOT emit pit window messages, got: " + broadcasts);
    }

    @Test
    void raceStillEmitsLapCountdown() {
        startRace();
        // totalLaps=3, currentLap=3 → last lap.
        service.onStateUpdate(stateJson(5, 1, 0, 0, 0, 0, 0));
        service.onStateUpdate(stateJson(5, 2, 0, 0, 0, 90000, 88000));
        service.onStateUpdate(stateJson(5, 3, 0, 0, 0, 89000, 88000));

        assertFalse(broadcastsContaining("Last lap").isEmpty(),
                "Race should emit 'Last lap', got: " + broadcasts);
    }

    // -- sector delta ----------------------------------------------------------

    @Test
    void sectorDeltaEmitsPurpleOnFirstCompletion() {
        startQualifying();
        // Start in sector 0. Transition to sector 1 → sector 0 is completed with time 25000ms.
        service.onStateUpdate(stateJson(5, 1, 0, 0, 0, 0, 0)); // initial state, prevSector=-1 → 0, no completion
        flushTick(5, 1, 1, 25000, 0, 0, 0);

        List<String> msgs = broadcastsContaining("Purple sector 1");
        assertFalse(msgs.isEmpty(), "First sector 1 should be purple, got: " + broadcasts);
        assertTrue(msgs.get(0).contains("25.000"), "Should report time 25.000: " + msgs.get(0));
    }

    @Test
    void sectorDeltaEmitsYellowWhenSlower() {
        startQualifying();
        // First sector 1 time: 25.000 (purple, sets best).
        service.onStateUpdate(stateJson(5, 1, 0, 0, 0, 0, 0));
        flushTick(5, 1, 1, 25000, 0, 0, 0);
        int beforeSecond = broadcastsContaining("Purple sector 1").size();

        // Player went round; back to sector 0, then 0→1 again with slower time 25.500.
        flushTick(5, 2, 0, 25500, 30000, 0, 0);
        flushTick(5, 2, 1, 25500, 0, 0, 0);

        assertEquals(beforeSecond, broadcastsContaining("Purple sector 1").size(),
                "No new purple, got: " + broadcasts);
        assertFalse(broadcastsContaining("Sector 1 down").isEmpty(),
                "Should emit 'down' for slower sector, got: " + broadcasts);
        assertTrue(broadcastsContaining("Sector 1 down").get(0).contains("0.500"),
                "Delta should be 0.500: " + broadcasts);
    }

    // -- lap complete ----------------------------------------------------------

    @Test
    void lapCompleteEmitsProvisionalPoleWhenFastest() {
        startQualifying();
        // Player sets the fastest lap: 88000ms, Verstappen 90000ms.
        service.onStateUpdate(stateJson(5, 1, 0, 0, 0, 0, 0));
        flushTick(1, 2, 0, 25000, 30000, 88000, 90000);

        List<String> msgs = broadcastsContaining("Provisional pole");
        assertFalse(msgs.isEmpty(), "Expected provisional pole, got: " + broadcasts);
        assertTrue(msgs.get(0).contains("1:28.000"), "Should format as 1:28.000: " + msgs.get(0));
    }

    @Test
    void lapCompleteReportsGapToPole() {
        startQualifying();
        // Player in P5 runs 89.500, leader has 88.000.
        service.onStateUpdate(stateJson(5, 1, 0, 0, 0, 0, 0));
        flushTick(5, 2, 0, 25000, 30000, 89500, 88000);

        List<String> msgs = broadcastsContaining("off pole");
        assertFalse(msgs.isEmpty(), "Expected gap-to-pole message, got: " + broadcasts);
        String msg = msgs.get(0);
        assertTrue(msg.contains("P5"), "Should mention P5: " + msg);
        assertTrue(msg.contains("1.500"), "Gap should be 1.500: " + msg);
    }
}
