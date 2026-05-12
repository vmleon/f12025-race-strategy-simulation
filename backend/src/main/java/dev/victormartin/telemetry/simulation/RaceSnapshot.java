package dev.victormartin.telemetry.simulation;

import java.util.List;

/**
 * Immutable snapshot of the current race state, used as input to the simulation.
 */
public record RaceSnapshot(
        int trackId,
        int totalLaps,
        int currentLap,
        int currentSector,          // 0, 1, or 2 — next sector to simulate from
        int weather,                // 0=clear, 1=light cloud, etc.
        int trackTemp,
        int airTemp,
        boolean safetyCar,
        List<CarSnapshot> cars,
        PitStrategy pitStrategy     // strategy to evaluate for the player car
) {

    public record TyreSet(
            int visualTyreCompound,
            boolean available,
            int wear,
            int lifeSpan,
            int usableLife,
            int lapDeltaTime,
            boolean fitted
    ) {}

    public record CarSnapshot(
            int carIndex,
            String driverName,
            boolean aiControlled,
            int position,
            int tyreCompound,
            int tyreAgeLaps,
            double fuelKg,
            double fuelBurnPerSectorKg,
            int frontWingDamage,
            int floorDamage,
            int engineDamage,
            int numPitStops,
            double totalTimeMs,
            List<TyreSet> tyreSets,
            List<Long> recentLapTimesMs,
            long baselineLapMs
    ) {}

    public record PitStrategy(
            int targetCarIndex,
            List<PitStop> stops
    ) {
        public record PitStop(int onLap, int newCompound) {}
    }
}
