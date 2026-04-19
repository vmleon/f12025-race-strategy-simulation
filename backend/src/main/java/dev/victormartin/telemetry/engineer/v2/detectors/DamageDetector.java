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
    private static final int SEVERE = 7;

    // Fixed order for tie-breaking within a tick.
    private static final String[] WIRE_KEYS  = { "fwDmg",       "rwDmg",      "flDmg", "diffDmg",  "spDmg"   };
    private static final String[] PART_LABEL = { "Front wing",  "Rear wing",  "Floor", "Diffuser", "Sidepod" };
    private static final int NUM_PARTS = WIRE_KEYS.length;

    private final Map<String, int[]> armedByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "Damage"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        int[] armed = armedByUid.computeIfAbsent(tick.sessionUid(), k -> new int[NUM_PARTS]);
        JsonNode pc = tick.playerCar();

        // Apply the reset rule once per tick for all parts before evaluating crossings.
        for (int i = 0; i < NUM_PARTS; i++) {
            JsonNode node = pc.get(WIRE_KEYS[i]);
            if (node == null || !node.canConvertToInt()) continue;
            int v = node.asInt();
            if (armed[i] > 0 && v < armed[i] - 1) {
                armed[i] = 0;
            }
        }

        // Evaluate crossings in fixed part order. First match wins.
        for (int i = 0; i < NUM_PARTS; i++) {
            JsonNode node = pc.get(WIRE_KEYS[i]);
            if (node == null || !node.canConvertToInt()) continue;
            int v = node.asInt();
            if (v >= SEVERE && armed[i] < SEVERE) {
                armed[i] = SEVERE;
                return Optional.of(new EngineerMessage(
                        Priority.IMMEDIATE,
                        PART_LABEL[i] + " is heavily damaged.",
                        tick.wallClockMs(), tick.currentLap(), 2));
            }
            if (v >= LIGHT && armed[i] < LIGHT) {
                armed[i] = LIGHT;
                return Optional.of(new EngineerMessage(
                        Priority.HIGH,
                        PART_LABEL[i] + " has light damage.",
                        tick.wallClockMs(), tick.currentLap(), 3));
            }
        }
        return Optional.empty();
    }
}
