package udp.server;

/**
 * Tracks a single open→peak→close excursion for one event type. Fed per frame:
 * whether the trigger is active, the current raw intensity, a detail label, and
 * frame context. Returns a completed DrivingEvent on the frame the excursion
 * closes (active=false while open), or null otherwise. Excursions shorter than
 * minDurationMs are discarded to suppress flicker.
 */
public class ExcursionTracker {

    /** Per-frame context shared by all detectors. */
    public record Frame(
            double sessionTimeMs, double lapDistanceM,
            double brake, double throttle, double steer,
            double speedKmh, int lapNumber, int sectorNumber, long frameIdentifier) {}

    private final String eventType;
    private final String intensitySignal;
    private final long minDurationMs;

    private boolean open;
    private double openTimeMs;
    private double openDistanceM;
    private double entrySpeedKmh;
    private int lapNumber;
    private int sectorNumber;

    private double peakIntensity;
    private String detailAtPeak;
    private double brakeAtPeak;
    private double throttleAtPeak;
    private double steerAtPeak;

    private double brakePeak;
    private double throttlePeak;
    private double steerAbsPeak;

    private double lastDistanceM;
    private double lastTimeMs;
    private long lastFrameId;

    public ExcursionTracker(String eventType, String intensitySignal, long minDurationMs) {
        this.eventType = eventType;
        this.intensitySignal = intensitySignal;
        this.minDurationMs = minDurationMs;
    }

    public DrivingEvent update(boolean active, double intensity, String detail, Frame f) {
        if (active) {
            if (!open) {
                open = true;
                openTimeMs = f.sessionTimeMs();
                openDistanceM = f.lapDistanceM();
                entrySpeedKmh = f.speedKmh();
                lapNumber = f.lapNumber();
                sectorNumber = f.sectorNumber();
                peakIntensity = Double.NEGATIVE_INFINITY;
                brakePeak = 0;
                throttlePeak = 0;
                steerAbsPeak = 0;
            }
            // running maxima of inputs
            brakePeak = Math.max(brakePeak, f.brake());
            throttlePeak = Math.max(throttlePeak, f.throttle());
            steerAbsPeak = Math.max(steerAbsPeak, Math.abs(f.steer()));
            // capture inputs at the intensity peak
            if (intensity > peakIntensity) {
                peakIntensity = intensity;
                detailAtPeak = detail;
                brakeAtPeak = f.brake();
                throttleAtPeak = f.throttle();
                steerAtPeak = f.steer();
            }
            lastDistanceM = f.lapDistanceM();
            lastTimeMs = f.sessionTimeMs();
            lastFrameId = f.frameIdentifier();
            return null;
        }

        if (!open) {
            return null;
        }
        // closing
        open = false;
        long durationMs = (long) (f.sessionTimeMs() - openTimeMs);
        if (durationMs < minDurationMs) {
            return null; // flicker, discard
        }
        return new DrivingEvent(
                eventType, lapNumber, sectorNumber, openDistanceM, f.lapDistanceM(),
                detailAtPeak, peakIntensity, intensitySignal, durationMs, entrySpeedKmh,
                brakePeak, throttlePeak, steerAbsPeak,
                brakeAtPeak, throttleAtPeak, steerAtPeak, f.frameIdentifier());
    }
}
