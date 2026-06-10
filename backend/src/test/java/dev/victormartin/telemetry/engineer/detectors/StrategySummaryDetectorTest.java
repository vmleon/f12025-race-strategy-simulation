package dev.victormartin.telemetry.engineer.detectors;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.SessionKind;
import dev.victormartin.telemetry.simulation.RaceSnapshot.PitStrategy.PitStop;
import dev.victormartin.telemetry.simulation.StrategyEvaluation;
import dev.victormartin.telemetry.simulation.StrategyEvaluation.RankedStrategy;
import dev.victormartin.telemetry.simulation.StrategyEvaluation.StrategyCandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategySummaryDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_UID = "abc";

    private StrategySummaryDetector detector;

    @BeforeEach
    void setUp() {
        detector = new StrategySummaryDetector();
        detector.onSessionStarted(SESSION_UID, 0, 15);
    }

    private EngineerTick tickAtLap(int lap, String tyre) {
        ObjectNode player = MAPPER.createObjectNode();
        player.put("tyre", tyre);
        return new EngineerTick(
                1_000L, SESSION_UID, 15, SessionKind.RACE,
                7, lap, 50, 5300,
                PitState.ON_TRACK, PitState.ON_TRACK,
                MAPPER.createObjectNode(), player, MAPPER.createArrayNode(),
                4, 1000f, 240, 0.9f, 0, 0, 0);
    }

    private static RankedStrategy ranked(int rank, String label, PitStop... stops) {
        return new RankedStrategy(rank, new StrategyCandidate(label, List.of(stops)),
                4.0, 0, 0, 0, 0, 0, 0, 12.0);
    }

    private static StrategyEvaluation eval(RankedStrategy... ranks) {
        return new StrategyEvaluation(4, List.of(ranks), false);
    }

    @Test
    void announcesCurrentPlanAndBestAlternative() {
        detector.setEvaluation(SESSION_UID, eval(
                ranked(1, "1-stop M", new PitStop(20, 17)),
                ranked(2, "1-stop H", new PitStop(22, 18))));

        // Within the lookahead window (box L20, now L18) so it's actionable.
        var msg = detector.evaluate(tickAtLap(18, "S"));
        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.NORMAL, msg.get().priority());
        assertEquals("Current plan: box lap 20 for Mediums. Next best: box lap 22 for Hards.",
                msg.get().text());
    }

    @Test
    void suppressedUntilStopWithinLookahead() {
        detector.setEvaluation(SESSION_UID, eval(ranked(1, "1-stop M", new PitStop(20, 17))));
        // Far out — the optimal box lap keeps drifting, so stay quiet.
        assertTrue(detector.evaluate(tickAtLap(10, "S")).isEmpty());
        // Within the lookahead window — now announce (signature wasn't consumed while quiet).
        assertTrue(detector.evaluate(tickAtLap(18, "S")).isPresent());
    }

    @Test
    void suppressedDuringOpeningLap() {
        detector.setEvaluation(SESSION_UID, eval(ranked(1, "1-stop M", new PitStop(20, 17))));
        assertTrue(detector.evaluate(tickAtLap(1, "S")).isEmpty());
    }

    @Test
    void doesNotRepeatWhilePlanIsStable() {
        detector.setEvaluation(SESSION_UID, eval(ranked(1, "1-stop M", new PitStop(20, 17))));
        assertTrue(detector.evaluate(tickAtLap(18, "S")).isPresent());
        // Same plan a lap later — no repeat.
        assertTrue(detector.evaluate(tickAtLap(19, "S")).isEmpty());
    }

    @Test
    void reAnnouncesWhenPlanMateriallyChanges() {
        detector.setEvaluation(SESSION_UID, eval(ranked(1, "1-stop M", new PitStop(20, 17))));
        assertTrue(detector.evaluate(tickAtLap(18, "S")).isPresent());

        // New recommendation: different pit lap + compound, still within lookahead.
        detector.setEvaluation(SESSION_UID, eval(ranked(1, "1-stop H", new PitStop(21, 18))));
        var msg = detector.evaluate(tickAtLap(19, "S"));
        assertTrue(msg.isPresent());
        assertEquals("Current plan: box lap 21 for Hards.", msg.get().text());
    }

    @Test
    void noStopPlanSaysRunToTheEnd() {
        detector.setEvaluation(SESSION_UID, eval(ranked(1, "No stop")));
        var msg = detector.evaluate(tickAtLap(8, "M"));
        assertTrue(msg.isPresent());
        assertEquals("Current plan: stay out to the end on medium.", msg.get().text());
    }
}
