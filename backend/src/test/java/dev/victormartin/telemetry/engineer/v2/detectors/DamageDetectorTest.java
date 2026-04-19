package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_UID = "abc";

    private DamageDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DamageDetector();
        detector.onSessionStarted(SESSION_UID, 0, 10);
    }

    private EngineerTick tickWith(ObjectNode playerCar) {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(playerCar);
        return new EngineerTick(
                System.currentTimeMillis(), SESSION_UID, 10, SessionKind.RACE,
                7, 1, 0, 5300,
                PitState.ON_TRACK, PitState.ON_TRACK,
                MAPPER.createObjectNode(), playerCar, cars,
                5, 1000f, 240, 0.9f, 0, 0, 0);
    }

    private ObjectNode emptyPlayerCar() {
        return MAPPER.createObjectNode();
    }

    @Test
    void noDamageFieldsReturnsEmpty() {
        assertTrue(detector.evaluate(tickWith(emptyPlayerCar())).isEmpty());
    }

    @Test
    void filtersAreUnrestricted() {
        assertEquals(Set.of(), detector.appliesToStates());
        assertEquals(Set.of(), detector.appliesToSessions());
    }
}
