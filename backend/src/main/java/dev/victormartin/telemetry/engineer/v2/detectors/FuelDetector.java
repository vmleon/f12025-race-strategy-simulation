package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * "Fuel is critical. Lift and coast through the slow corners." Race only.
 *
 * Ports v1 detectFuelLevel.
 */
public class FuelDetector implements RadioDetector {

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "Fuel"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        float fuel = tick.playerCar().has("fuel") ? (float) tick.playerCar().get("fuel").asDouble() : -1f;
        if (fuel < 0 || tick.totalLaps() <= 0) return Optional.empty();

        int remaining = tick.totalLaps() - tick.currentLap() + 1;
        if (remaining <= 0) return Optional.empty();

        if (s.initialFuel < 0 && tick.currentLap() >= 2) {
            s.initialFuel = fuel;
            s.initialFuelLap = tick.currentLap();
        }
        if (s.initialFuel <= 0 || tick.currentLap() <= s.initialFuelLap || s.alertSent) return Optional.empty();

        float lapsElapsed = tick.currentLap() - s.initialFuelLap;
        if (lapsElapsed < 5) return Optional.empty();

        float fuelPerLap = (s.initialFuel - fuel) / lapsElapsed;
        if (fuelPerLap <= 0) return Optional.empty();

        float fuelNeeded = fuelPerLap * remaining;
        if (fuel >= fuelNeeded * 0.97f) return Optional.empty();

        s.alertSent = true;
        return Optional.of(new EngineerMessage(
                Priority.HIGH,
                "Fuel is critical. Lift and coast through the slow corners.",
                tick.wallClockMs(), tick.currentLap(), 3));
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        stateByUid.put(sessionUid, new State());
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        stateByUid.remove(sessionUid);
    }

    private static class State {
        float initialFuel = -1f;
        int initialFuelLap = 0;
        boolean alertSent = false;
    }
}
