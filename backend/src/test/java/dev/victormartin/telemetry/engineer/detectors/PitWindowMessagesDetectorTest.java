package dev.victormartin.telemetry.engineer.detectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.SessionKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PitWindowMessagesDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UID = "abc";
    private static final int TRACK_LENGTH = 5000;
    private static final int SOFT = 16, MEDIUM = 17, HARD = 18;

    private PitWindowMessagesDetector detector;

    @BeforeEach
    void setUp() {
        detector = new PitWindowMessagesDetector();
        detector.onSessionStarted(UID, 0, 10);
    }

    /** Player-car node carrying the pit-stop count read by the detector. */
    private static ObjectNode playerCar(int pits) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("pits", pits);
        return n;
    }

    private EngineerTick tick(int currentLap, float lapDist, int pits) {
        return tick(currentLap, lapDist, playerCar(pits));
    }

    /** Player-car node with a per-corner tyre-wear array [RL, RR, FL, FR]. */
    private static ObjectNode playerCar(int pits, int[] wear) {
        ObjectNode n = playerCar(pits);
        ArrayNode w = n.putArray("tyreWear");
        for (int v : wear) w.add(v);
        return n;
    }

    private EngineerTick tickWithWear(int currentLap, float lapDist, int pits, int[] wear) {
        return tick(currentLap, lapDist, playerCar(pits, wear));
    }

    private EngineerTick tick(int currentLap, float lapDist, ObjectNode playerCar) {
        return new EngineerTick(
                1_000L, UID, 10, SessionKind.RACE,
                0, currentLap, 50, TRACK_LENGTH,
                PitState.ON_TRACK, PitState.ON_TRACK,
                MAPPER.createObjectNode(),
                playerCar,
                MAPPER.createArrayNode(),
                5, lapDist, 240, 0.9f,
                0, 0, 0);
    }

    /** Tick on a given lap/sector with an explicit pit state (for the box-this-lap call). */
    private EngineerTick tickSector(int currentLap, int sector, int pits, PitState pitState) {
        ObjectNode player = playerCar(pits);
        player.put("sector", sector);
        return new EngineerTick(
                1_000L, UID, 10, SessionKind.RACE,
                0, currentLap, 50, TRACK_LENGTH,
                pitState, PitState.ON_TRACK,
                MAPPER.createObjectNode(),
                player,
                MAPPER.createArrayNode(),
                5, 100f, 240, 0.9f,
                0, 0, 0);
    }

    @Test
    void recoveryFiresWhenDriverMissesTheWindow() {
        // Strategy recommends boxing on lap 20 (Hards).
        detector.setRecommendation(UID, 20, HARD);

        // Drive up to and onto the window: T-5 @15, T-1 @19, box @20.
        detector.evaluate(tick(15, 100f, 0));
        detector.evaluate(tick(19, 100f, 0));
        detector.evaluate(tick(20, TRACK_LENGTH * 0.9f, 0));

        // Driver does NOT pit (pits still 0). Strategy re-evaluates and now
        // recommends lap 24 (still Hards).
        detector.setRecommendation(UID, 24, HARD);
        Optional<EngineerMessage> msg = detector.evaluate(tick(21, 100f, 0));

        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.HIGH, msg.get().priority());
        assertEquals("We've missed that window. New plan, box lap 24, Hards ready.",
                msg.get().text());

        // Normal cadence resumes toward the new target.
        assertTrue(detector.evaluate(tick(22, 100f, 0)).isEmpty()); // delta 2, silent
        Optional<EngineerMessage> tMinus1 = detector.evaluate(tick(23, 100f, 0));
        assertTrue(tMinus1.isPresent());
        assertEquals("Box next lap. Hards ready.", tMinus1.get().text());
    }

    @Test
    void noRecoveryWhenDriverPittedAsInstructed() {
        // Strategy recommends boxing on lap 20 (Hards).
        detector.setRecommendation(UID, 20, HARD);
        detector.evaluate(tick(15, 100f, 0));
        detector.evaluate(tick(19, 100f, 0));
        detector.evaluate(tick(20, TRACK_LENGTH * 0.9f, 0));

        // Driver pits on lap 20 (pits 0 -> 1). A second stop is now recommended
        // for lap 28 — this is a fresh plan, not a missed window.
        detector.setRecommendation(UID, 28, MEDIUM);
        Optional<EngineerMessage> msg = detector.evaluate(tick(21, 100f, 1));

        assertTrue(msg.isEmpty());
    }

    @Test
    void firstWindowIsNotTreatedAsRecovery() {
        // Very first recommendation of the session — no prior target exists.
        detector.setRecommendation(UID, 20, SOFT);
        Optional<EngineerMessage> msg = detector.evaluate(tick(15, 100f, 0)); // delta 5

        assertTrue(msg.isPresent());
        assertEquals("Box window opens in 5 laps. Softs ready.", msg.get().text());
    }

    @Test
    void doesNotReannounceFiveLapWindowWhenTargetDrifts() {
        detector.setRecommendation(UID, 30, HARD);
        Optional<EngineerMessage> first = detector.evaluate(tick(25, 100f, 0)); // delta 5
        assertTrue(first.isPresent());
        assertEquals("Box window opens in 5 laps. Hards ready.", first.get().text());

        // Strategy drifts the target out by 2 laps within the same stint (no pit).
        detector.setRecommendation(UID, 32, HARD);
        assertTrue(detector.evaluate(tick(26, 100f, 0)).isEmpty()); // delta 6, silent
        // delta hits 5 again for the drifted target — must NOT re-announce.
        assertTrue(detector.evaluate(tick(27, 100f, 0)).isEmpty());
    }

    @Test
    void doesNotAnnounceFiveLapWindowAtHighTyreWear() {
        detector.setRecommendation(UID, 30, HARD);
        // A corner already past the cliff (40%): "opens in 5 laps" is nonsense.
        Optional<EngineerMessage> msg =
                detector.evaluate(tickWithWear(25, 100f, 0, new int[] {40, 38, 35, 30})); // delta 5
        assertTrue(msg.isEmpty());
    }

    @Test
    void clearedRecommendationIsSilent() {
        detector.setRecommendation(UID, -1, 0);
        assertTrue(detector.evaluate(tick(10, 100f, 0)).isEmpty());
    }

    @Test
    void boxThisLapFiresOnFinalSectorOfStopLap() {
        detector.setRecommendation(UID, 20, MEDIUM);
        // Earlier sectors of the stop lap: not yet the commit point.
        assertTrue(detector.evaluate(tickSector(20, 0, 0, PitState.ON_TRACK)).isEmpty());
        assertTrue(detector.evaluate(tickSector(20, 1, 0, PitState.ON_TRACK)).isEmpty());
        // Entering the final sector → commit.
        Optional<EngineerMessage> msg = detector.evaluate(tickSector(20, 2, 0, PitState.ON_TRACK));
        assertTrue(msg.isPresent());
        assertEquals(EngineerMessage.Priority.IMMEDIATE, msg.get().priority());
        assertEquals("Box this lap. Mediums ready.", msg.get().text());
        // Once per stop: a later final-sector tick stays silent.
        assertTrue(detector.evaluate(tickSector(20, 2, 0, PitState.ON_TRACK)).isEmpty());
    }

    @Test
    void boxThisLapSuppressedWhenAlreadyInPitLane() {
        detector.setRecommendation(UID, 20, MEDIUM);
        // Final sector but already in the pit lane → no commit call.
        assertTrue(detector.evaluate(tickSector(20, 2, 0, PitState.PIT_EXIT)).isEmpty());
    }
}
