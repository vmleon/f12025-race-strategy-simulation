package dev.victormartin.telemetry.engineer.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.RadioDetector;
import dev.victormartin.telemetry.engineer.SessionKind;

/**
 * Announces impact damage to chassis/aero parts at three severity tiers.
 * Parts watched (fixed order): front wing, rear wing, floor, diffuser, sidepod.
 *
 * Tiers (damage % from CarDamageData, 0-100):
 *   - MINOR (>=10):    "has minor damage" (HIGH)
 *   - DAMAGED (>=30):  "is damaged"       (HIGH)
 *   - HEAVY (>=60):    "is heavily damaged" (IMMEDIATE)
 *
 * The previous SEVERE threshold of 7% caused every meaningful contact to land
 * straight in the "heavily damaged" bucket — a curb-strike sounded identical
 * to a wall impact.
 *
 * Gearbox and engine damage are excluded — they accumulate from normal wear
 * and would fire spuriously under impact-tuned thresholds.
 */
public class DamageDetector implements RadioDetector {

    private static final int MINOR_PCT = 10;
    private static final int DAMAGED_PCT = 30;
    private static final int HEAVY_PCT = 60;

    // Tiers listed most-severe first so an impact that crosses straight to
    // HEAVY fires HEAVY, not MINOR.
    private static final int[] TIER_THRESHOLDS = { HEAVY_PCT, DAMAGED_PCT, MINOR_PCT };
    private static final String[] TIER_SUFFIX = {
            " is heavily damaged.",
            " is damaged.",
            " has minor damage."
    };
    private static final Priority[] TIER_PRIORITY = {
            Priority.IMMEDIATE,
            Priority.HIGH,
            Priority.HIGH
    };
    private static final int[] TIER_TTL_LAPS = { 2, 3, 3 };

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

        // Reset rule: damage dropped meaningfully below the current armed level
        // (e.g. front wing repaired during a pit stop). Allows re-firing on a
        // fresh impact at a lower tier.
        boolean frontWingRepaired = false;
        for (int i = 0; i < NUM_PARTS; i++) {
            JsonNode node = pc.get(WIRE_KEYS[i]);
            if (node == null || !node.canConvertToInt()) continue;
            int v = node.asInt();
            if (armed[i] > 0 && v < armed[i] - 1) {
                if (i == 0) frontWingRepaired = true; // index 0 = front wing
                armed[i] = 0;
            }
        }

        // Confirm a front-wing repair (useful in FP to know the car is race-ready).
        if (frontWingRepaired) {
            return Optional.of(new EngineerMessage(
                    Priority.NORMAL,
                    "Front wing replaced, good to go.",
                    tick.wallClockMs(), tick.currentLap(), 2));
        }

        // Walk tiers from most to least severe. Within a tier, walk parts in
        // fixed order. A single message per tick — the worst untriggered
        // crossing wins.
        for (int tier = 0; tier < TIER_THRESHOLDS.length; tier++) {
            int threshold = TIER_THRESHOLDS[tier];
            for (int i = 0; i < NUM_PARTS; i++) {
                JsonNode node = pc.get(WIRE_KEYS[i]);
                if (node == null || !node.canConvertToInt()) continue;
                int v = node.asInt();
                if (v >= threshold && armed[i] < threshold) {
                    armed[i] = threshold;
                    return Optional.of(new EngineerMessage(
                            TIER_PRIORITY[tier],
                            PART_LABEL[i] + TIER_SUFFIX[tier],
                            tick.wallClockMs(), tick.currentLap(), TIER_TTL_LAPS[tier]));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        armedByUid.remove(sessionUid);
    }
}
