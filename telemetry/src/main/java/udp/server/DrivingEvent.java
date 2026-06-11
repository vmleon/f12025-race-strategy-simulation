package udp.server;

/**
 * A completed driving event emitted by a detector. Carries dynamics + location
 * only; session/car/track context is added by the caller when building the DB row.
 * lapDistanceEndM may be null if the excursion never closed cleanly.
 */
public record DrivingEvent(
        String eventType,        // LOCKUP | WHEELSPIN | SLIDE | BRAKING
        int lapNumber,
        int sectorNumber,
        double lapDistanceStartM,
        Double lapDistanceEndM,
        String locationDetail,   // worst wheel / axle; null for braking
        double peakIntensity,    // raw peak magnitude
        String intensitySignal,  // e.g. wheel_slip_ratio
        long durationMs,
        double entrySpeedKmh,
        double brakePeak,
        double throttlePeak,
        double steerAbsPeak,
        double brakeAtPeak,
        double throttleAtPeak,
        double steerAtPeak,
        long frameIdentifier) {}
