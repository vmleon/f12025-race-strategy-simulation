package dev.victormartin.telemetry.engineer.detectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.SessionKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TyreConditionDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UID = "abc";

    private TyreConditionDetector detector;

    @BeforeEach
    void setUp() {
        detector = new TyreConditionDetector();
        detector.onSessionStarted(UID, 0, 10);
    }

    private EngineerTick tick(int tyreAge, String compound) {
        ObjectNode player = MAPPER.createObjectNode();
        player.put("tyreAge", tyreAge);
        player.put("tyre", compound);
        return new EngineerTick(
                1_000L, UID, 10, SessionKind.RACE,
                0, 5, 50, 5300,
                PitState.ON_TRACK, PitState.ON_TRACK,
                MAPPER.createObjectNode(), player, MAPPER.createArrayNode(),
                5, 100f, 240, 0.9f, 0, 0, 0);
    }

    @Test
    void outLapMessageDropsCopyAndRewords() {
        detector.evaluate(tick(10, "M"));            // establish previous tyre age
        Optional<EngineerMessage> msg = detector.evaluate(tick(1, "M")); // fresh tyres fitted

        assertTrue(msg.isPresent());
        assertEquals("New mediums on, easy on the exit.", msg.get().text());
        assertFalse(msg.get().text().contains("Copy"));
    }
}
