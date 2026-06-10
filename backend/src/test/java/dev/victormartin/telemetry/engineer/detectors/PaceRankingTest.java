package dev.victormartin.telemetry.engineer.detectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class PaceRankingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode cars(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void rankIsOneWhenPlayerFastest() {
        Map<Integer, Long> bests = new HashMap<>(Map.of(0, 80_000L, 1, 81_000L, 2, 82_000L));
        assertEquals(1, PaceRanking.rank(bests, 0));
    }

    @Test
    void rankCountsFasterCars() {
        Map<Integer, Long> bests = new HashMap<>(Map.of(0, 82_000L, 1, 80_000L, 2, 81_000L));
        assertEquals(3, PaceRanking.rank(bests, 0));
    }

    @Test
    void rankZeroWhenPlayerHasNoBest() {
        Map<Integer, Long> bests = new HashMap<>(Map.of(1, 80_000L));
        assertEquals(0, PaceRanking.rank(bests, 0));
        assertEquals(0, PaceRanking.rank(bests, -1));
    }

    @Test
    void updateBestsFoldsMinAndFindsPlayer() throws Exception {
        Map<Integer, Long> bests = new HashMap<>();
        int playerIdx = PaceRanking.updateBests(bests, cars(
                "[{\"idx\":0,\"ai\":false,\"lastLapTimeMs\":81000},"
                + "{\"idx\":1,\"ai\":true,\"lastLapTimeMs\":80000}]"));
        assertEquals(0, playerIdx);
        // A slower later lap must not replace the session best.
        PaceRanking.updateBests(bests, cars("[{\"idx\":0,\"ai\":false,\"lastLapTimeMs\":85000}]"));
        assertEquals(81_000L, bests.get(0));
        // A faster lap replaces it.
        PaceRanking.updateBests(bests, cars("[{\"idx\":0,\"ai\":false,\"lastLapTimeMs\":79000}]"));
        assertEquals(79_000L, bests.get(0));
    }

    @Test
    void updateBestsIgnoresZeroLaps() throws Exception {
        Map<Integer, Long> bests = new HashMap<>();
        PaceRanking.updateBests(bests, cars("[{\"idx\":0,\"ai\":false,\"lastLapTimeMs\":0}]"));
        assertTrue(bests.isEmpty());
    }
}
