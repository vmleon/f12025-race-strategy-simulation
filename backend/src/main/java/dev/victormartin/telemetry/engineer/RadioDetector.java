package dev.victormartin.telemetry.engineer;

import java.util.Optional;
import java.util.Set;

/**
 * One race-engineer detector. Each detector class encapsulates exactly one
 * scenario (e.g. "track is clear at pit exit", "car closing on slow lap",
 * "lost a place"). The orchestrator filters detectors by
 * {@link #appliesToStates()} and {@link #appliesToSessions()} before invoking
 * {@link #evaluate(EngineerTick)} — which means a detector cannot fire in a
 * pit state or session it didn't opt into, so {@code pitStatus} checks can't
 * be forgotten inside detector methods.
 *
 * Detectors own their per-session state internally (typically keyed by
 * {@code tick.sessionUid()}). They must release that state in
 * {@link #onSessionEnded(String)} to prevent leaks across sessions.
 */
public interface RadioDetector {

    /** Short, stable identifier used in trace logs. */
    String name();

    /** Pit states in which this detector is allowed to fire. Empty set = all. */
    Set<PitState> appliesToStates();

    /** Sessions in which this detector is allowed to fire. Empty set = all. */
    Set<SessionKind> appliesToSessions();

    /**
     * Evaluate the current tick. Return {@link Optional#empty()} if no message
     * should be emitted; otherwise return the message to enqueue.
     */
    Optional<EngineerMessage> evaluate(EngineerTick tick);

    default void onSessionStarted(String sessionUid, int trackId, int sessionType) {}

    default void onSessionEnded(String sessionUid) {}
}
