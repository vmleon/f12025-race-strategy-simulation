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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionChangeDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TRACK_LENGTH = 5300;
    private static final String SESSION_UID = "abc";
    private static final long T0 = 1_000_000L;
    /** Past the 5s debounce window; safe choice for "settled" assertions. */
    private static final long PAST_DEBOUNCE = 6_000L;

    private PositionChangeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new PositionChangeDetector();
        detector.onSessionStarted(SESSION_UID, 0, 10);
    }

    private static ObjectNode car(String name, int pos, int lap, float lapDist) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("name", name);
        n.put("pos", pos);
        n.put("lap", lap);
        n.put("lapDist", lapDist);
        return n;
    }

    private EngineerTick tickAt(long wallClockMs, int playerPos, float playerLapDist,
                                 JsonNode cars, PitState previousPitState) {
        return new EngineerTick(
                wallClockMs, SESSION_UID, 10, SessionKind.RACE,
                7, 1, 0, TRACK_LENGTH,
                PitState.ON_TRACK, previousPitState,
                MAPPER.createObjectNode(),
                MAPPER.createObjectNode(),
                cars,
                playerPos, playerLapDist, 240, 0.9f,
                0, 0, 0);
    }

    @Test
    void firstOnTrackTickAfterPitExitDoesNotEmitAndSetsBaseline() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car("VERSTAPPEN", 4, 1, 1500f));
        cars.add(car("Player", 5, 1, 1000f));

        // Coming out of the pit lane: previous state was PIT_EXIT, player now P5.
        assertTrue(detector.evaluate(tickAt(T0, 5, 1000f, cars, PitState.PIT_EXIT)).isEmpty());
        // Subsequent ticks at the same position should be silent.
        assertTrue(detector.evaluate(tickAt(T0 + 1000, 5, 1100f, cars, PitState.ON_TRACK)).isEmpty());
        assertTrue(detector.evaluate(tickAt(T0 + PAST_DEBOUNCE + 1000, 5, 1100f, cars, PitState.ON_TRACK)).isEmpty());
    }

    @Test
    void emitsImmediateGainAfterDebounceWindow() {
        // Establish baseline at P5.
        ArrayNode setup = MAPPER.createArrayNode();
        setup.add(car("VERSTAPPEN", 4, 1, 1500f));
        setup.add(car("Player", 5, 1, 1000f));
        detector.evaluate(tickAt(T0, 5, 1000f, setup, PitState.PIT_EXIT));
        detector.evaluate(tickAt(T0 + 1000, 5, 1100f, setup, PitState.ON_TRACK));

        // Overtake: player goes P5 → P4.
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car("NORRIS", 3, 1, 1320f)); // 220m ahead → 4.0s
        cars.add(car("Player", 4, 1, 1100f));
        cars.add(car("VERSTAPPEN", 5, 1, 1080f));
        // Debounce starts here. Same-tick fire must NOT happen.
        assertTrue(detector.evaluate(tickAt(T0 + 2000, 4, 1100f, cars, PitState.ON_TRACK)).isEmpty());
        // Position stable for the next tick within debounce — still no fire.
        assertTrue(detector.evaluate(tickAt(T0 + 4000, 4, 1100f, cars, PitState.ON_TRACK)).isEmpty());
        // Past the 5s debounce window — fire.
        var msg = detector.evaluate(tickAt(T0 + 2000 + PAST_DEBOUNCE, 4, 1100f, cars, PitState.ON_TRACK));

        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.IMMEDIATE, msg.get().priority());
        assertEquals("P4. NORRIS is next, 4 seconds up the road.", msg.get().text());
    }

    @Test
    void leadingNowMessageWhenGainingP1AfterDebounce() {
        ArrayNode setup = MAPPER.createArrayNode();
        setup.add(car("Player", 2, 1, 1000f));
        detector.evaluate(tickAt(T0, 2, 1000f, setup, PitState.PIT_EXIT));
        detector.evaluate(tickAt(T0 + 1000, 2, 1100f, setup, PitState.ON_TRACK));

        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car("Player", 1, 1, 1200f));
        detector.evaluate(tickAt(T0 + 2000, 1, 1200f, cars, PitState.ON_TRACK));
        var msg = detector.evaluate(tickAt(T0 + 2000 + PAST_DEBOUNCE, 1, 1200f, cars, PitState.ON_TRACK));

        assertTrue(msg.isPresent());
        assertEquals("P1. Leading now.", msg.get().text());
    }

    @Test
    void emitsHighLossWithNameAfterDebounce() {
        ArrayNode setup = MAPPER.createArrayNode();
        setup.add(car("Player", 5, 1, 1000f));
        detector.evaluate(tickAt(T0, 5, 1000f, setup, PitState.PIT_EXIT));
        detector.evaluate(tickAt(T0 + 1000, 5, 1100f, setup, PitState.ON_TRACK));

        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car("RUSSELL", 5, 1, 1300f));
        cars.add(car("Player", 6, 1, 1250f));
        detector.evaluate(tickAt(T0 + 2000, 6, 1250f, cars, PitState.ON_TRACK));
        var msg = detector.evaluate(tickAt(T0 + 2000 + PAST_DEBOUNCE, 6, 1250f, cars, PitState.ON_TRACK));

        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.HIGH, msg.get().priority());
        assertEquals("Lost a place. P6. RUSSELL is now ahead.", msg.get().text());
    }

    @Test
    void doesNotEmitWhenPositionUnchanged() {
        ArrayNode cars = MAPPER.createArrayNode();
        cars.add(car("Player", 5, 1, 1000f));
        detector.evaluate(tickAt(T0, 5, 1000f, cars, PitState.PIT_EXIT));
        assertTrue(detector.evaluate(tickAt(T0 + 1000, 5, 1100f, cars, PitState.ON_TRACK)).isEmpty());
        assertTrue(detector.evaluate(tickAt(T0 + PAST_DEBOUNCE, 5, 1200f, cars, PitState.ON_TRACK)).isEmpty());
    }

    /** Battle: lost a place, regained it within 5s — should be silent. */
    @Test
    void netZeroBattleEmitsNothing() {
        ArrayNode atP5 = MAPPER.createArrayNode();
        atP5.add(car("Player", 5, 1, 1000f));
        detector.evaluate(tickAt(T0, 5, 1000f, atP5, PitState.PIT_EXIT));
        detector.evaluate(tickAt(T0 + 1000, 5, 1100f, atP5, PitState.ON_TRACK));

        // Lost a place → P6
        ArrayNode atP6 = MAPPER.createArrayNode();
        atP6.add(car("RUSSELL", 5, 1, 1300f));
        atP6.add(car("Player", 6, 1, 1250f));
        assertTrue(detector.evaluate(tickAt(T0 + 2000, 6, 1250f, atP6, PitState.ON_TRACK)).isEmpty());

        // Regained it within debounce → back to P5
        ArrayNode backP5 = MAPPER.createArrayNode();
        backP5.add(car("Player", 5, 1, 1300f));
        backP5.add(car("RUSSELL", 6, 1, 1280f));
        assertTrue(detector.evaluate(tickAt(T0 + 4000, 5, 1300f, backP5, PitState.ON_TRACK)).isEmpty());

        // Now stable for past the debounce — net change is zero, no message.
        assertTrue(detector.evaluate(tickAt(T0 + 4000 + PAST_DEBOUNCE, 5, 1400f, backP5, PitState.ON_TRACK)).isEmpty());
    }

    /** Lost two places in quick succession — single consolidated message. */
    @Test
    void consecutiveLossesConsolidate() {
        ArrayNode atP5 = MAPPER.createArrayNode();
        atP5.add(car("Player", 5, 1, 1000f));
        detector.evaluate(tickAt(T0, 5, 1000f, atP5, PitState.PIT_EXIT));
        detector.evaluate(tickAt(T0 + 1000, 5, 1100f, atP5, PitState.ON_TRACK));

        // Lost first place → P6
        ArrayNode atP6 = MAPPER.createArrayNode();
        atP6.add(car("RUSSELL", 5, 1, 1300f));
        atP6.add(car("Player", 6, 1, 1250f));
        assertTrue(detector.evaluate(tickAt(T0 + 2000, 6, 1250f, atP6, PitState.ON_TRACK)).isEmpty());

        // Lost second place → P7 (within debounce)
        ArrayNode atP7 = MAPPER.createArrayNode();
        atP7.add(car("HAMILTON", 6, 1, 1310f));
        atP7.add(car("Player", 7, 1, 1280f));
        assertTrue(detector.evaluate(tickAt(T0 + 3000, 7, 1280f, atP7, PitState.ON_TRACK)).isEmpty());

        // Stable past debounce — single consolidated message
        var msg = detector.evaluate(tickAt(T0 + 3000 + PAST_DEBOUNCE, 7, 1500f, atP7, PitState.ON_TRACK));
        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.HIGH, msg.get().priority());
        assertEquals("Lost 2 places. P7. HAMILTON is now ahead.", msg.get().text());
    }

    /** Gained two places in quick succession — single consolidated message. */
    @Test
    void consecutiveGainsConsolidate() {
        ArrayNode atP5 = MAPPER.createArrayNode();
        atP5.add(car("Player", 5, 1, 1000f));
        detector.evaluate(tickAt(T0, 5, 1000f, atP5, PitState.PIT_EXIT));
        detector.evaluate(tickAt(T0 + 1000, 5, 1100f, atP5, PitState.ON_TRACK));

        // Gain to P4
        ArrayNode atP4 = MAPPER.createArrayNode();
        atP4.add(car("NORRIS", 3, 1, 1500f)); // 380m ahead → 6.9s
        atP4.add(car("Player", 4, 1, 1120f));
        assertTrue(detector.evaluate(tickAt(T0 + 2000, 4, 1120f, atP4, PitState.ON_TRACK)).isEmpty());

        // Gain to P3
        ArrayNode atP3 = MAPPER.createArrayNode();
        atP3.add(car("VERSTAPPEN", 2, 1, 1600f)); // 460m ahead → 8.4s
        atP3.add(car("Player", 3, 1, 1140f));
        assertTrue(detector.evaluate(tickAt(T0 + 3000, 3, 1140f, atP3, PitState.ON_TRACK)).isEmpty());

        var msg = detector.evaluate(tickAt(T0 + 3000 + PAST_DEBOUNCE, 3, 1500f, atP3, PitState.ON_TRACK));
        assertTrue(msg.isPresent());
        assertEquals("Up 2 places. P3. VERSTAPPEN is next, 1.8 seconds up the road.", msg.get().text());
    }

    @Test
    void appliesOnlyToOnTrack() {
        assertEquals(java.util.Set.of(PitState.ON_TRACK), detector.appliesToStates());
    }

    @Test
    void appliesToRaceAndSprintRace() {
        assertEquals(java.util.Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE), detector.appliesToSessions());
    }
}
