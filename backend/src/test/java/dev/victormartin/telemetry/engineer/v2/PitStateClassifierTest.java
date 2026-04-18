package dev.victormartin.telemetry.engineer.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PitStateClassifier}. The single-tick assertions cover
 * each transition in isolation; the end-of-file end-to-end tests replay the
 * three scenarios observed in the Phase B telemetry capture (FP garage→track,
 * FP pit visit, race serviced pit stop) one tick at a time.
 */
class PitStateClassifierTest {

    private static PitState classify(int pitStatus, float lapDist, int speedKmh,
                                     SessionKind kind, PitState previous) {
        return PitStateClassifier.classify(pitStatus, 0, 0, lapDist, speedKmh, kind, previous);
    }

    // --- single-tick transitions --------------------------------------------

    @Test
    void onTrackStaysOnTrack() {
        assertEquals(PitState.ON_TRACK,
                classify(0, 1500f, 280, SessionKind.RACE, PitState.ON_TRACK));
    }

    @Test
    void anyStateDropsToOnTrackWhenPitStatusIsZero() {
        for (PitState prev : PitState.values()) {
            assertEquals(PitState.ON_TRACK,
                    classify(0, 100f, 240, SessionKind.RACE, prev),
                    "previous=" + prev);
        }
    }

    @Test
    void onTrackPromotesToPitEntryWhenPitStatusFlipsToOne() {
        assertEquals(PitState.PIT_ENTRY,
                classify(1, 4600f, 80, SessionKind.RACE, PitState.ON_TRACK));
    }

    @Test
    void pitEntryStaysWhileRolling() {
        assertEquals(PitState.PIT_ENTRY,
                classify(1, 50f, 60, SessionKind.RACE, PitState.PIT_ENTRY));
    }

    @Test
    void pitEntryBecomesStoppedWhenSpeedDropsBelowThreshold() {
        assertEquals(PitState.PIT_STOPPED,
                classify(1, 50f, 4, SessionKind.PRACTICE, PitState.PIT_ENTRY));
    }

    @Test
    void pitStoppedStaysWhileSpeedBelowThreshold() {
        assertEquals(PitState.PIT_STOPPED,
                classify(1, 49f, 0, SessionKind.PRACTICE, PitState.PIT_STOPPED));
    }

    @Test
    void pitStoppedPromotesToPitExitWhenSpeedRises() {
        assertEquals(PitState.PIT_EXIT,
                classify(1, 60f, 30, SessionKind.PRACTICE, PitState.PIT_STOPPED));
    }

    @Test
    void pitExitStaysUntilPitStatusReturnsToZero() {
        assertEquals(PitState.PIT_EXIT,
                classify(1, 250f, 70, SessionKind.PRACTICE, PitState.PIT_EXIT));
    }

    @Test
    void pitExitDropsToOnTrackOnPitStatusZero() {
        assertEquals(PitState.ON_TRACK,
                classify(0, 300f, 90, SessionKind.PRACTICE, PitState.PIT_EXIT));
    }

    // --- session start ------------------------------------------------------

    @Test
    void firstTickOnTrackClassifiesAsOnTrack() {
        assertEquals(PitState.ON_TRACK,
                classify(0, 1200f, 260, SessionKind.RACE, null));
    }

    @Test
    void firstTickInPitClassifiesAsStopped() {
        assertEquals(PitState.PIT_STOPPED,
                classify(1, -4615.2f, 0, SessionKind.PRACTICE, null));
    }

    // --- race-mode pitStatus = 2 override ----------------------------------

    @Test
    void pitStatusTwoForcesStoppedFromPitEntry() {
        assertEquals(PitState.PIT_STOPPED,
                classify(2, 52f, 8, SessionKind.RACE, PitState.PIT_ENTRY));
    }

    @Test
    void pitStatusTwoForcesStoppedRegardlessOfSpeed() {
        assertEquals(PitState.PIT_STOPPED,
                classify(2, 52f, 12, SessionKind.RACE, PitState.PIT_STOPPED));
    }

    @Test
    void pitStatusTwoForcesStoppedEvenComingFromPitExit() {
        // Hypothetical: two consecutive race stops in quick succession.
        assertEquals(PitState.PIT_STOPPED,
                classify(2, 52f, 4, SessionKind.RACE, PitState.PIT_EXIT));
    }

    // --- end-to-end scenarios mirroring captured logs -----------------------

