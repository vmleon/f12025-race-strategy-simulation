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
    // Latest non-zero live S1/S2 (ms) seen for each car's current lap. The third
    // sector completes at the start/finish line, where the live LapData packet has
    // already reset S1/S2 to 0 for the new lap — so subtracting them there yields the
    // whole lap time. We cache them while the car is mid-lap and subtract from the
    // completed lap time to recover the true third-sector time.
    private final long[] lapSector1Ms = new long[NUM_CARS];
    private final long[] lapSector2Ms = new long[NUM_CARS];

    public SectorTransitionDetector() {
        reset();
    }

    public void reset() {
        for (int i = 0; i < NUM_CARS; i++) {
            lastSector[i] = UNINITIALIZED;
            lastLap[i] = UNINITIALIZED;
            lapSector1Ms[i] = 0;
            lapSector2Ms[i] = 0;
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
    public record Transition(int carIndex, int completedSector, int lapNumber, int recovered,
                             long thirdSectorMs) {
        /** @param thirdSectorMs pre-resolved third-sector time (completed lap − cached
         *  S1 − S2), or -1 when not applicable. Only consulted for a sector-2
         *  primary/Tier1 capture, where the live LapData S1/S2 have reset at the line. */
        public Transition(int carIndex, int completedSector, int lapNumber, int recovered) {
            this(carIndex, completedSector, lapNumber, recovered, -1L);
        }
    }

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
                cacheLiveSectorTimes(i, car);
                continue;
            }

            if (currentSector != lastSector[i]) {
                // Calculate how many sectors were skipped
                List<int[]> missed = getMissedSectors(lastSector[i], currentSector, lastLap[i], currentLap);

                for (int[] m : missed) {
                    int completedSector = m[0];
                    int lapForSnapshot = m[1];
                    // The third sector finishes at the line, where the live S1/S2 have
                    // already reset — recover it from the cached pre-reset times.
                    long thirdMs = completedSector == 2 ? thirdSectorFromCache(i, car) : -1L;
                    int tier = completedSector == lastSector[i]
                            ? RECOVERED_PRIMARY  // expected next transition — primary capture
                            : recoverTier(i, lapForSnapshot, completedSector, car, historyBuffer);
                    transitions.add(new Transition(i, completedSector, lapForSnapshot, tier, thirdMs));
                }

                lastSector[i] = currentSector;
                lastLap[i] = currentLap;
            } else if (currentLap != lastLap[i]) {
                lastLap[i] = currentLap;
            }

            // Cache after handling the transition so the value consumed above is the
            // pre-crossing one; only non-zero values overwrite, so the reset frame (S1/S2
            // both 0) leaves the just-completed lap's times intact for the capture.
            cacheLiveSectorTimes(i, car);
        }
        return transitions;
    }

    /** Remember the latest non-zero live S1/S2 for this car's current lap. */
    private void cacheLiveSectorTimes(int carIndex, LapData lap) {
        long s1 = lap.sector1TimeInMS();
        if (s1 > 0) lapSector1Ms[carIndex] = s1;
        long s2 = lap.sector2TimeInMS();
        if (s2 > 0) lapSector2Ms[carIndex] = s2;
    }

    /** Third-sector time = completed lap time − cached S1 − S2, or -1 if unavailable. */
    private long thirdSectorFromCache(int carIndex, LapData lap) {
        long lapMs = lap.lastLapTimeInMS;
        long s1 = lapSector1Ms[carIndex];
        long s2 = lapSector2Ms[carIndex];
        if (lapMs > 0 && s1 > 0 && s2 > 0) {
            long third = lapMs - s1 - s2;
            if (third > 0) return third;
        }
        return -1L;
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
                    // Cached pre-reset S1/S2 give the true third sector. The live
                    // subtraction below is only a fallback — at the line S1/S2 read 0,
                    // so it returns the whole lap time (the bug this replaces).
                    if (transition.thirdSectorMs() > 0) {
                        yield transition.thirdSectorMs();
                    }
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
