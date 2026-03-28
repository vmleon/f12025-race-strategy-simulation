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

    public static final int RECOVERED_PRIMARY = 0;
    public static final int RECOVERED_TIER1 = 1;
    public static final int RECOVERED_TIER2 = 2;
    public static final int RECOVERED_TIER3 = 3;

    /**
     * Represents a detected sector transition for one car.
     * @param recovered 0=primary, 1=Tier1 (LapData cumulative), 2=Tier2 (SessionHistory), 3=Tier3 (gap flag)
     */
    public record Transition(int carIndex, int completedSector, int lapNumber, int recovered) {}

    /**
     * Check all 22 cars for sector transitions. Returns list of transitions detected,
     * including recovered transitions for missed sectors.
     */
    public List<Transition> detect(LapData[] laps, SessionHistoryBuffer historyBuffer) {
        List<Transition> transitions = new ArrayList<>();
        for (int i = 0; i < NUM_CARS; i++) {
            LapData car = laps[i];
            int currentSector = car.sector;
            int currentLap = car.currentLapNum;

            if (lastSector[i] == UNINITIALIZED) {
                lastSector[i] = currentSector;
                lastLap[i] = currentLap;
                continue;
            }

            if (currentSector != lastSector[i]) {
                // Calculate how many sectors were skipped
                List<int[]> missed = getMissedSectors(lastSector[i], currentSector, lastLap[i], currentLap);

                for (int[] m : missed) {
                    int completedSector = m[0];
                    int lapForSnapshot = m[1];

                    if (completedSector == lastSector[i]) {
                        // This is the expected next transition — primary capture
                        transitions.add(new Transition(i, completedSector, lapForSnapshot, RECOVERED_PRIMARY));
                    } else {
                        // This sector was missed — try recovery tiers
                        int tier = recoverTier(i, lapForSnapshot, completedSector, car, historyBuffer);
                        transitions.add(new Transition(i, completedSector, lapForSnapshot, tier));
                    }
                }

                lastSector[i] = currentSector;
                lastLap[i] = currentLap;
            } else if (currentLap != lastLap[i]) {
                lastLap[i] = currentLap;
            }
        }
        return transitions;
    }

    /**
     * Determine which sectors were completed between lastSector and currentSector.
     * Returns list of [completedSector, lapNumber] pairs in order.
     */
    private List<int[]> getMissedSectors(int lastSec, int currentSec, int lastLapNum, int currentLapNum) {
        List<int[]> result = new ArrayList<>();
        int sec = lastSec;
        int lap = lastLapNum;

        while (sec != currentSec) {
            int completedSector = sec;
            int lapForSnapshot = (completedSector == 2) ? lap : currentLapNum;
            result.add(new int[]{completedSector, lapForSnapshot});

            sec = (sec + 1) % 3;
            if (sec == 0) {
                lap = currentLapNum; // crossed lap boundary
            }

            // Safety: don't loop more than 3 times
            if (result.size() > 3) break;
        }
        return result;
    }

    /**
     * Determine recovery tier for a missed sector.
     */
    private int recoverTier(int carIndex, int lapNumber, int sectorNumber,
                            LapData currentLap, SessionHistoryBuffer historyBuffer) {
        // Tier 1: LapData cumulative times still available in the current packet
        long sectorTime = switch (sectorNumber) {
            case 0 -> currentLap.sector1TimeInMS();
            case 1 -> currentLap.sector2TimeInMS();
            default -> -1;
        };
        if (sectorTime > 0) {
            return RECOVERED_TIER1;
        }

        // Tier 2: SessionHistory buffer
        if (historyBuffer != null) {
            long histTime = historyBuffer.getSectorTime(carIndex, lapNumber, sectorNumber);
            if (histTime > 0) {
                return RECOVERED_TIER2;
            }
        }

        // Tier 3: Gap flag
        return RECOVERED_TIER3;
    }

    /**
     * Build a DbWriter.SectorSnapshot from the current state for a given transition.
     * Uses the transition's recovery tier and resolves sector time from the appropriate source.
     */
    public static DbWriter.SectorSnapshot captureSnapshot(
            long sessionUid, Transition transition, CarStateTracker state,
            SessionHistoryBuffer historyBuffer, long frameIdentifier) {

        int ci = transition.carIndex();
        int recovered = transition.recovered();
        LapData lap = state.getLapData(ci);
        CarTelemetryData tel = (recovered < RECOVERED_TIER3) ? state.getTelemetry(ci) : null;
        CarStatusData status = (recovered < RECOVERED_TIER3) ? state.getStatus(ci) : null;
        CarDamageData damage = (recovered < RECOVERED_TIER3) ? state.getDamage(ci) : null;
        SessionData session = (recovered < RECOVERED_TIER3) ? state.getSession() : null;

        // Determine sector time based on recovery tier
        long sectorTimeMs = resolveSectorTime(transition, lap, historyBuffer);

        long lapTimeMs = lap != null ? lap.currentLapTimeInMS : 0;
        if (transition.completedSector() == 2 && lap != null) {
            lapTimeMs = lap.lastLapTimeInMS;
        }

        return new DbWriter.SectorSnapshot(
                sessionUid,
                ci,
                transition.lapNumber(),
                transition.completedSector(),
                sectorTimeMs,
                lapTimeMs,
                lap != null ? lap.carPosition : 0,
                lap != null ? (long) lap.deltaToCarInFrontMinutesPart * 60_000 + lap.deltaToCarInFrontMSPart : 0,
                lap != null ? (long) lap.deltaToRaceLeaderMinutesPart * 60_000 + lap.deltaToRaceLeaderMSPart : 0,
                lap != null ? lap.pitStatus : 0,
                lap != null ? lap.numPitStops : 0,
                lap != null ? lap.penalties : 0,
                lap != null ? lap.currentLapInvalid : 0,
                lap != null ? lap.cornerCuttingWarnings : 0,
                lap != null ? lap.driverStatus : 0,
                lap != null ? lap.speedTrapFastestSpeed : 0,
                status != null ? status.actualTyreCompound : 0,
                status != null ? status.visualTyreCompound : 0,
                status != null ? status.tyresAgeLaps : 0,
                damage != null ? damage.tyresWear[0] : 0,
                damage != null ? damage.tyresWear[1] : 0,
                damage != null ? damage.tyresWear[2] : 0,
                damage != null ? damage.tyresWear[3] : 0,
                damage != null ? damage.tyresDamage[0] : 0,
                damage != null ? damage.tyresDamage[1] : 0,
                damage != null ? damage.tyresDamage[2] : 0,
                damage != null ? damage.tyresDamage[3] : 0,
                damage != null ? damage.tyreBlisters[0] : 0,
                damage != null ? damage.tyreBlisters[1] : 0,
                damage != null ? damage.tyreBlisters[2] : 0,
                damage != null ? damage.tyreBlisters[3] : 0,
                damage != null ? damage.frontLeftWingDamage : 0,
                damage != null ? damage.frontRightWingDamage : 0,
                damage != null ? damage.rearWingDamage : 0,
                damage != null ? damage.floorDamage : 0,
                damage != null ? damage.diffuserDamage : 0,
                damage != null ? damage.sidepodDamage : 0,
                damage != null ? damage.engineDamage : 0,
                damage != null ? damage.gearBoxDamage : 0,
                tel != null ? tel.tyresSurfaceTemperature[0] : 0,
                tel != null ? tel.tyresSurfaceTemperature[1] : 0,
                tel != null ? tel.tyresSurfaceTemperature[2] : 0,
                tel != null ? tel.tyresSurfaceTemperature[3] : 0,
                tel != null ? tel.tyresInnerTemperature[0] : 0,
                tel != null ? tel.tyresInnerTemperature[1] : 0,
                tel != null ? tel.tyresInnerTemperature[2] : 0,
                tel != null ? tel.tyresInnerTemperature[3] : 0,
                tel != null ? tel.brakesTemperature[0] : 0,
                tel != null ? tel.brakesTemperature[1] : 0,
                tel != null ? tel.brakesTemperature[2] : 0,
                tel != null ? tel.brakesTemperature[3] : 0,
                tel != null ? tel.engineTemperature : 0,
                status != null ? status.fuelInTank : 0,
                status != null ? status.fuelRemainingLaps : 0,
                status != null ? status.ersDeployMode : 0,
                status != null ? status.drsAllowed : 0,
                status != null ? status.drsActivationDistance : 0,
                session != null ? session.weather : 0,
                session != null ? session.trackTemperature : 0,
                session != null ? session.airTemperature : 0,
                session != null ? session.safetyCarStatus : 0,
                session != null ? session.sessionType : 0,
                recovered,
                frameIdentifier
        );
    }

    /**
     * Resolve sector time from the best available source based on recovery tier.
     */
    private static long resolveSectorTime(Transition transition, LapData lap,
                                          SessionHistoryBuffer historyBuffer) {
        int sector = transition.completedSector();

        // Primary / Tier 1: use LapData cumulative times
        if (transition.recovered() <= RECOVERED_TIER1 && lap != null) {
            return switch (sector) {
                case 0 -> lap.sector1TimeInMS();
                case 1 -> lap.sector2TimeInMS();
                case 2 -> {
                    if (lap.lastLapTimeInMS > 0) {
                        yield lap.lastLapTimeInMS - lap.sector1TimeInMS() - lap.sector2TimeInMS();
                    }
                    yield 0;
                }
                default -> 0;
            };
        }

        // Tier 2: SessionHistory buffer
        if (transition.recovered() == RECOVERED_TIER2 && historyBuffer != null) {
            long time = historyBuffer.getSectorTime(transition.carIndex(), transition.lapNumber(), sector);
            return time > 0 ? time : 0;
        }

        // Tier 3: no timing data available
        return 0;
    }
}
