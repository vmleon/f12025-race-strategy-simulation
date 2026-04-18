package dev.victormartin.telemetry.engineer.v2;

/**
 * Where the player car is in the pit cycle.
 * Computed per tick by {@link PitStateClassifier}.
 *
 * Modelled as four states (not five) because the garage vs. pit-box distinction
 * does not change which detectors apply — only ON_TRACK / pit-entry / pit-stopped /
 * pit-exit drive different behaviour. Detectors that care about garage-vs-box
 * (e.g. front-wing repair messages) inspect lapDist or pitStatus directly.
 */
public enum PitState {
    /** On the racing line (pitStatus == 0). */
    ON_TRACK,
    /** Crossed the pit-entry line, still rolling toward the box. */
    PIT_ENTRY,
    /** Stationary in the garage or in the pit box (covers FP garage-park + race serviced stop). */
    PIT_STOPPED,
    /** Released from the box, rolling toward the pit-exit line. */
    PIT_EXIT
}
