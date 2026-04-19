package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
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

    private static final int LIGHT = 3;
    // parts[0] = front wing; more added in later tasks.
    private final Map<String, int[]> armedByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "Damage"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        int[] armed = armedByUid.computeIfAbsent(tick.sessionUid(), k -> new int[1]);
        JsonNode pc = tick.playerCar();
        JsonNode fwNode = pc.get("fwDmg");
        if (fwNode == null || !fwNode.canConvertToInt()) return Optional.empty();
        int fw = fwNode.asInt();
        if (fw >= LIGHT && armed[0] < LIGHT) {
            armed[0] = LIGHT;
            return Optional.of(new EngineerMessage(
                    Priority.HIGH,
                    "Front wing has light damage.",
                    tick.wallClockMs(), tick.currentLap(), 3));
        }
        return Optional.empty();
    }
}