    /**
     * FP3 cold-start: spawn in garage at lapDist=-4615.2, sit for 30s, roll out
     * through the pit lane, cross the pit-exit line onto the track.
     */
    @Test
    void scenarioFreePracticeColdStart() {
        // Tick 1: session starts with player parked in garage.
        PitState s = classify(1, -4615.2f, 0, SessionKind.PRACTICE, null);
        assertEquals(PitState.PIT_STOPPED, s);

        // Sitting still for 30 ticks (~30s).
        for (int i = 0; i < 30; i++) {
            s = classify(1, -4615.2f, 0, SessionKind.PRACTICE, s);
            assertEquals(PitState.PIT_STOPPED, s);
        }

        // Player releases handbrake, starts rolling.
        s = classify(1, -4600f, 8, SessionKind.PRACTICE, s);
        assertEquals(PitState.PIT_EXIT, s);

        // Continues rolling toward pit-exit line.
        s = classify(1, 200f, 60, SessionKind.PRACTICE, s);
        assertEquals(PitState.PIT_EXIT, s);

        // Crosses pit-exit line onto track.
        s = classify(0, 280f, 90, SessionKind.PRACTICE, s);
        assertEquals(PitState.ON_TRACK, s);
    }

    /**
     * FP3 mid-session pit visit: on track → cross pit-entry line → roll down
     * lane → park in box → sit → leave.
     */
    @Test
    void scenarioFreePracticePitVisit() {
        PitState s = PitState.ON_TRACK;

        // On track, mid-lap.
        s = classify(0, 4500f, 280, SessionKind.PRACTICE, s);
        assertEquals(PitState.ON_TRACK, s);

        // Cross pit-entry line.
        s = classify(1, 4621f, 100, SessionKind.PRACTICE, s);
        assertEquals(PitState.PIT_ENTRY, s);

        // Rolling down the lane.
        s = classify(1, 30f, 60, SessionKind.PRACTICE, s);
        assertEquals(PitState.PIT_ENTRY, s);

        // Slowing into the box.
        s = classify(1, 49f, 8, SessionKind.PRACTICE, s);
        assertEquals(PitState.PIT_ENTRY, s);

        // Stopped.
        s = classify(1, 49f, 0, SessionKind.PRACTICE, s);
        assertEquals(PitState.PIT_STOPPED, s);

        // Static for many ticks (front-wing repair).
        for (int i = 0; i < 40; i++) {
            s = classify(1, 49f, 0, SessionKind.PRACTICE, s);
            assertEquals(PitState.PIT_STOPPED, s);
        }

        // Released, accelerating out.
        s = classify(1, 60f, 25, SessionKind.PRACTICE, s);
        assertEquals(PitState.PIT_EXIT, s);

        // Crossing pit-exit line.
        s = classify(0, 295f, 80, SessionKind.PRACTICE, s);
        assertEquals(PitState.ON_TRACK, s);
    }

    /**
     * Race serviced pit stop: pitStatus 0 → 1 → 2 → 1 → 0, mirroring the
     * 14:18:01-14:18:22 transition pattern from the Phase B race trace.
     */
    @Test
    void scenarioRaceServicedPitStop() {
        PitState s = PitState.ON_TRACK;

        // On track, last lap before pitting.
        s = classify(0, 4609f, 270, SessionKind.RACE, s);
        assertEquals(PitState.ON_TRACK, s);

        // Cross pit-entry line; pitLaneTimer activates.
        s = PitStateClassifier.classify(1, 1, 200, 4609.7f, 90, SessionKind.RACE, s);
        assertEquals(PitState.PIT_ENTRY, s);

        // Service starts (pitStatus jumps to 2 even before speed reads zero).
        s = PitStateClassifier.classify(2, 1, 6206, 52.4f, 8, SessionKind.RACE, s);
        assertEquals(PitState.PIT_STOPPED, s);

        // Service ongoing.
        s = PitStateClassifier.classify(2, 1, 8000, 52.5f, 0, SessionKind.RACE, s);
        assertEquals(PitState.PIT_STOPPED, s);

        // Released — pitStatus drops back to 1, speed picks up.
        s = PitStateClassifier.classify(1, 1, 9253, 52.7f, 12, SessionKind.RACE, s);
        assertEquals(PitState.PIT_EXIT, s);

        // Crossing pit-exit line.
        s = PitStateClassifier.classify(0, 0, 0, 298.8f, 95, SessionKind.RACE, s);
        assertEquals(PitState.ON_TRACK, s);
    }

    // --- defensive: pitStatus == 1 with no previous (mid-game restart) -----

    @Test
    void firstTickOnTrackWithRollingSpeedClassifiesAsOnTrack() {
        // Edge case: backend reconnects mid-session while player is on track.
        assertEquals(PitState.ON_TRACK,
                classify(0, 2300f, 220, SessionKind.RACE, null));
    }
}
