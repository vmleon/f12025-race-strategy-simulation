package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerMessageHelpers;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * Stint-aware greeting fired once per session on the first ON_TRACK tick. Replaces
 * the generic "Radio check" that v1 enqueued at session start — that message
 * routinely expired (60s wall-clock TTL) while the player sat in the garage with
 * no safe zone. By gating on ON_TRACK we guarantee the message lands on the
 * out-lap and includes the fitted compound and (for practice) fuel laps.
 */
public class SessionStartGreetingDetector implements RadioDetector {

    private final Map<String, Boolean> firedByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "SessionStartGreeting"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        if (Boolean.TRUE.equals(firedByUid.get(tick.sessionUid()))) return Optional.empty();
        firedByUid.put(tick.sessionUid(), true);

        JsonNode p = tick.playerCar();
        String compound = p.has("tyre") ? p.get("tyre").asText() : "";
        String compoundPlural = compoundLabel(compound);
        String handling = compoundHandlingNote(compound);
        double fuelLaps = p.has("fuelLaps") ? p.get("fuelLaps").asDouble() : 0.0;

        String text = switch (tick.sessionKind()) {
            case PRACTICE -> practiceGreeting(tick.sessionType(), compoundPlural, handling, fuelLaps);
            case QUALIFYING, SPRINT_QUALIFYING ->
                    qualifyingGreeting(tick.sessionType(), compoundPlural, handling, fuelLaps);
            case RACE, SPRINT_RACE -> "Lights out. " + compoundPlural
                    + " on. Settle in, we'll talk strategy.";
            case TIME_TRIAL -> "Time trial. " + compoundPlural + " on. Push for a clean lap.";
            default -> "Radio check. All systems go.";
        };

        return Optional.of(new EngineerMessage(
                Priority.NORMAL, text,
                tick.wallClockMs(), tick.currentLap(), 3));
    }

    private static String practiceGreeting(int sessionType, String compoundPlural,
                                            String handling, double fuelLaps) {
        String label = switch (sessionType) {
            case 1 -> "P1";
            case 2 -> "P2";
            case 3 -> "P3";
            default -> "Practice";
        };
        String compoundClause = compoundPlural + " fitted"
                + (handling.isEmpty() ? "" : " — " + handling);
        String fuelClause = fuelLaps > 0.0
                ? " Fuel for " + (int) Math.round(fuelLaps) + " laps in the tank."
                : "";
        return label + " underway. " + compoundClause + "." + fuelClause
                + " Push when you have a window.";
    }

    private static String qualifyingGreeting(int sessionType, String compoundPlural,
                                              String handling, double fuelLaps) {
        String label = switch (sessionType) {
            case 5 -> "Q1";
            case 6 -> "Q2";
            case 7 -> "Q3";
            case 8 -> "Short qualifying";
            case 9 -> "One-shot qualifying";
            case 14 -> "Sprint qualifying";
            default -> "Qualifying";
        };
        String compoundClause = compoundPlural + " fitted"
                + (handling.isEmpty() ? "" : " — " + handling);
        String fuelClause = fuelLaps > 0.0
                ? " Fuel for " + (int) Math.round(fuelLaps) + " laps."
                : "";
        return label + " underway. " + compoundClause + "." + fuelClause
                + " Send it on a clear lap.";
    }

    /** One-line handling tip per compound. Returns empty string if unknown. */
    private static String compoundHandlingNote(String abbr) {
        if (abbr == null) return "";
        return switch (abbr) {
            case "S" -> "peak grip after a single warm-up lap, watch for graining";
            case "M" -> "balanced grip, settles after a couple of laps";
            case "H" -> "slow warm-up, expect understeer for the first few laps";
            case "I" -> "intermediate compound, treat them gently in the dry sections";
            case "W" -> "full wet compound, aquaplaning risk above 200";
            default -> "";
        };
    }

    /** "M" → "Mediums", "S" → "Softs", etc. Falls back to "Tyres". */
    private static String compoundLabel(String abbr) {
        String spoken = EngineerMessageHelpers.tyreSpokenName(abbr);
        if ("unknown".equals(spoken)) return "Tyres";
        return EngineerMessageHelpers.capitalize(spoken) + "s";
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        firedByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        firedByUid.remove(sessionUid);
    }
}
