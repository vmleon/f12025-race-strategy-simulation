package dev.victormartin.telemetry.engineer.detectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.SessionKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YellowFlagDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UID = "uid";

    private YellowFlagDetector detector;

    @BeforeEach
    void setUp() {
        detector = new YellowFlagDetector();
        detector.onSessionStarted(UID, 0, 10);
    }

    private EngineerTick tick(int playerSector, int... yellowSectors) {
        ObjectNode state = MAPPER.createObjectNode();
        ArrayNode ys = state.putArray("yellowSectors");
        for (int s : yellowSectors) ys.add(s);
        ObjectNode player = MAPPER.createObjectNode();
        player.put("sector", playerSector);
        return new EngineerTick(
                1_000L, UID, 10, SessionKind.RACE,
                0, 5, 50, 5000,
                PitState.ON_TRACK, PitState.ON_TRACK,
                state, player, MAPPER.createArrayNode(),
                3, 100f, 240, 0.9f, 0, 0, 0);
    }

    @Test
    void announcesYellowInSectorAhead() {
        // Player in sector 0, yellow in sector 1 (the sector ahead).
        Optional<EngineerMessage> msg = detector.evaluate(tick(0, 1));
        assertTrue(msg.isPresent());
        assertEquals("Yellow flag in sector 2.", msg.get().text());
    }

    @Test
    void announcesYellowInCurrentSector() {
        Optional<EngineerMessage> msg = detector.evaluate(tick(0, 0));
        assertTrue(msg.isPresent());
        assertEquals("Yellow flag in sector 1.", msg.get().text());
    }

    @Test
    void silentForYellowBehindPlayer() {
        // Player in sector 0; sector 2 is behind (not current or ahead).
        assertTrue(detector.evaluate(tick(0, 2)).isEmpty());
    }

    @Test
    void deduplicatesWhileYellowPersists() {
        assertTrue(detector.evaluate(tick(0, 1)).isPresent());
        assertTrue(detector.evaluate(tick(0, 1)).isEmpty());
    }

    @Test
    void reannouncesAfterClearAndReraise() {
        assertTrue(detector.evaluate(tick(0, 1)).isPresent());
        assertTrue(detector.evaluate(tick(0)).isEmpty());        // cleared
        assertTrue(detector.evaluate(tick(0, 1)).isPresent());   // raised again
    }
}
