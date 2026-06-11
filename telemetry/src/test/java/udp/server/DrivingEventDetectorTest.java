package udp.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DrivingEventDetectorTest {

    // wheel order RL, RR, FL, FR
    private DrivingEventDetector.FrameInput input(
            double timeMs, double dist, int lap, int sector, long frameId,
            double brake, double throttle, double steer,
            double localVelX, double localVelY, double chassisYaw,
            float[] wheelSpeed, float[] slipRatio, float[] slipAngle, int speedKmh) {
        return new DrivingEventDetector.FrameInput(
                timeMs, dist, lap, sector, frameId, brake, throttle, steer,
                localVelX, localVelY, chassisYaw, wheelSpeed, slipRatio, slipAngle, speedKmh);
    }

    private float[] w(float a, float b, float c, float d) { return new float[]{a, b, c, d}; }

    /** Collect all events produced across a frame sequence. */
    private List<DrivingEvent> run(DrivingEventDetector d, List<DrivingEventDetector.FrameInput> frames) {
        List<DrivingEvent> all = new ArrayList<>();
        for (var f : frames) all.addAll(d.onFrame(f));
        return all;
    }

    @Test
    void detectsLockupFrontLeftUnderBraking() {
        DrivingEventDetector d = new DrivingEventDetector();
        List<DrivingEventDetector.FrameInput> frames = new ArrayList<>();
        // braking hard, FL (index 2) almost stopped while car does 45 m/s
        for (int i = 0; i < 8; i++) {
            frames.add(input(1000 + i * 50, 100 + i, 3, 0, 10 + i,
                    0.95, 0.0, 0.1, 45, 0, 0,
                    w(44, 44, 5, 44), w(0.1f, 0.1f, -0.85f, 0.1f), w(0, 0, 0, 0), 162));
        }
        // release: brake off, wheel recovers
        frames.add(input(1450, 109, 3, 0, 19, 0.0, 0.0, 0.0,
                44, 0, 0, w(44, 44, 44, 44), w(0, 0, 0, 0), w(0, 0, 0, 0), 158));

        List<DrivingEvent> events = run(d, frames);
        List<DrivingEvent> lockups = events.stream().filter(e -> e.eventType().equals("LOCKUP")).toList();
        assertEquals(1, lockups.size());
        DrivingEvent e = lockups.get(0);
        assertEquals("FL", e.locationDetail());
        assertEquals("wheel_slip_ratio", e.intensitySignal());
        assertEquals(0.95, e.brakePeak(), 1e-6);
        assertEquals(0, e.sectorNumber());
    }

    @Test
    void detectsWheelspinRearUnderThrottle() {
        DrivingEventDetector d = new DrivingEventDetector();
        List<DrivingEventDetector.FrameInput> frames = new ArrayList<>();
        // full throttle, rear-left (index 0) spinning faster than car forward speed (30 m/s)
        for (int i = 0; i < 6; i++) {
            frames.add(input(2000 + i * 50, 200 + i, 5, 1, 40 + i,
                    0.0, 1.0, 0.0, 30, 0, 0,
                    w(40, 31, 30, 30), w(0.6f, 0.1f, 0, 0), w(0, 0, 0, 0), 108));
        }
        frames.add(input(2300, 206, 5, 1, 46, 0.0, 0.5, 0.0,
                33, 0, 0, w(33, 33, 33, 33), w(0, 0, 0, 0), w(0, 0, 0, 0), 119));

        List<DrivingEvent> spins = run(d, frames).stream()
                .filter(e -> e.eventType().equals("WHEELSPIN")).toList();
        assertEquals(1, spins.size());
        assertEquals("RL", spins.get(0).locationDetail());
        assertEquals(1.0, spins.get(0).throttlePeak(), 1e-6);
    }

    @Test
    void detectsSlideAsUndersteerWhenFrontSlipAngleDominates() {
        DrivingEventDetector d = new DrivingEventDetector();
        List<DrivingEventDetector.FrameInput> frames = new ArrayList<>();
        // chassisYaw above gate; front slip angles (FL=2, FR=3) larger than rear -> understeer FRONT
        for (int i = 0; i < 6; i++) {
            frames.add(input(3000 + i * 50, 300 + i, 7, 2, 60 + i,
                    0.2, 0.3, 0.5, 40, 6, 0.2,
                    w(40, 40, 40, 40), w(0, 0, 0, 0), w(0.1f, 0.1f, 0.6f, 0.6f), 144));
        }
        frames.add(input(3300, 306, 7, 2, 66, 0.2, 0.3, 0.0,
                40, 0, 0.0, w(40, 40, 40, 40), w(0, 0, 0, 0), w(0, 0, 0, 0), 144));

        List<DrivingEvent> slides = run(d, frames).stream()
                .filter(e -> e.eventType().equals("SLIDE")).toList();
        assertEquals(1, slides.size());
        assertEquals("FRONT", slides.get(0).locationDetail());
        assertEquals("wheel_slip_angle", slides.get(0).intensitySignal());
    }

    @Test
    void detectsBrakingPointWithPeakBrake() {
        DrivingEventDetector d = new DrivingEventDetector();
        List<DrivingEventDetector.FrameInput> frames = new ArrayList<>();
        float[] noSlip = w(45, 45, 45, 45);
        // brake onset and build to 1.0, then release
        double[] brakes = {0.3, 0.7, 1.0, 0.8, 0.0};
        for (int i = 0; i < brakes.length; i++) {
            frames.add(input(4000 + i * 50, 400 + i, 9, 0, 80 + i,
                    brakes[i], 0.0, 0.0, 45, 0, 0,
                    noSlip, w(0, 0, 0, 0), w(0, 0, 0, 0), 162));
        }

        List<DrivingEvent> braking = run(d, frames).stream()
                .filter(e -> e.eventType().equals("BRAKING")).toList();
        assertEquals(1, braking.size());
        DrivingEvent e = braking.get(0);
        assertEquals(1.0, e.peakIntensity(), 1e-6); // peak brake
        assertEquals(1.0, e.brakePeak(), 1e-6);
        assertEquals("brake", e.intensitySignal());
        assertNull(e.locationDetail());
        assertEquals(400.0, e.lapDistanceStartM(), 1e-6);
    }

    @Test
    void noEventsWhenStationaryOrClean() {
        DrivingEventDetector d = new DrivingEventDetector();
        List<DrivingEventDetector.FrameInput> frames = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // very low speed, no brake/throttle, no slip
            frames.add(input(5000 + i * 50, 0 + i, 1, 0, 90 + i,
                    0.0, 0.0, 0.0, 2, 0, 0,
                    w(2, 2, 2, 2), w(0, 0, 0, 0), w(0, 0, 0, 0), 7));
        }
        assertTrue(run(d, frames).isEmpty());
    }
}
