package dev.victormartin.telemetry.simulation;

/**
 * Mutable state of a single car during a Monte Carlo iteration.
 * Cloned from RaceSnapshot at the start of each iteration.
 */
public class CarState {

    public final int carIndex;
    public final String driverName;
    public final boolean aiControlled;

    public int position;
    public int tyreCompound;    // 16=soft, 17=medium, 18=hard
    public int tyreAgeLaps;
    public double fuelKg;
    public double fuelBurnPerSectorKg;

    // Damage percentages (0-100)
    public int frontWingDamage;
    public int floorDamage;
    public int engineDamage;

    public int numPitStops;
    public boolean retired;
    public double totalTimeMs;

    // Current sector/lap tracking
    public int currentLap;

    public CarState(int carIndex, String driverName, boolean aiControlled,
                    int position, int tyreCompound, int tyreAgeLaps,
                    double fuelKg, double fuelBurnPerSectorKg,
                    int frontWingDamage, int floorDamage, int engineDamage,
                    int numPitStops, double totalTimeMs, int currentLap) {
        this.carIndex = carIndex;
        this.driverName = driverName;
        this.aiControlled = aiControlled;
        this.position = position;
        this.tyreCompound = tyreCompound;
        this.tyreAgeLaps = tyreAgeLaps;
        this.fuelKg = fuelKg;
        this.fuelBurnPerSectorKg = fuelBurnPerSectorKg;
        this.frontWingDamage = frontWingDamage;
        this.floorDamage = floorDamage;
        this.engineDamage = engineDamage;
        this.numPitStops = numPitStops;
        this.totalTimeMs = totalTimeMs;
        this.currentLap = currentLap;
    }

    public CarState copy() {
        return new CarState(carIndex, driverName, aiControlled,
                position, tyreCompound, tyreAgeLaps,
                fuelKg, fuelBurnPerSectorKg,
                frontWingDamage, floorDamage, engineDamage,
                numPitStops, totalTimeMs, currentLap);
    }

    public String regime() {
        return aiControlled ? "AI" : "PLAYER";
    }
}
