package dev.victormartin.telemetry.engineer.detectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.SessionKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeSectorComparisonDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_UID = "abc";

    private PracticeSectorComparisonDetector detector;

    @BeforeEach
    void setUp() {
        detector = new PracticeSectorComparisonDetector();
        detector.onSessionStarted(SESSION_UID, 0, 4);
    }

    private static ObjectNode car(int idx, String name, boolean ai, int sector,
                                   long s1Ms, long s2Ms) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("idx", idx);
        n.put("name", name);
        n.put("ai", ai);
        n.put("sector", sector);
        ArrayNode sectorMs = MAPPER.createArrayNode();
        sectorMs.add(s1Ms);
        sectorMs.add(s2Ms);
        sectorMs.add(0L);
        n.set("lastSectorMs", sectorMs);
        return n;
    }

    private EngineerTick tick(int lap, JsonNode cars) {
        return new EngineerTick(
                System.currentTimeMillis(), SESSION_UID, 4, SessionKind.PRACTICE,
                7, lap, 0, 5300,
                PitState.ON_TRACK, PitState.ON_TRACK,
                MAPPER.createObjectNode(), MAPPER.createObjectNode(), cars,
                5, 1000f, 240, 0.9f, 0, 0, 0);
    }

    @Test
    void firesWhenAiBeatsPlayerByOver100ms() {
        // Tick 1: both cars in S0. Establish prevSector.
        ArrayNode setup = MAPPER.createArrayNode();
        setup.add(car(0, "Player", false, 0, 0, 0));
        setup.add(car(1, "RIVAL", true, 0, 0, 0));
        detector.evaluate(tick(2, setup));

        // Tick 2: RIVAL completes S1 in 23.000s.
        ArrayNode r2 = MAPPER.createArrayNode();
        r2.add(car(0, "Player", false, 0, 0, 0));
        r2.add(car(1, "RIVAL", true, 1, 23000L, 0));
        detector.evaluate(tick(2, r2));

        // Tick 3: Player completes S1 in 23.150s — 150ms slower than RIVAL.
        ArrayNode p3 = MAPPER.createArrayNode();
        p3.add(car(0, "Player", false, 1, 23150L, 0));
        p3.add(car(1, "RIVAL", true, 1, 23000L, 0));
        var msg = detector.evaluate(tick(2, p3));

        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.NORMAL, msg.get().priority());
        assertEquals("RIVAL is 0.2 seconds faster in Sector 1.", msg.get().text());
    }

    @Test
    void doesNotFireWhenDeficitUnder100ms() {
        ArrayNode setup = MAPPER.createArrayNode();
        setup.add(car(0, "Player", false, 0, 0, 0));
        setup.add(car(1, "RIVAL", true, 0, 0, 0));
        detector.evaluate(tick(2, setup));

        ArrayNode r2 = MAPPER.createArrayNode();
        r2.add(car(0, "Player", false, 0, 0, 0));
        r2.add(car(1, "RIVAL", true, 1, 23000L, 0));
        detector.evaluate(tick(2, r2));

        ArrayNode p3 = MAPPER.createArrayNode();
        p3.add(car(0, "Player", false, 1, 23080L, 0)); // 80ms behind, under threshold
        p3.add(car(1, "RIVAL", true, 1, 23000L, 0));
        assertTrue(detector.evaluate(tick(2, p3)).isEmpty());
    }

    @Test
    void doesNotFireWhenPlayerIsFaster() {
        ArrayNode setup = MAPPER.createArrayNode();
        setup.add(car(0, "Player", false, 0, 0, 0));
        setup.add(car(1, "RIVAL", true, 0, 0, 0));
        detector.evaluate(tick(2, setup));

        ArrayNode r2 = MAPPER.createArrayNode();
        r2.add(car(0, "Player", false, 0, 0, 0));
        r2.add(car(1, "RIVAL", true, 1, 23200L, 0));
        detector.evaluate(tick(2, r2));

        ArrayNode p3 = MAPPER.createArrayNode();
        p3.add(car(0, "Player", false, 1, 23000L, 0)); // player faster
        p3.add(car(1, "RIVAL", true, 1, 23200L, 0));
        assertTrue(detector.evaluate(tick(2, p3)).isEmpty());
    }

    @Test
    void enforcesOneLapCooldown() {
        // Setup with rival fastest.
        ArrayNode setup = MAPPER.createArrayNode();
        setup.add(car(0, "Player", false, 0, 0, 0));
        setup.add(car(1, "RIVAL", true, 0, 0, 0));
        detector.evaluate(tick(2, setup));
        ArrayNode r = MAPPER.createArrayNode();
        r.add(car(0, "Player", false, 0, 0, 0));
        r.add(car(1, "RIVAL", true, 1, 23000L, 0));
        detector.evaluate(tick(2, r));

        // Player completes S1 at lap 2 — fires.
        ArrayNode p2 = MAPPER.createArrayNode();
        p2.add(car(0, "Player", false, 1, 23200L, 0));
        p2.add(car(1, "RIVAL", true, 1, 23000L, 0));
        assertTrue(detector.evaluate(tick(2, p2)).isPresent());

        // Same lap, another sector transition — would re-fire if no cooldown.
        // Player rolls back to S0 (start of lap 3 wraps prev=2 → cur=0; for the
        // test simply assert second call same lap is empty since playerBest now set).
        ArrayNode p2b = MAPPER.createArrayNode();
        p2b.add(car(0, "Player", false, 1, 23150L, 0)); // worse than 23200? no, faster
        p2b.add(car(1, "RIVAL", true, 1, 23000L, 0));
        // Player did improve to 23150 → new personal best. With cooldown=1, can't refire same lap.
        assertTrue(detector.evaluate(tick(2, p2b)).isEmpty());
    }

    @Test
    void appliesOnlyToPracticeAndOnTrack() {
        assertEquals(java.util.Set.of(PitState.ON_TRACK), detector.appliesToStates());
        assertEquals(java.util.Set.of(SessionKind.PRACTICE), detector.appliesToSessions());
    }
}
