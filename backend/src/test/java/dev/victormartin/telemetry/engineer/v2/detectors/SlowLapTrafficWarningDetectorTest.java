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
    private static final int PRIME_SAMPLES = 12; // matches SAMPLE_BUFFER_SIZE

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
        n.put("pitStatus", 0);
        n.put("speed", 240);
        return n;
    }

    private EngineerTick tickOnTrack(int playerPos, int playerLap, float playerLapDist,
                                      float playerThrottle, int playerSpeedKmh,
                                      JsonNode cars, long ts) {
        return new EngineerTick(
                ts, SESSION_UID, 4, SessionKind.PRACTICE,
                7, playerLap, 0, TRACK_LENGTH,
                PitState.ON_TRACK, PitState.ON_TRACK,
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                cars,
                playerPos, playerLapDist, playerSpeedKmh, playerThrottle,
                0, 0, 0);
    }

    /** Slowly-rolling outlap profile (low throttle, low speed). */
    private void primeOutlapBuffer(JsonNode cars, long startTs) {
        for (int i = 0; i < PRIME_SAMPLES; i++) {
            detector.evaluate(tickOnTrack(5, 1, 800f + i * 10, 0.15f, 110, cars, startTs + i * 1000L));
        }
    }

    @Test
    void buffersSamplesAndDoesNotFireUntilFull() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 950f));

        long t = 1000L;
        // A handful of low-throttle, low-speed samples are not enough — buffer needs 12.
        for (int i = 0; i < PRIME_SAMPLES - 1; i++) {
            assertTrue(detector.evaluate(tickOnTrack(5, 1, 1000f + i, 0.15f, 110, cars, t + i * 1000L)).isEmpty());
        }
    }

    @Test
    void firesWhenSlowAndCarBehindClosing() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 800f)); // 200m → 3.6s

        long t = 1000L;
        primeOutlapBuffer(cars, t);

        // Now actually closing: gap shrinks from 200m to 100m.
        ArrayNode cars2 = MAPPER.createArrayNode();
        cars2.add(car(0, "Player", 5, 1, 1050f));
        cars2.add(car(1, "RIVAL", 6, 1, 950f)); // 100m → 1.8s
        var msg = detector.evaluate(tickOnTrack(5, 1, 1050f, 0.15f, 110, cars2,
                t + (PRIME_SAMPLES + 1) * 1000L));

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
        // High throttle + high speed = flying lap. This is the case the v2 fix targets.
        for (int i = 0; i < PRIME_SAMPLES + 2; i++) {
            assertTrue(detector.evaluate(tickOnTrack(5, 1, 800f + i * 50, 0.85f, 240, cars, t + i * 1000L)).isEmpty());
        }
    }

    @Test
    void doesNotFireOnFlyingLapWithBriefBrakingZones() {
        // Reproduces the bug from the user's qualifying session. Player is on a hot
        // lap: high speed, high throttle on average, but throttle dips below 30 % for
        // a few corners. With the old 3-sample buffer this fired falsely.
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 800f));

        long t = 1000L;
        // Pattern: 3 samples low (corner), 3 samples high (straight), repeat — net avg
        // throttle is too high for "slow lap" and avg speed is too high too.
        for (int i = 0; i < PRIME_SAMPLES + 2; i++) {
            boolean braking = (i / 3) % 2 == 0;
            float throttle = braking ? 0.10f : 0.95f;
            int speed = braking ? 100 : 290;
            assertTrue(detector.evaluate(tickOnTrack(5, 1, 800f + i * 50, throttle, speed, cars, t + i * 1000L)).isEmpty(),
                    "must not fire on a flying lap with corner braking zones");
        }
    }

    @Test
    void doesNotFireWhenGapTooLarge() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 0f)); // 1000m → 18.2s

        long t = 1000L;
        for (int i = 0; i < PRIME_SAMPLES; i++) {
            assertTrue(detector.evaluate(tickOnTrack(5, 1, 800f + i * 10, 0.15f, 110, cars, t + i * 1000L)).isEmpty());
        }
    }

    @Test
    void doesNotFireWhenRivalFallingBack() {
        long t = 1000L;
        ArrayNode close = MAPPER.createArrayNode();
        close.add(car(0, "Player", 5, 1, 1000f));
        close.add(car(1, "RIVAL", 6, 1, 950f));
        // Prime: player creeps forward 5 m/tick, rival fixed → gap growing the
        // whole time. Small increments keep the gap below the 4 s ceiling so
        // previousGapBehind actually gets recorded.
        for (int i = 0; i < PRIME_SAMPLES; i++) {
            detector.evaluate(tickOnTrack(5, 1, 1000f + i * 5, 0.15f, 110, close, t + i * 1000L));
        }
        // Test tick: gap continues to grow (rival further back). 'closing' must
        // be false → no fire.
        ArrayNode further = MAPPER.createArrayNode();
        further.add(car(0, "Player", 5, 1, 1100f));
        further.add(car(1, "RIVAL", 6, 1, 950f));
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1100f, 0.15f, 110, further,
                t + (PRIME_SAMPLES + 1) * 1000L)).isEmpty());
    }

    @Test
    void doesNotFireWhenCarBehindIsParked() {
        // Car behind has speed 0 → it's a parked AI in the garage / pits, not a real
        // pursuer. Specifically reproduces the one-shot Q misfire pattern (every other
        // car is sitting out).
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        ObjectNode rival = car(1, "RIVAL", 6, 1, 950f);
        rival.put("speed", 0);
        rival.put("pitStatus", 1);
        cars.add(rival);

        long t = 1000L;
        primeOutlapBuffer(cars, t);
        ArrayNode cars2 = MAPPER.createArrayNode();
        cars2.add(car(0, "Player", 5, 1, 1050f));
        ObjectNode rival2 = car(1, "RIVAL", 6, 1, 1000f);
        rival2.put("speed", 0);
        rival2.put("pitStatus", 1);
        cars2.add(rival2);
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1050f, 0.15f, 110, cars2,
                t + (PRIME_SAMPLES + 1) * 1000L)).isEmpty(),
                "must not warn about parked AIs");
    }

    @Test
    void respectsCooldown() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car(0, "Player", 5, 1, 1000f));
        cars.add(car(1, "RIVAL", 6, 1, 800f));

        long t = 1000L;
        primeOutlapBuffer(cars, t);
        // Closing tick → fires.
        ArrayNode cars2 = MAPPER.createArrayNode();
        cars2.add(car(0, "Player", 5, 1, 1050f));
        cars2.add(car(1, "RIVAL", 6, 1, 950f));
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1050f, 0.15f, 110, cars2,
                t + (PRIME_SAMPLES + 1) * 1000L)).isPresent());
        // 5 seconds later, still closing — must be suppressed by cooldown.
        ArrayNode cars3 = MAPPER.createArrayNode();
        cars3.add(car(0, "Player", 5, 1, 1100f));
        cars3.add(car(1, "RIVAL", 6, 1, 1050f));
        assertTrue(detector.evaluate(tickOnTrack(5, 1, 1100f, 0.15f, 110, cars3,
                t + (PRIME_SAMPLES + 6) * 1000L)).isEmpty());
    }

    @Test
    void appliesOnlyToOnTrack() {
        assertEquals(java.util.Set.of(PitState.ON_TRACK), detector.appliesToStates());
    }

    @Test
    void doesNotApplyToRaceOrQualifying() {
        // Qualifying (any format) is excluded after the v2 fix — flying-lap throttle
        // dips at slow corners caused systemic false positives, and one-shot Q has
        // only a single car on track.
        assertFalse(detector.appliesToSessions().contains(SessionKind.RACE));
        assertFalse(detector.appliesToSessions().contains(SessionKind.QUALIFYING));
        assertFalse(detector.appliesToSessions().contains(SessionKind.SPRINT_QUALIFYING));
    }
}
