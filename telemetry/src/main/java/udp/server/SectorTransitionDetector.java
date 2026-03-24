package udp.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects sector transitions by comparing current vs previous sector for each car.
 * When a transition is detected, captures a full snapshot from CarStateTracker.
 */
public class SectorTransitionDetector {

    private static final int NUM_CARS = 22;
    private static final int UNINITIALIZED = -1;

    private final int[] lastSector = new int[NUM_CARS];
    private final int[] lastLap = new int[NUM_CARS];

    public SectorTransitionDetector() {
        reset();
    }

    public void reset() {
        for (int i = 0; i < NUM_CARS; i++) {
            lastSector[i] = UNINITIALIZED;
            lastLap[i] = UNINITIALIZED;
        }
    }

    /**
     * Represents a detected sector transition for one car.
     */
    public record Transition(int carIndex, int completedSector, int lapNumber) {}

    /**
     * Check all 22 cars for sector transitions. Returns list of transitions detected.
     */
    public List<Transition> detect(LapData[] laps) {
        List<Transition> transitions = new ArrayList<>();
        for (int i = 0; i < NUM_CARS; i++) {
            LapData car = laps[i];
            int currentSector = car.sector;
            int currentLap = car.currentLapNum;

            if (lastSector[i] == UNINITIALIZED) {
                // First packet for this car — initialize, no transition
                lastSector[i] = currentSector;
                lastLap[i] = currentLap;
                continue;
            }

            if (currentSector != lastSector[i]) {
                // Sector changed — the PREVIOUS sector was completed
                int completedSector = lastSector[i];
                // Use the lap number associated with the completed sector:
                // - sectors 0,1 completed → same lap as current
                // - sector 2 completed (2→0) → the lap that just finished (lastLap)
                int lapForSnapshot = (completedSector == 2) ? lastLap[i] : currentLap;

                transitions.add(new Transition(i, completedSector, lapForSnapshot));

                lastSector[i] = currentSector;
                lastLap[i] = currentLap;
            } else if (currentLap != lastLap[i]) {
                lastLap[i] = currentLap;
            }
        }
        return transitions;
    }

    /**
     * Build a DbWriter.SectorSnapshot from the current state for a given transition.
     */
    public static DbWriter.SectorSnapshot captureSnapshot(
            long sessionUid, Transition transition, CarStateTracker state, long frameIdentifier) {

        int ci = transition.carIndex();
        LapData lap = state.getLapData(ci);
        CarTelemetryData tel = state.getTelemetry(ci);
        CarStatusData status = state.getStatus(ci);
        CarDamageData damage = state.getDamage(ci);
        SessionData session = state.getSession();

        // Determine sector time based on which sector completed
        long sectorTimeMs = switch (transition.completedSector()) {
            case 0 -> lap != null ? lap.sector1TimeInMS() : 0;
            case 1 -> lap != null ? lap.sector2TimeInMS() : 0;
            case 2 -> {
                // Sector 3 time = lap time - sector1 - sector2
                if (lap != null && lap.lastLapTimeInMS > 0) {
                    yield lap.lastLapTimeInMS - lap.sector1TimeInMS() - lap.sector2TimeInMS();
                }
                yield 0;
            }
            default -> 0;
        };

        long lapTimeMs = lap != null ? lap.currentLapTimeInMS : 0;
        // For sector 2→0 transition (lap completed), use the full lap time
        if (transition.completedSector() == 2 && lap != null) {
            lapTimeMs = lap.lastLapTimeInMS;
        }

        return new DbWriter.SectorSnapshot(
                sessionUid,
                ci,
                transition.lapNumber(),
                transition.completedSector(),
                // Timing
                sectorTimeMs,
                lapTimeMs,
                lap != null ? lap.carPosition : 0,
                // Gaps
                lap != null ? (long) lap.deltaToCarInFrontMinutesPart * 60_000 + lap.deltaToCarInFrontMSPart : 0,
                lap != null ? (long) lap.deltaToRaceLeaderMinutesPart * 60_000 + lap.deltaToRaceLeaderMSPart : 0,
                // Pit/penalties
                lap != null ? lap.pitStatus : 0,
                lap != null ? lap.numPitStops : 0,
                lap != null ? lap.penalties : 0,
                // Validity
                lap != null ? lap.currentLapInvalid : 0,
                lap != null ? lap.cornerCuttingWarnings : 0,
                lap != null ? lap.driverStatus : 0,
                // Speed trap
                lap != null ? lap.speedTrapFastestSpeed : 0,
                // Tyres (compound)
                status != null ? status.actualTyreCompound : 0,
                status != null ? status.visualTyreCompound : 0,
                status != null ? status.tyresAgeLaps : 0,
                // Tyre wear (RL=0, RR=1, FL=2, FR=3)
                damage != null ? damage.tyresWear[0] : 0,
                damage != null ? damage.tyresWear[1] : 0,
                damage != null ? damage.tyresWear[2] : 0,
                damage != null ? damage.tyresWear[3] : 0,
                // Tyre damage
                damage != null ? damage.tyresDamage[0] : 0,
                damage != null ? damage.tyresDamage[1] : 0,
                damage != null ? damage.tyresDamage[2] : 0,
                damage != null ? damage.tyresDamage[3] : 0,
                // Tyre blisters
                damage != null ? damage.tyreBlisters[0] : 0,
                damage != null ? damage.tyreBlisters[1] : 0,
                damage != null ? damage.tyreBlisters[2] : 0,
                damage != null ? damage.tyreBlisters[3] : 0,
                // Car damage
                damage != null ? damage.frontLeftWingDamage : 0,
                damage != null ? damage.frontRightWingDamage : 0,
                damage != null ? damage.rearWingDamage : 0,
                damage != null ? damage.floorDamage : 0,
                damage != null ? damage.diffuserDamage : 0,
                damage != null ? damage.sidepodDamage : 0,
                damage != null ? damage.engineDamage : 0,
                damage != null ? damage.gearBoxDamage : 0,
                // Tyre temps (surface: RL=0, RR=1, FL=2, FR=3)
                tel != null ? tel.tyresSurfaceTemperature[0] : 0,
                tel != null ? tel.tyresSurfaceTemperature[1] : 0,
                tel != null ? tel.tyresSurfaceTemperature[2] : 0,
                tel != null ? tel.tyresSurfaceTemperature[3] : 0,
                // Tyre temps (inner)
                tel != null ? tel.tyresInnerTemperature[0] : 0,
                tel != null ? tel.tyresInnerTemperature[1] : 0,
                tel != null ? tel.tyresInnerTemperature[2] : 0,
                tel != null ? tel.tyresInnerTemperature[3] : 0,
                // Brake temps
                tel != null ? tel.brakesTemperature[0] : 0,
                tel != null ? tel.brakesTemperature[1] : 0,
                tel != null ? tel.brakesTemperature[2] : 0,
                tel != null ? tel.brakesTemperature[3] : 0,
                // Engine temp
                tel != null ? tel.engineTemperature : 0,
                // Fuel/ERS
                status != null ? status.fuelInTank : 0,
                status != null ? status.fuelRemainingLaps : 0,
                status != null ? status.ersDeployMode : 0,
                // DRS
                status != null ? status.drsAllowed : 0,
                status != null ? status.drsActivationDistance : 0,
                // Conditions
                session != null ? session.weather : 0,
                session != null ? session.trackTemperature : 0,
                session != null ? session.airTemperature : 0,
                session != null ? session.safetyCarStatus : 0,
                // Recovery / frame
                0, // recovered = 0 (primary capture)
                frameIdentifier
        );
    }
}
