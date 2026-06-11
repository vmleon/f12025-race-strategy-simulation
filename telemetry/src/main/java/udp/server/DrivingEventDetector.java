package udp.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects driver-performance events on the player car, one excursion = one event.
 * Triggers use units-grounded signals (wheelSpeed vs localVelocityX, chassisYaw,
 * brake); the undocumented slip values are stored as raw intensity for later
 * calibration. Thresholds are deliberately loose — tune from collected data.
 *
 * Wheel order: RL=0, RR=1, FL=2, FR=3. Rear wheels = {0,1}; front = {2,3}.
 */
public class DrivingEventDetector {

    // ── tunable thresholds (loose by design) ────────────────────────────
    private static final double MIN_SPEED_MS = 8.0;     // ~30 km/h; below this, skip
    private static final double BRAKE_GATE = 0.15;      // lock-up requires braking
    private static final double LOCK_RATIO = 0.80;      // wheel < 80% of forward speed = locking
    private static final double THROTTLE_GATE = 0.50;   // wheelspin requires throttle
    private static final double SPIN_RATIO = 1.10;      // wheel > 110% of forward speed = spinning
    private static final double YAW_GATE_RAD = 0.10;    // ~5.7° sideslip = sliding
    private static final double BRAKE_ONSET = 0.15;     // braking-point open threshold (independent of BRAKE_GATE; equal value is coincidental)
    private static final double BRAKE_RELEASE = 0.05;   // braking-point close threshold
    private static final long MIN_EVENT_MS = 50;        // discard flicker

    private static final String[] WHEEL = {"RL", "RR", "FL", "FR"};

    /** All per-frame inputs the detector needs for the player car. */
    public record FrameInput(
            double sessionTimeMs, double lapDistanceM, int lapNumber, int sectorNumber, long frameIdentifier,
            double brake, double throttle, double steer,
            double localVelocityX, double localVelocityY, double chassisYaw,
            float[] wheelSpeed, float[] wheelSlipRatio, float[] wheelSlipAngle, int speedKmh) {}

    private final ExcursionTracker lockup = new ExcursionTracker("LOCKUP", "wheel_slip_ratio", MIN_EVENT_MS);
    private final ExcursionTracker wheelspin = new ExcursionTracker("WHEELSPIN", "wheel_slip_ratio", MIN_EVENT_MS);
    private final ExcursionTracker slide = new ExcursionTracker("SLIDE", "wheel_slip_angle", MIN_EVENT_MS);
    private final ExcursionTracker braking = new ExcursionTracker("BRAKING", "brake", MIN_EVENT_MS);

    private boolean brakingIsOpen = false; // braking excursion latch (the tracker only knows active/inactive)

    /** Feed one player-car frame; returns events that closed on this frame (0–4). */
    public List<DrivingEvent> onFrame(FrameInput in) {
        List<DrivingEvent> out = new ArrayList<>(1);
        ExcursionTracker.Frame f = new ExcursionTracker.Frame(
                in.sessionTimeMs(), in.lapDistanceM(), in.brake(), in.throttle(), in.steer(),
                in.speedKmh(), in.lapNumber(), in.sectorNumber(), in.frameIdentifier());

        double refSpeed = in.localVelocityX();
        boolean moving = refSpeed >= MIN_SPEED_MS;

        // ── LOCKUP: any wheel rotating well below forward speed under braking ──
        boolean lockActive = false;
        double lockIntensity = 0;
        String lockDetail = null;
        if (moving && in.brake() > BRAKE_GATE) {
            int worst = -1;
            double worstRatio = LOCK_RATIO;
            for (int i = 0; i < 4; i++) {
                double ratio = in.wheelSpeed()[i] / refSpeed;
                if (ratio < worstRatio) { worstRatio = ratio; worst = i; }
            }
            if (worst >= 0) {
                lockActive = true;
                lockIntensity = Math.abs(in.wheelSlipRatio()[worst]);
                lockDetail = WHEEL[worst];
            }
        }
        addIfPresent(out, lockup.update(lockActive, lockIntensity, lockDetail, f));

        // ── WHEELSPIN: a rear wheel rotating well above forward speed under throttle ──
        boolean spinActive = false;
        double spinIntensity = 0;
        String spinDetail = null;
        if (moving && in.throttle() > THROTTLE_GATE) {
            int worst = -1;
            double worstRatio = SPIN_RATIO;
            for (int i = 0; i <= 1; i++) { // rear wheels RL, RR
                double ratio = in.wheelSpeed()[i] / refSpeed;
                if (ratio > worstRatio) { worstRatio = ratio; worst = i; }
            }
            if (worst >= 0) {
                spinActive = true;
                spinIntensity = Math.abs(in.wheelSlipRatio()[worst]);
                spinDetail = WHEEL[worst];
            }
        }
        addIfPresent(out, wheelspin.update(spinActive, spinIntensity, spinDetail, f));

        // ── SLIDE: chassis sideslip beyond gate; axle by front/rear slip-angle magnitude ──
        boolean slideActive = false;
        double slideIntensity = 0;
        String slideDetail = null;
        if (moving && Math.abs(in.chassisYaw()) > YAW_GATE_RAD) {
            double front = Math.max(Math.abs(in.wheelSlipAngle()[2]), Math.abs(in.wheelSlipAngle()[3]));
            double rear = Math.max(Math.abs(in.wheelSlipAngle()[0]), Math.abs(in.wheelSlipAngle()[1]));
            slideActive = true;
            if (front >= rear) { slideDetail = "FRONT"; slideIntensity = front; }
            else { slideDetail = "REAR"; slideIntensity = rear; }
        }
        addIfPresent(out, slide.update(slideActive, slideIntensity, slideDetail, f));

        // ── BRAKING point: open at onset, stay open while trailing above release ──
        boolean brakeActive = in.brake() > BRAKE_ONSET || (brakingIsOpen && in.brake() > BRAKE_RELEASE);
        brakingIsOpen = brakeActive;
        addIfPresent(out, braking.update(brakeActive, in.brake(), null, f));

        return out;
    }

    private static void addIfPresent(List<DrivingEvent> out, DrivingEvent e) {
        if (e != null) out.add(e);
    }

    /** Reset all detector state (e.g. on session change). Drops any in-progress excursions. */
    public void reset() {
        lockup.reset();
        wheelspin.reset();
        slide.reset();
        braking.reset();
        brakingIsOpen = false;
    }
}
