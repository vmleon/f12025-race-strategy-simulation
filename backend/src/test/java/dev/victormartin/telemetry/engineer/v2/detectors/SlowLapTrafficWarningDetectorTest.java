package dev.victormartin.telemetry.engineer.v2.detectors;

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

class SlowLapTrafficWarningDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TRACK_LENGTH = 5300;
    private static final String SESSION_UID = "abc";

    private SlowLapTrafficWarningDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SlowLapTrafficWarningDetector();
        detector.onSessionStarted(SESSION_UID, 0, 4);
    }

    private static ObjectNode car(int idx, String name, int pos, int lap, float lapDist) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("idx", idx);
        n.put("name", name);
        n.put("pos", pos);
        n.put("lap", lap);
        n.put("lapDist", lapDist);
        return n;
    }

    private EngineerTick tickOnTrack(int playerPos, int playerLap, float playerLapDist,
                                      float playerThrottle, JsonNode cars, long ts) {
        return new EngineerTick(
                ts, SESSION_UID, 4, SessionKind.PRACTICE,
                7, playerLap, 0, TRACK_LENGTH,
                PitState.ON_TRACK, PitState.ON_TRACK,
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                cars,
                playerPos, playerLapDist, 240, playerThrottle,
                0, 0, 0);
    }

    @Test
    void buffersThrottleAndDoesNotFireUntilThreeSamples() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 950f)); // 50m behind, very close

        // Two ticks with low throttle aren't enough yet (buffer needs 3).
        long t = 1000L;
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1000f, 0.1f, cars, t)).isEmpty());
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1010f, 0.1f, cars, t + 1000)).isEmpty());
    }

    @Test
    void firesWhenSlowAndCarBehindClosing() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 800f)); // 200m → 3.6s

        long t = 1000L;
        // Prime throttle buffer (3 low ticks).
        detector.evaluate(tickOnTrack(5, 1, 800f, 0.1f, cars, t));
        detector.evaluate(tickOnTrack(5, 1, 900f, 0.1f, cars, t + 1000));
        // First "real" tick to seed previousGapBehind.
        detector.evaluate(tickOnTrack(5, 1, 1000f, 0.1f, cars, t + 2000));
        // Now actually closing: gap shrinks from 200m to 100m.
        ArrayNode cars2 = MAPPER.createArrayNode();
        cars2.add(car(0, "Player", 5, 1, 1050f));
        cars2.add(car(1, "RIVAL", 6, 1, 950f)); // 100m → 1.8s
        var msg = detector.evaluate(tickOnTrack(5, 1, 1050f, 0.1f, cars2, t + 3000));

        assertTrue(msg.isPresent(), "should fire when slow and rival closing");
        assertEquals("RIVAL closing fast behind, let them through.", msg.get().text());
        assertEquals(EngineerMessage.Priority.HIGH, msg.get().priority());
    }

    @Test
    void doesNotFireOnPushLap() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 800f));

        long t = 1000L;
        // High throttle = push lap.
        detector.evaluate(tickOnTrack(5, 1, 800f, 0.95f, cars, t));
        detector.evaluate(tickOnTrack(5, 1, 900f, 0.95f, cars, t + 1000));
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1000f, 0.95f, cars, t + 2000)).isEmpty());
    }

    @Test
    void doesNotFireWhenGapTooLarge() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 0f)); // 1000m → 18.2s

        long t = 1000L;
        detector.evaluate(tickOnTrack(5, 1, 800f, 0.1f, cars, t));
        detector.evaluate(tickOnTrack(5, 1, 900f, 0.1f, cars, t + 1000));
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1000f, 0.1f, cars, t + 2000)).isEmpty());
    }

    @Test
    void doesNotFireWhenRivalFallingBack() {
        // Rival close but gap growing → not "closing".
        long t = 1000L;
        // Prime buffer, seed gap at small value.
        ArrayNode close = MAPPER.createArrayNode();
        close.add(car(0, "Player", 5, 1, 1000f));
        close.add(car(1, "RIVAL", 6, 1, 950f));
        detector.evaluate(tickOnTrack(5, 1, 1000f, 0.1f, close, t));
        detector.evaluate(tickOnTrack(5, 1, 1050f, 0.1f, close, t + 1000));
        detector.evaluate(tickOnTrack(5, 1, 1100f, 0.1f, close, t + 2000));
        // Now gap grows from ~50m to ~150m — rival falling back.
        ArrayNode further = MAPPER.createArrayNode();
        further.add(car(0, "Player", 5, 1, 1300f));
        further.add(car(1, "RIVAL", 6, 1, 1150f));
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1300f, 0.1f, further, t + 3000)).isEmpty());
    }

    @Test
    void respectsCooldown() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 800f));

        long t = 1000L;
        detector.evaluate(tickOnTrack(5, 1, 800f, 0.1f, cars, t));
        detector.evaluate(tickOnTrack(5, 1, 900f, 0.1f, cars, t + 1000));
        detector.evaluate(tickOnTrack(5, 1, 1000f, 0.1f, cars, t + 2000));
        // Closing tick → fires.
        ArrayNode cars2 = MAPPER.createArrayNode();
        cars2.add(car(0, "Player", 5, 1, 1050f));
        cars2.add(car(1, "RIVAL", 6, 1, 950f));
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1050f, 0.1f, cars2, t + 3000)).isPresent());
        // 5 seconds later, still closing — must be suppressed by cooldown.
        ArrayNode cars3 = MAPPER.createArrayNode();
        cars3.add(car(0, "Player", 5, 1, 1100f));
        cars3.add(car(1, "RIVAL", 6, 1, 1050f));
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1100f, 0.1f, cars3, t + 8000)).isEmpty());
    }

    @Test
    void appliesOnlyToOnTrack() {
        assertEquals(java.util.Set.of(PitState.ON_TRACK), detector.appliesToStates());
    }

    @Test
    void doesNotApplyToRace() {
        // Slow-lap-traffic warning is a quali/practice politeness signal — pointless in a race.
        assertFalse(detector.appliesToSessions().contains(SessionKind.RACE));
    }
}
