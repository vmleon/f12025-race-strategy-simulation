package dev.victormartin.telemetry.simulation;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory store of calibration coefficients for a specific track.
 * Coefficients are keyed by (knobName, regime, sectorNumber).
 * Sector-wide coefficients use sectorNumber = -1.
 */
public class Coefficients {

    private final Map<String, Double> store = new HashMap<>();

    public void put(String knobName, String regime, int sector, double value) {
        store.put(key(knobName, regime, sector), value);
    }

    public double get(String knobName, String regime, int sector) {
        Double v = store.get(key(knobName, regime, sector));
        if (v != null) return v;
        // Fall back to track-wide (sector=-1)
        v = store.get(key(knobName, regime, -1));
        if (v != null) return v;
        return 0.0;
    }

    public double get(String knobName, String regime) {
        return get(knobName, regime, -1);
    }

    private static String key(String knobName, String regime, int sector) {
        return knobName + "|" + regime + "|" + sector;
    }

    /**
     * Creates a Coefficients instance with cold-start defaults for both regimes.
     */
    public static Coefficients defaults() {
        var c = new Coefficients();
        for (String regime : new String[]{"PLAYER", "AI"}) {
            c.put("tyre_deg_soft", regime, -1, 0.05);
            c.put("tyre_deg_medium", regime, -1, 0.03);
            c.put("tyre_deg_hard", regime, -1, 0.02);
            c.put("fuel_effect", regime, -1, 0.01);
            c.put("front_wing_damage", regime, -1, 0.02);
            c.put("floor_damage", regime, -1, 0.04);
            c.put("engine_damage", regime, -1, 0.01);
            c.put("dirty_air", regime, -1, 0.30);
            c.put("drs_advantage", regime, -1, -0.20);
            c.put("overtake_probability", regime, -1, 0.15);
            c.put("safety_car_rate", regime, -1, 0.01);
        }
        return c;
    }

    public int size() {
        return store.size();
    }
}
