package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Random;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeGripDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_UID = "abc";

    private PracticeGripDetector detector;

    @BeforeEach
    void setUp() {
        detector = new PracticeGripDetector(new Random(42)); // seeded for determinism
        detector.onSessionStarted(SESSION_UID, 0, 4);
    }

    private EngineerTick tickAtLap(int lap) {
        return new EngineerTick(
                System.currentTimeMillis(), SESSION_UID, 4, SessionKind.PRACTICE,
                7, lap, 0, 5300,
                PitState.ON_TRACK, PitState.ON_TRACK,
                MAPPER.createObjectNode(), MAPPER.createObjectNode(), MAPPER.createArrayNode(),
                5, 1000f, 240, 0.9f, 0, 0, 0);
    }

    @Test
    void doesNotFireOnLapZero() {
        assertTrue(detector.evaluate(tickAtLap(0)).isEmpty());
    }

    @Test
    void firstCallArmsScheduleAndDoesNotFire() {
        // Lap 1 first call should schedule, not emit.
        assertTrue(detector.evaluate(tickAtLap(1)).isEmpty());
    }

    @Test
    void firesAtScheduledLap() {
        // First call at lap 1 schedules nextFireLap to lap 1 + 2 + jitter ∈ {0,1} = lap 3 or 4.
        // We test that by lap 5 the detector has fired.
        detector.evaluate(tickAtLap(1));
        boolean firedAtLeastOnce = false;
        for (int lap = 2; lap <= 5; lap++) {
            if (detector.evaluate(tickAtLap(lap)).isPresent()) {
                firedAtLeastOnce = true;
                break;
            }
        }
        assertTrue(firedAtLeastOnce, "should fire at or before lap 5 with default cadence");
    }

    @Test
    void doesNotRepeatInSameLap() {
        detector.evaluate(tickAtLap(1));
        // Walk forward until we get a fire. Then a second call same lap must be empty.
        for (int lap = 2; lap <= 8; lap++) {
            var msg = detector.evaluate(tickAtLap(lap));
            if (msg.isPresent()) {
                assertTrue(detector.evaluate(tickAtLap(lap)).isEmpty(),
                        "must not repeat in the same lap");
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("expected at least one fire by lap 8");
    }

    @Test
    void firesMoreFrequentlyThanV1() {
        // Across 12 laps, v1 (cadence 5-7) would fire ~2 times. v2 should fire ~5+ times.
        detector.evaluate(tickAtLap(1));
        int fires = 0;
        for (int lap = 2; lap <= 12; lap++) {
            if (detector.evaluate(tickAtLap(lap)).isPresent()) fires++;
        }
        assertTrue(fires >= 4, "expected ≥4 fires across laps 2-12 with v2 cadence; got " + fires);
    }

    @Test
    void emittedMessageIsNormalPriority() {
        detector.evaluate(tickAtLap(1));
        for (int lap = 2; lap <= 8; lap++) {
            var msg = detector.evaluate(tickAtLap(lap));
            if (msg.isPresent()) {
                assertEquals(EngineerMessage.Priority.NORMAL, msg.get().priority());
                return;
            }
        }
    }

    @Test
    void onlyAppliesToPracticeAndOnTrack() {
        assertEquals(java.util.Set.of(PitState.ON_TRACK), detector.appliesToStates());
        assertEquals(java.util.Set.of(SessionKind.PRACTICE), detector.appliesToSessions());
    }
}
