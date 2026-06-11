package udp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExcursionTrackerTest {

    private ExcursionTracker.Frame frame(double timeMs, double dist, double brake,
                                         double throttle, double steer, double speedKmh,
                                         int lap, int sector, long frameId) {
        return new ExcursionTracker.Frame(timeMs, dist, brake, throttle, steer, speedKmh, lap, sector, frameId);
    }

    @Test
    void emitsOneEventOnCloseWithMaxAndAtPeakInputs() {
        ExcursionTracker t = new ExcursionTracker("LOCKUP", "wheel_slip_ratio", 50);

        // open: active, intensity 0.5, brake 0.6
        assertNull(t.update(true, 0.5, "FL", frame(1000, 100, 0.6, 0.0, 0.1, 200, 3, 0, 10)));
        // peak: intensity 0.9 (new max) — captures at-peak inputs here; brake rises to 0.95 (running max)
        assertNull(t.update(true, 0.9, "FL", frame(1100, 110, 0.95, 0.0, 0.3, 198, 3, 0, 11)));
        // still active but lower intensity; brake drops — running max stays 0.95
        assertNull(t.update(true, 0.4, "FL", frame(1200, 120, 0.7, 0.0, 0.2, 195, 3, 0, 12)));
        // close
        DrivingEvent e = t.update(false, 0.0, "FL", frame(1300, 130, 0.0, 0.0, 0.0, 190, 3, 0, 13));

        assertNotNull(e);
        assertEquals("LOCKUP", e.eventType());
        assertEquals("wheel_slip_ratio", e.intensitySignal());
        assertEquals(0.9, e.peakIntensity(), 1e-9);
        assertEquals("FL", e.locationDetail());
        assertEquals(100.0, e.lapDistanceStartM(), 1e-9);
        assertEquals(130.0, e.lapDistanceEndM(), 1e-9);
        assertEquals(300, e.durationMs());          // 1300 - 1000
        assertEquals(200.0, e.entrySpeedKmh(), 1e-9); // speed at open frame
        assertEquals(0.95, e.brakePeak(), 1e-9);     // running max
        assertEquals(0.95, e.brakeAtPeak(), 1e-9);   // brake at intensity-peak frame
        assertEquals(0.3, e.steerAtPeak(), 1e-9);    // signed steer at intensity-peak frame
        assertEquals(0.3, e.steerAbsPeak(), 1e-9);   // max |steer| over excursion
    }

    @Test
    void dropsExcursionShorterThanMinDuration() {
        ExcursionTracker t = new ExcursionTracker("LOCKUP", "wheel_slip_ratio", 50);
        assertNull(t.update(true, 0.5, "FL", frame(1000, 100, 0.6, 0, 0, 200, 3, 0, 10)));
        // closes after only 30ms (< 50ms min) -> dropped
        assertNull(t.update(false, 0.0, "FL", frame(1030, 101, 0.0, 0, 0, 199, 3, 0, 11)));
    }

    @Test
    void noEventWhenNeverActive() {
        ExcursionTracker t = new ExcursionTracker("SLIDE", "wheel_slip_angle", 50);
        assertNull(t.update(false, 0.0, null, frame(1000, 100, 0, 0, 0, 200, 3, 0, 10)));
    }
}
