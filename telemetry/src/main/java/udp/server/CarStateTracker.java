package udp.server;

/**
 * Maintains the latest state for each of the 22 cars, updated on every incoming packet.
 * Only the latest values are kept — previous state is overwritten.
 */
public class CarStateTracker {

    // Latest parsed arrays (null until first packet of each type arrives)
    private volatile LapData[] lapData;
    private volatile CarTelemetryData[] telemetryData;
    private volatile CarStatusData[] statusData;
    private volatile CarDamageData[] damageData;
    private volatile SessionData sessionData;

    public void updateLapData(LapData[] data) { this.lapData = data; }
    public void updateTelemetry(CarTelemetryData[] data) { this.telemetryData = data; }
    public void updateStatus(CarStatusData[] data) { this.statusData = data; }
    public void updateDamage(CarDamageData[] data) { this.damageData = data; }
    public void updateSession(SessionData data) { this.sessionData = data; }

    public LapData getLapData(int carIndex) {
        LapData[] snap = lapData;
        return snap != null && carIndex < snap.length ? snap[carIndex] : null;
    }

    public CarTelemetryData getTelemetry(int carIndex) {
        CarTelemetryData[] snap = telemetryData;
        return snap != null && carIndex < snap.length ? snap[carIndex] : null;
    }

    public CarStatusData getStatus(int carIndex) {
        CarStatusData[] snap = statusData;
        return snap != null && carIndex < snap.length ? snap[carIndex] : null;
    }

    public CarDamageData getDamage(int carIndex) {
        CarDamageData[] snap = damageData;
        return snap != null && carIndex < snap.length ? snap[carIndex] : null;
    }

    public SessionData getSession() { return sessionData; }

    /**
     * Reset all state (e.g., on session change).
     */
    public void reset() {
        lapData = null;
        telemetryData = null;
        statusData = null;
        damageData = null;
        sessionData = null;
    }
}
