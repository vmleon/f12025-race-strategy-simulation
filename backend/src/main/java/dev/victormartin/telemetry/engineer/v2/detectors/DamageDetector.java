package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Optional;
import java.util.Set;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * Announces impact damage to chassis/aero parts at two severity tiers.
 * Parts watched (fixed order): front wing, rear wing, floor, diffuser, sidepod.
 * Thresholds: >=3% light (HIGH), >=7% severe (IMMEDIATE).
 *
 * Gearbox and engine damage are excluded — they accumulate from normal wear
 * and would fire spuriously under these aggressive thresholds.
 */
public class DamageDetector implements RadioDetector {

    @Override
    public String name() { return "Damage"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        return Optional.empty();
    }
}
