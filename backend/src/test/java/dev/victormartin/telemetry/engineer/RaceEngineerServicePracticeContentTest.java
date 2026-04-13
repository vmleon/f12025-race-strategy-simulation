package dev.victormartin.telemetry.engineer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaceEngineerServicePracticeContentTest {

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
        // sessionType 2 = FP2 (practice)
        service.onSessionStarted("test-session", 0, 2, 1, 1);
    }

    /** Basic practice state JSON. sector values drive sector-transition detection. */
    private String stateJson(int playerLap, int playerSector, long s1Ms, long s2Ms,
                               float wearFL, float fuel, int pitStatus, float playerLapDist,
                               int rivalSector, long rivalS1Ms, long rivalS2Ms,
                               float rivalLapDist, int rivalPitStatus) {
        return "{\"trackId\":0,\"totalLaps\":50,\"trackLength\":5303,\"weather\":0,\"safetyCarStatus\":0,\"cars\":["
                + "{\"idx\":0,\"ai\":false,\"pos\":5,\"lap\":" + playerLap + ",\"sector\":" + playerSector
                + ",\"lapDist\":" + playerLapDist + ",\"drsAllowed\":0,\"ersMode\":0"
                + ",\"tyre\":\"M\",\"tyreAge\":5,\"fuel\":" + fuel + ",\"pitStatus\":" + pitStatus + ",\"pits\":0"
                + ",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0"
                + ",\"tyreWear\":[10.0,10.0," + wearFL + ",10.0]"
                + ",\"lastSectorMs\":[" + s1Ms + "," + s2Ms + ",0],\"lastLapTimeMs\":0"
                + ",\"name\":\"Player\"}"
                + ",{\"idx\":1,\"ai\":true,\"pos\":4,\"lap\":" + playerLap + ",\"sector\":" + rivalSector
                + ",\"lapDist\":" + rivalLapDist + ",\"drsAllowed\":0,\"ersMode\":0"
                + ",\"tyre\":\"M\",\"tyreAge\":5,\"fuel\":50.0,\"pitStatus\":" + rivalPitStatus + ",\"pits\":0"
                + ",\"pen\":0,\"unservedDT\":0,\"unservedSG\":0,\"warnings\":0"
                + ",\"tyreWear\":[10.0,10.0,10.0,10.0]"
                + ",\"lastSectorMs\":[" + rivalS1Ms + "," + rivalS2Ms + ",0],\"lastLapTimeMs\":0"
                + ",\"name\":\"Piastri\"}"
                + "]}";
    }

    private List<String> broadcastsContaining(String s) {
        return broadcasts.stream().filter(b -> b.contains(s)).toList();
    }

    private void flush(String json) {
        for (int i = 0; i < 6; i++) {
            service.onStateUpdate(json);
            service.onStateUpdate(json.replace("\"lapDist\":200.0", "\"lapDist\":1200.0"));
        }
    }

    @Test
    void tyreFuelSummaryFiresEveryFourLaps() {
        // wearFL=20, wearFR fixed at 10 in stateJson → frontAvg = (20+10)/2 = 15
        // rear wear all 10 → rearAvg = 10
        flush(stateJson(6, 0, 28000, 61000, 20f, 45.0f, 0, 200.0f, 0, 28500, 62000, 3000f, 0));
        assertEquals(1, broadcastsContaining("Fronts at 15% wear, rears at 10%, fuel 45 kilograms.").size(),
                "Tyre/fuel summary should fire, got: " + broadcasts);
    }

    @Test
    void sectorComparisonFiresWhenRivalFaster() {
        // Set up rival's best sectors first (rival transitions 0->1 with S1=27500)
        flush(stateJson(3, 0, 28000, 0, 10f, 50.0f, 0, 200.0f, 1, 27500, 0, 3000f, 0));
        broadcasts.clear();

        // Player transitions 0->1 with S1=28000. Rival best is 27500. Delta 500ms > 150ms → fire.
        flush(stateJson(5, 1, 28000, 62000, 10f, 48.0f, 0, 200.0f, 2, 27500, 60000, 3000f, 0));
        assertEquals(1, broadcastsContaining("Piastri is 0.5 seconds faster in Sector 1").size(),
                "Sector comparison should fire, got: " + broadcasts);
    }

    @Test
    void trackTrafficClearFiresInPitLane() {
        // Player in pit lane (pitStatus=1), rival far away on track
        // gap = (200 - 3000 + 5303) / 55 = 2503/55 ≈ 45.5s > 15 → "clear"
        flush(stateJson(5, 0, 28000, 61000, 10f, 50.0f, 1, 200.0f, 0, 28000, 60000, 3000f, 0));
        assertEquals(1, broadcastsContaining("Track is clear, go now.").size(),
                "Clear-track message should fire, got: " + broadcasts);
    }

    @Test
    void trackTrafficHoldFiresWhenCarApproaching() {
        // Player in pit lane, rival close: gap = (200 - 5100 + 5303)/55 ≈ 7.3s < 8 → "hold"
        flush(stateJson(5, 0, 28000, 61000, 10f, 50.0f, 1, 200.0f, 0, 28000, 60000, 5100f, 0));
        assertEquals(1, broadcastsContaining("Hold position, Piastri about to pass.").size(),
                "Hold message should fire, got: " + broadcasts);
    }

    @Test
    void trackTrafficNotRepeatedWithinSameStint() {
        flush(stateJson(5, 0, 28000, 61000, 10f, 50.0f, 1, 200.0f, 0, 28000, 60000, 3000f, 0));
        int initial = broadcastsContaining("Track is clear").size();
        assertEquals(1, initial);

        // Stay in pit lane — should NOT fire again
        flush(stateJson(5, 0, 28000, 61000, 10f, 50.0f, 1, 200.0f, 0, 28000, 60000, 3000f, 0));
        assertEquals(1, broadcastsContaining("Track is clear").size(),
                "Should not repeat track-traffic message in same stint, got: " + broadcasts);
    }
}
