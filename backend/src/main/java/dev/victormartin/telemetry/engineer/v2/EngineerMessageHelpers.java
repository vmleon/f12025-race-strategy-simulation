package dev.victormartin.telemetry.engineer.v2;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Static helpers shared by detectors. Pure functions, no state.
 */
public final class EngineerMessageHelpers {

    /** Rough average lap pace conversion: 200 km/h ≈ 55 m/s. */
    public static final float METRES_PER_SECOND = 55f;

    private EngineerMessageHelpers() {}

    public static String formatTenths(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.floor(rounded)) return String.format("%.0f", rounded);
        return String.format("%.1f", rounded);
    }

    /** "1.4 seconds" / "1 second" / "0.5 seconds" with correct singular/plural. */
    public static String formatSecondsPhrase(double seconds) {
        double rounded = Math.round(seconds * 10) / 10.0;
        String number = formatTenths(seconds);
        boolean singular = rounded == 1.0;
        return number + (singular ? " second" : " seconds");
    }

    public static String formatLapTime(long ms) {
        long minutes = ms / 60000;
        double seconds = (ms % 60000) / 1000.0;
        String secStr = formatTenths(seconds) + " seconds";
        if (minutes > 0) {
            return minutes + (minutes == 1 ? " minute " : " minutes ") + secStr;
        }
        return secStr;
    }

    /** Coarse "X minutes left" / "less than a minute left" for radio chatter. */
    public static String formatSessionTimeLeft(int secondsLeft) {
        if (secondsLeft <= 0) return "";
        if (secondsLeft < 60) return "less than a minute left";
        int minutes = secondsLeft / 60;
        if (minutes == 1) return "about a minute left";
        return minutes + " minutes left";
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String tyreSpokenName(String abbr) {
        if (abbr == null) return "unknown";
        return switch (abbr) {
            case "S" -> "soft";
            case "M" -> "medium";
            case "H" -> "hard";
            case "I" -> "intermediate";
            case "W" -> "wet";
            default -> "unknown";
        };
    }

    public static String compoundDisplayName(int compound) {
        return switch (compound) {
            case 16 -> "Softs";
            case 17 -> "Mediums";
            case 18 -> "Hards";
            case 7 -> "Inters";
            case 8 -> "Wets";
            default -> "Tyres";
        };
    }

    public static JsonNode findCarAtPosition(JsonNode cars, int position) {
        for (JsonNode car : cars) {
            int p = car.has("pos") ? car.get("pos").asInt() : 0;
            if (p == position) return car;
        }
        return null;
    }

    /**
     * Gap in seconds between {@code playerLapDist} and {@code other} (the car
     * ahead on track). Returns -1 if not computable (different lap, missing
     * fields).
     */
    public static float gapToCarSeconds(JsonNode other, int playerLap, float playerLapDist, int trackLength) {
        if (other == null) return -1f;
        int otherLap = other.has("lap") ? other.get("lap").asInt() : 0;
        if (otherLap != playerLap) return -1f;
        float otherDist = other.has("lapDist") ? (float) other.get("lapDist").asDouble() : 0f;
        float gap = otherDist - playerLapDist;
        if (gap < 0) gap += trackLength;
        return gap / METRES_PER_SECOND;
    }
}
