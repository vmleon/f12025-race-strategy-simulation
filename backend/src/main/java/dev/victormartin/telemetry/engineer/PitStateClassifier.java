package dev.victormartin.telemetry.engineer;

/**
 * Pure FSM that classifies the player's current {@link PitState} from one tick of
 * telemetry plus the previous state.
 *
 * Inputs come from PacketLapData (pitStatus, pitLaneTimerActive, pitLaneTimeMs,
 * lapDist) and PacketCarTelemetry (speedKmh).
 *
 * Stateless — instances of this class are not needed; everything is on
 * {@link #classify}. The caller threads the previous state forward.
 */
public final class PitStateClassifier {

    /** Speed threshold (km/h) below which the player is treated as "stopped" in the pit. */
    public static final int STOP_THRESHOLD_KMH = 5;

    private PitStateClassifier() {}

    /**
     * Compute the current pit state.
     *
     * @param pitStatus           raw F1 25 pitStatus enum: 0 = on track, 1 = in pit lane, 2 = serviced (race only)
     * @param pitLaneTimerActive  raw F1 25 flag (currently unused — reserved for future race-specific logic)
     * @param pitLaneTimeMs       raw F1 25 ms since pit-lane entry (currently unused)
     * @param lapDist             player's lap distance (negative while spawned in garage)
     * @param speedKmh            current speed in km/h (integer per game telemetry)
     * @param sessionKind         session category (currently unused — reserved for session-specific logic)
     * @param previous            the previous tick's classified state, or null on session start
     */
    public static PitState classify(int pitStatus,
                                    int pitLaneTimerActive,
                                    int pitLaneTimeMs,
                                    float lapDist,
                                    int speedKmh,
                                    SessionKind sessionKind,
                                    PitState previous) {

        if (pitStatus == 0) return PitState.ON_TRACK;

        // pitStatus == 2 only appears during a serviced race pit stop. The game
        // sets it the moment the mechanics start work, regardless of the
        // residual roll-on speed, so it overrides the speed-based stop check.
        if (pitStatus == 2) return PitState.PIT_STOPPED;

        // pitStatus == 1: depends on where the player came from.
        if (previous == null) {
            // First tick of a new session that already starts in the pit. Most
            // common case: garage spawn. We can't distinguish "spawned and
            // moving" from "spawned and stopped" yet, so default to STOPPED;
            // the next tick will promote to PIT_EXIT if the player is rolling.
            return PitState.PIT_STOPPED;
        }
        return switch (previous) {
            case ON_TRACK -> PitState.PIT_ENTRY;
            case PIT_ENTRY -> speedKmh < STOP_THRESHOLD_KMH ? PitState.PIT_STOPPED : PitState.PIT_ENTRY;
            case PIT_STOPPED -> speedKmh >= STOP_THRESHOLD_KMH ? PitState.PIT_EXIT : PitState.PIT_STOPPED;
            case PIT_EXIT -> PitState.PIT_EXIT;
        };
    }
}
