package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackTrafficExitDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TRACK_LENGTH = 5300;
    private static final String SESSION_UID = "abc";

    private TrackTrafficExitDetector detector;

    @BeforeEach
    void setUp() {
        detector = new TrackTrafficExitDetector();
        detector.onSessionStarted(SESSION_UID, 0, 4);
    }

    private static ObjectNode car(int idx, String name, boolean ai, int pitStatus, float lapDist) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("idx", idx);
        n.put("name", name);
        n.put("ai", ai);
        n.put("pitStatus", pitStatus);
        n.put("lapDist", lapDist);
        return n;
    }

    private EngineerTick tickInPitExit(PitState previousPitState, float playerLapDist, JsonNode cars) {
        return new EngineerTick(
                System.currentTimeMillis(), SESSION_UID, 4, SessionKind.PRACTICE,
                7, 1, 0, TRACK_LENGTH,
                PitState.PIT_EXIT, previousPitState,
                MAPPER.createObjectNode(),  // state — unused by this detector
                MAPPER.createObjectNode(),  // playerCar — fields all promoted
                cars,
                19, playerLapDist, 60, 0.5f,
                1, 0, 0);
    }

    @Test
    void firesClearWhenNearestAiIsFarBehind() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", false, 1, 200f));
        cars.add(car(1, "ALONSO", true, 0, 4500f)); // 200 - 4500 + 5300 = 1000m → 18.2s
        EngineerTick tick = tickInPitExit(PitState.PIT_STOPPED, 200f, cars);

        Optional<EngineerMessage> msg = detector.evaluate(tick);

        assertTrue(msg.isPresent());
        assertEquals("Track is clear, go now.", msg.get().text());
        assertEquals(EngineerMessage.Priority.HIGH, msg.get().priority());
    }

    @Test
    void firesHoldWhenNearestAiCloseBehind() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", false, 1, 200f));
        cars.add(car(1, "OCON", true, 0, 0f)); // 200m gap → 3.6s — under 8s threshold
        EngineerTick tick = tickInPitExit(PitState.PIT_STOPPED, 200f, cars);

        Optional<EngineerMessage> msg = detector.evaluate(tick);

        assertTrue(msg.isPresent());
        assertEquals("Hold position, OCON about to pass.", msg.get().text());
    }

    @Test
    void doesNotFireInDeadZone() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", false, 1, 200f));
        // Player at lapDist=200, OCON at lapDist=4750. gap = (200-4750+5300)=750m → ~13.6s (between 8 and 15)
        cars.add(car(1, "OCON", true, 0, 4750f));
        EngineerTick tick = tickInPitExit(PitState.PIT_STOPPED, 200f, cars);

        assertTrue(detector.evaluate(tick).isEmpty());
    }

    @Test
    void doesNotFireWhenNoAiOnTrack() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", false, 1, 200f));
        cars.add(car(1, "OCON", true, 1, 50f)); // also in pit → ignored
        EngineerTick tick = tickInPitExit(PitState.PIT_STOPPED, 200f, cars);

        assertTrue(detector.evaluate(tick).isEmpty());
    }

    @Test
    void firesOnceAndIsSuppressedForRestOfStint() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", false, 1, 200f));
        cars.add(car(1, "ALONSO", true, 0, 4500f));

        EngineerTick first = tickInPitExit(PitState.PIT_STOPPED, 200f, cars);
        assertTrue(detector.evaluate(first).isPresent());

        // Subsequent tick still in PIT_EXIT (player still rolling out) — must not refire.
        EngineerTick second = tickInPitExit(PitState.PIT_EXIT, 280f, cars);
        assertTrue(detector.evaluate(second).isEmpty());
    }

    @Test
    void resetsAfterReturningToStoppedAndExitingAgain() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", false, 1, 200f));
        cars.add(car(1, "ALONSO", true, 0, 4500f));

        // First out-lap fires.
        assertTrue(detector.evaluate(tickInPitExit(PitState.PIT_STOPPED, 200f, cars)).isPresent());
        // Player drives a flying lap, pits again, stops, then exits again.
        // Second PIT_EXIT-after-PIT_STOPPED → flag resets, detector can fire again.
        Optional<EngineerMessage> second = detector.evaluate(tickInPitExit(PitState.PIT_STOPPED, 200f, cars));
        assertTrue(second.isPresent(), "should refire on a fresh out-lap");
    }

    @Test
    void onSessionEndedClearsState() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", false, 1, 200f));
        cars.add(car(1, "ALONSO", true, 0, 4500f));
        assertTrue(detector.evaluate(tickInPitExit(PitState.PIT_STOPPED, 200f, cars)).isPresent());

        detector.onSessionEnded(SESSION_UID);
        detector.onSessionStarted(SESSION_UID, 0, 4);

        assertTrue(detector.evaluate(tickInPitExit(PitState.PIT_STOPPED, 200f, cars)).isPresent(),
                "fresh session should be able to fire again");
    }

    @Test
    void appliesOnlyToPitExit() {
        assertEquals(java.util.Set.of(PitState.PIT_EXIT), detector.appliesToStates());
    }

    @Test
    void appliesToPracticeQualifyingAndSprintQualifying() {
        assertTrue(detector.appliesToSessions().contains(SessionKind.PRACTICE));
        assertTrue(detector.appliesToSessions().contains(SessionKind.QUALIFYING));
        assertTrue(detector.appliesToSessions().contains(SessionKind.SPRINT_QUALIFYING));
        assertFalse(detector.appliesToSessions().contains(SessionKind.RACE));
    }
}
