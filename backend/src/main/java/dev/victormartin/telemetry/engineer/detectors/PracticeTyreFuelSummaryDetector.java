package dev.victormartin.telemetry.engineer.detectors;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.CircuitSafeZoneService;
import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.RadioDetector;
import dev.victormartin.telemetry.engineer.SessionKind;

/**
 * "Fronts at X%, rears at Y%, fuel for Z laps." Practice only, once per 4-lap
 * interval. Falls back to "Z kilograms" if {@code fuelLaps} is not reported (some
 * game modes set unlimited fuel and emit 0).
 *
 * Fires in the final safe zone before the line (not at lap rollover), so the
 * housekeeping clears that zone and the lap-complete call owns the start/finish
 * zone as immediate finishing feedback instead of the two competing there.
 */
public class PracticeTyreFuelSummaryDetector implements RadioDetector {

    private static final int LAP_GAP = 4;

    private final CircuitSafeZoneService safeZoneService;
    private final Map<String, Integer> lastLapByUid = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Long>> bestByUid = new ConcurrentHashMap<>();

    public PracticeTyreFuelSummaryDetector(CircuitSafeZoneService safeZoneService) {
        this.safeZoneService = safeZoneService;
    }

    @Override
    public String name() { return "PracticeTyreFuelSummary"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.PRACTICE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        // Accumulate session-best laps every tick (not just when firing) so the pace
        // rank is current when we deliver the summary.
        Map<Integer, Long> bests = bestByUid.computeIfAbsent(tick.sessionUid(), k -> new HashMap<>());
        int playerIdx = PaceRanking.updateBests(bests, tick.cars());

        int lap = tick.currentLap();
        if (lap < 2) return Optional.empty();
        // Deliver in the final sector before the line, not at lap rollover.
        if (!inFinalSafeZone(tick)) return Optional.empty();
        Integer last = lastLapByUid.get(tick.sessionUid());
        if (last != null && lap - last < LAP_GAP) return Optional.empty();

        JsonNode wear = tick.playerCar().get("tyreWear");
        if (wear == null || !wear.isArray() || wear.size() < 4) return Optional.empty();
        int rearAvg = (int) Math.round((wear.get(0).asDouble() + wear.get(1).asDouble()) / 2.0);
        int frontAvg = (int) Math.round((wear.get(2).asDouble() + wear.get(3).asDouble()) / 2.0);

        double fuelLaps = tick.playerCar().has("fuelLaps")
                ? tick.playerCar().get("fuelLaps").asDouble() : 0.0;
        int fuelKg = tick.playerCar().has("fuel")
                ? (int) Math.round(tick.playerCar().get("fuel").asDouble()) : -1;

        String fuelText;
        if (fuelLaps > 0.0) {
            int laps = (int) Math.round(fuelLaps);
            fuelText = "fuel for " + laps + (laps == 1 ? " more lap" : " more laps");
        } else if (fuelKg >= 0) {
            fuelText = "fuel " + fuelKg + " kilograms";
        } else {
            return Optional.empty();
        }

        int rank = PaceRanking.rank(bests, playerIdx);
        String paceText = rank > 0 ? " P" + rank + " on pace." : "";

        lastLapByUid.put(tick.sessionUid(), lap);
        return Optional.of(new EngineerMessage(
                Priority.NORMAL,
                "Fronts at " + frontAvg + "% wear, rears at " + rearAvg + "%, " + fuelText + "." + paceText,
                tick.wallClockMs(), lap, 3));
    }

    /** True when the player is in the last safe zone before the line (the final sector).
     * Unconfigured tracks have no zones → permissive fallback so the summary still fires. */
    private boolean inFinalSafeZone(EngineerTick tick) {
        int finalIdx = safeZoneService.finalZoneIndex(tick.trackId());
        if (finalIdx < 0) return true;
        return safeZoneService.currentZoneIndex(
                tick.trackId(), tick.playerLapDist(), tick.playerSpeedKmh()) == finalIdx;
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        lastLapByUid.remove(sessionUid);
        bestByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        lastLapByUid.remove(sessionUid);
        bestByUid.remove(sessionUid);
    }
}
