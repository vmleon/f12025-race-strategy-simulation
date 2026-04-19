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

    @Test
    void frontWingLightCrossingFiresHighPriority() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 4);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.HIGH, msg.get().priority());
        assertEquals("Front wing has light damage.", msg.get().text());
    }

    @Test
    void frontWingStaysArmedAndDoesNotReFire() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 4);
        assertTrue(detector.evaluate(tickWith(car)).isPresent());
        // Next tick, still 4%: no new message.
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());
        // Bump to 5% — still within the same tier, no re-fire.
        car.put("fwDmg", 5);
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());
    }

    @Test
    void frontWingSevereCrossingFiresImmediate() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 8);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.IMMEDIATE, msg.get().priority());
        assertEquals("Front wing is heavily damaged.", msg.get().text());
    }

    @Test
    void jumpPastLightStraightToSevereFiresSevereOnly() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 12);
        var first = detector.evaluate(tickWith(car));
        assertTrue(first.isPresent());
        assertEquals("Front wing is heavily damaged.", first.get().text());
        // Same value next tick — no second "light" message.
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());
    }

    @Test
    void upgradeFromLightToSevereFiresSevere() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 4);
        assertTrue(detector.evaluate(tickWith(car)).isPresent()); // light
        car.put("fwDmg", 9);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Front wing is heavily damaged.", msg.get().text());
        // And no re-fire while stuck severe.
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());
    }

    @Test
    void repairResetsArmedStateAndAllowsReFire() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 12);
        assertTrue(detector.evaluate(tickWith(car)).isPresent()); // severe

        // Pit stop — damage returns to 0.
        car.put("fwDmg", 0);
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());

        // Later impact to 4% fires light again.
        car.put("fwDmg", 4);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Front wing has light damage.", msg.get().text());
    }

    @Test
    void rearWingFiresLightAndSevere() {
        ObjectNode car = emptyPlayerCar();
        car.put("rwDmg", 4);
        var light = detector.evaluate(tickWith(car));
        assertTrue(light.isPresent());
        assertEquals("Rear wing has light damage.", light.get().text());

        car.put("rwDmg", 10);
        var severe = detector.evaluate(tickWith(car));
        assertTrue(severe.isPresent());
        assertEquals("Rear wing is heavily damaged.", severe.get().text());
    }

    @Test
    void floorFiresSevere() {
        ObjectNode car = emptyPlayerCar();
        car.put("flDmg", 15);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Floor is heavily damaged.", msg.get().text());
        assertEquals(EngineerMessage.Priority.IMMEDIATE, msg.get().priority());
    }

    @Test
    void diffuserFiresLight() {
        ObjectNode car = emptyPlayerCar();
        car.put("diffDmg", 3);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Diffuser has light damage.", msg.get().text());
    }

    @Test
    void sidepodFiresLight() {
        ObjectNode car = emptyPlayerCar();
        car.put("spDmg", 6);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Sidepod has light damage.", msg.get().text());
    }

    @Test
    void partsHaveIndependentArmedState() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 10);
        assertTrue(detector.evaluate(tickWith(car)).isPresent()); // FW severe
        // FW armed at severe; RW fresh.
        car.put("rwDmg", 4);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Rear wing has light damage.", msg.get().text());
    }

    @Test
    void severeCrossingWinsOverEarlierLightCrossing() {
        // FW crosses light (4), floor crosses severe (15). Expect "Floor is heavily damaged."
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 4);
        car.put("flDmg", 15);
        var first = detector.evaluate(tickWith(car));
        assertTrue(first.isPresent());
        assertEquals("Floor is heavily damaged.", first.get().text());
        // Next tick, same values: FW light is still un-armed, fires now.
        var second = detector.evaluate(tickWith(car));
        assertTrue(second.isPresent());
        assertEquals("Front wing has light damage.", second.get().text());
    }

    @Test
    void sameSeverityTieBreaksByFixedPartOrder() {
        // FW and RW both cross light in the same tick. FW comes first in the fixed order.
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 4);
        car.put("rwDmg", 4);
        var first = detector.evaluate(tickWith(car));
        assertTrue(first.isPresent());
        assertEquals("Front wing has light damage.", first.get().text());
        var second = detector.evaluate(tickWith(car));
        assertTrue(second.isPresent());
        assertEquals("Rear wing has light damage.", second.get().text());
    }
}
