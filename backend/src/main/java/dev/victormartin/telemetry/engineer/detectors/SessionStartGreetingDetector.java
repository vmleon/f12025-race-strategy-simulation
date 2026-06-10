package dev.victormartin.telemetry.engineer.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.EngineerMessageHelpers;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.RadioDetector;
import dev.victormartin.telemetry.engineer.SessionKind;

/**
 * Stint-aware greeting fired once per session on the out-lap. Replaces
 * a generic "Radio check" at session start — that message
 * routinely expired (60s wall-clock TTL) while the player sat in the garage with
 * no safe zone. By gating on ON_TRACK <em>and</em> the car actually rolling we
 * guarantee the message lands on the out-lap and includes the fitted compound
 * and (for practice) fuel laps.
 *
 * Firing on the very first ON_TRACK tick (lapDist ≈ 0, stationary) raced the iOS
 * client (re)connecting to the new sessionUid: a client that connects after the
 * already-sent greeting never receives it, so the scene-set was published into
 * the void. Waiting until the car is moving leaves time for the client to attach.
 */
public class SessionStartGreetingDetector implements RadioDetector {

    /** Car is considered rolling out of the garage once it exceeds this speed. */
    private static final int OUT_LAP_SPEED_KMH = 30;

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
        // Wait for the out-lap (car rolling) so the client is connected to hear it.
        if (tick.playerSpeedKmh() < OUT_LAP_SPEED_KMH) return Optional.empty();
        firedByUid.put(tick.sessionUid(), true);

        JsonNode p = tick.playerCar();
        String compound = p.has("tyre") ? p.get("tyre").asText() : "";
        String compoundPlural = compoundLabel(compound);
        String handling = compoundHandlingNote(compound);

        String text = switch (tick.sessionKind()) {
            case PRACTICE -> practiceGreeting(tick.sessionType(), compoundPlural, handling);
            case QUALIFYING, SPRINT_QUALIFYING ->
                    qualifyingGreeting(tick.sessionType(), compoundPlural, handling);
            case RACE, SPRINT_RACE -> "Clutch paddle in position. You start on "
                    + compoundPlural.toLowerCase() + ". Settle in, we'll talk strategy.";
            case TIME_TRIAL -> "Time trial. " + compoundPlural + " on. Push for a clean lap.";
            default -> "Radio check. All systems go.";
        };

        return Optional.of(new EngineerMessage(
                Priority.NORMAL, text,
                tick.wallClockMs(), tick.currentLap(), 3));
    }

    private static String practiceGreeting(int sessionType, String compoundPlural,
                                            String handling) {
        String label = switch (sessionType) {
            case 1 -> "P1";
            case 2 -> "P2";
            case 3 -> "P3";
            default -> "Practice";
        };
        String compoundClause = compoundPlural + " fitted"
                + (handling.isEmpty() ? "" : " — " + handling);
        return label + " underway. " + compoundClause + ". Push when you have a window.";
    }

    private static String qualifyingGreeting(int sessionType, String compoundPlural,
                                              String handling) {
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
        return label + " underway. " + compoundClause + ". Send it on a clear lap.";
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
