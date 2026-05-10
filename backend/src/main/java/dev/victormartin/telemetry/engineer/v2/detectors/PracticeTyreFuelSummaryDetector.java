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
 * "Fronts at X%, rears at Y%, fuel for Z laps." Practice only, every 4 laps.
 * Falls back to "Z kilograms" if {@code fuelLaps} is not reported (some game
 * modes set unlimited fuel and emit 0).
 *
 * Ports v1 detectPracticeTyreFuelSummary.
 */
public class PracticeTyreFuelSummaryDetector implements RadioDetector {

    private static final int LAP_GAP = 4;

    private final Map<String, Integer> lastLapByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "PracticeTyreFuelSummary"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.PRACTICE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        int lap = tick.currentLap();
        if (lap < 2) return Optional.empty();
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

        lastLapByUid.put(tick.sessionUid(), lap);
        return Optional.of(new EngineerMessage(
                Priority.NORMAL,
                "Fronts at " + frontAvg + "% wear, rears at " + rearAvg + "%, " + fuelText + ".",
                tick.wallClockMs(), lap, 3));
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        lastLapByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        lastLapByUid.remove(sessionUid);
    }
}
