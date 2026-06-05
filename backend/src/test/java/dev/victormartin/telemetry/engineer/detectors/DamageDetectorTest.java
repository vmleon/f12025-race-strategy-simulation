package dev.victormartin.telemetry.engineer.detectors;

import java.util.Set;

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
    void announcesFrontWingReplacedOnRepair() {
        ObjectNode damaged = MAPPER.createObjectNode();
        damaged.put("fwDmg", 40);
        detector.evaluate(tickWith(damaged)); // arms the front wing at DAMAGED

        ObjectNode repaired = MAPPER.createObjectNode();
        repaired.put("fwDmg", 0);
        EngineerMessage msg = detector.evaluate(tickWith(repaired)).orElseThrow();

        assertEquals("Front wing replaced, good to go.", msg.text());
    }

    @Test
    void filtersAreUnrestricted() {
        assertEquals(Set.of(), detector.appliesToStates());
        assertEquals(Set.of(), detector.appliesToSessions());
    }

    @Test
    void belowMinorThresholdReturnsEmpty() {
        // A 5% scrape should NOT fire — minor threshold is 10%.
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 5);
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());
    }

    @Test
    void frontWingMinorCrossingFiresHigh() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 12);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.HIGH, msg.get().priority());
        assertEquals("Front wing has minor damage.", msg.get().text());
    }

    @Test
    void frontWingDamagedCrossingFiresHigh() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 35);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.HIGH, msg.get().priority());
        assertEquals("Front wing is damaged.", msg.get().text());
    }

    @Test
    void frontWingHeavyCrossingFiresImmediate() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 65);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.IMMEDIATE, msg.get().priority());
        assertEquals("Front wing is heavily damaged.", msg.get().text());
    }

    @Test
    void frontWingStaysArmedAndDoesNotReFire() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 12);
        assertTrue(detector.evaluate(tickWith(car)).isPresent()); // minor
        // Next tick, still 12%: no new message.
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());
        // Bump within the same tier (still under DAMAGED=30): no re-fire.
        car.put("fwDmg", 20);
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());
    }

    @Test
    void jumpStraightToHeavyFiresHeavyOnly() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 80);
        var first = detector.evaluate(tickWith(car));
        assertTrue(first.isPresent());
        assertEquals("Front wing is heavily damaged.", first.get().text());
        // Same value next tick — no extra message at lower tiers.
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());
    }

    @Test
    void upgradeFromMinorToDamagedToHeavy() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 12);
        var minor = detector.evaluate(tickWith(car));
        assertTrue(minor.isPresent());
        assertEquals("Front wing has minor damage.", minor.get().text());

        car.put("fwDmg", 35);
        var damaged = detector.evaluate(tickWith(car));
        assertTrue(damaged.isPresent());
        assertEquals("Front wing is damaged.", damaged.get().text());

        car.put("fwDmg", 70);
        var heavy = detector.evaluate(tickWith(car));
        assertTrue(heavy.isPresent());
        assertEquals("Front wing is heavily damaged.", heavy.get().text());

        // No re-fire while stuck at heavy.
        assertTrue(detector.evaluate(tickWith(car)).isEmpty());
    }

    @Test
    void repairResetsArmedStateAndAllowsReFire() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 70);
        assertTrue(detector.evaluate(tickWith(car)).isPresent()); // heavy

        // Pit stop — damage returns to 0; the front-wing repair is confirmed.
        car.put("fwDmg", 0);
        var repair = detector.evaluate(tickWith(car));
        assertTrue(repair.isPresent());
        assertEquals("Front wing replaced, good to go.", repair.get().text());

        // Later impact to 15% fires minor again.
        car.put("fwDmg", 15);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Front wing has minor damage.", msg.get().text());
    }

    @Test
    void rearWingFiresAllThreeTiers() {
        ObjectNode car = emptyPlayerCar();
        car.put("rwDmg", 12);
        var minor = detector.evaluate(tickWith(car));
        assertTrue(minor.isPresent());
        assertEquals("Rear wing has minor damage.", minor.get().text());

        car.put("rwDmg", 35);
        var damaged = detector.evaluate(tickWith(car));
        assertTrue(damaged.isPresent());
        assertEquals("Rear wing is damaged.", damaged.get().text());

        car.put("rwDmg", 70);
        var heavy = detector.evaluate(tickWith(car));
        assertTrue(heavy.isPresent());
        assertEquals("Rear wing is heavily damaged.", heavy.get().text());
    }

    @Test
    void floorFiresHeavy() {
        ObjectNode car = emptyPlayerCar();
        car.put("flDmg", 65);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Floor is heavily damaged.", msg.get().text());
        assertEquals(EngineerMessage.Priority.IMMEDIATE, msg.get().priority());
    }

    @Test
    void diffuserFiresMinor() {
        ObjectNode car = emptyPlayerCar();
        car.put("diffDmg", 12);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Diffuser has minor damage.", msg.get().text());
    }

    @Test
    void sidepodFiresMinor() {
        ObjectNode car = emptyPlayerCar();
        car.put("spDmg", 15);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Sidepod has minor damage.", msg.get().text());
    }

    @Test
    void partsHaveIndependentArmedState() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 65);
        assertTrue(detector.evaluate(tickWith(car)).isPresent()); // FW heavy
        // FW armed at heavy; RW fresh.
        car.put("rwDmg", 12);
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Rear wing has minor damage.", msg.get().text());
    }

    @Test
    void heavyCrossingWinsOverMinorAcrossParts() {
        // FW crosses minor (12), floor crosses heavy (70). Expect floor heavy first.
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 12);
        car.put("flDmg", 70);
        var first = detector.evaluate(tickWith(car));
        assertTrue(first.isPresent());
        assertEquals("Floor is heavily damaged.", first.get().text());
        // Next tick, same values: FW minor is still un-armed, fires now.
        var second = detector.evaluate(tickWith(car));
        assertTrue(second.isPresent());
        assertEquals("Front wing has minor damage.", second.get().text());
    }

    @Test
    void sameSeverityTieBreaksByFixedPartOrder() {
        // FW and RW both cross minor in the same tick. FW comes first in the fixed order.
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 12);
        car.put("rwDmg", 12);
        var first = detector.evaluate(tickWith(car));
        assertTrue(first.isPresent());
        assertEquals("Front wing has minor damage.", first.get().text());
        var second = detector.evaluate(tickWith(car));
        assertTrue(second.isPresent());
        assertEquals("Rear wing has minor damage.", second.get().text());
    }

    @Test
    void onSessionEndedClearsStateAndAllowsReArm() {
        ObjectNode car = emptyPlayerCar();
        car.put("fwDmg", 70);
        assertTrue(detector.evaluate(tickWith(car)).isPresent()); // heavy

        detector.onSessionEnded(SESSION_UID);
        detector.onSessionStarted(SESSION_UID, 0, 10);

        // Same damage value in the new session — fires fresh.
        var msg = detector.evaluate(tickWith(car));
        assertTrue(msg.isPresent());
        assertEquals("Front wing is heavily damaged.", msg.get().text());
    }
}
