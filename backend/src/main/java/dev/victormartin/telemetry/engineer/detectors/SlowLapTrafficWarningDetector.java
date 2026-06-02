package dev.victormartin.telemetry.engineer.detectors;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.RadioDetector;
import dev.victormartin.telemetry.engineer.SessionKind;

/**
 * "X closing fast behind, let them through" — fires when the player is on a
 * slow lap and a faster car is approaching from behind.
 *
 * A throttle-only "slow lap" heuristic with no pit gate would fire while the
 * player is parked in the box (zero throttle = "slow"). Phase C bug 3.2.
 *
 * Design:
 * - declared {@code appliesToStates = {ON_TRACK}} so the throttle-only "slow
 *   lap" heuristic can't mistake garage-park for an out-lap.
 * - PRACTICE only. Qualifying flying laps dip below 40 % throttle on every
 *   slow corner, so this detector consistently misfired on hot laps in any
 *   Q format (one-shot Q is especially silly — only the player is on track).
 * - "slow lap" now requires a sustained 12-sample window AND a low average
 *   speed (a flying lap at any circuit averages well above 160 km/h).
 * - the car behind must actually be moving on track (not a parked AI).
 */
public class SlowLapTrafficWarningDetector implements RadioDetector {

    private static final int SAMPLE_BUFFER_SIZE = 12;
    private static final float SLOW_LAP_THROTTLE_THRESHOLD = 0.30f;
    private static final float SLOW_LAP_SPEED_THRESHOLD_KMH = 160f;
    private static final int CAR_BEHIND_MIN_SPEED_KMH = 50;
    private static final float GAP_THRESHOLD_SEC = 4.0f;
    private static final long COOLDOWN_MS = 15_000L;
    private static final float METRES_PER_SECOND = 55f;

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "SlowLapTrafficWarning"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() {
        return Set.of(SessionKind.PRACTICE);
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());

        s.throttleBuffer.addLast(tick.playerThrottle());
        s.speedBuffer.addLast((float) tick.playerSpeedKmh());
        while (s.throttleBuffer.size() > SAMPLE_BUFFER_SIZE) {
            s.throttleBuffer.removeFirst();
        }
        while (s.speedBuffer.size() > SAMPLE_BUFFER_SIZE) {
            s.speedBuffer.removeFirst();
        }

        if (!isOnSlowLap(s)) {
            s.previousGapBehind = -1f;
            return Optional.empty();
        }

        JsonNode carBehind = null;
        for (JsonNode car : tick.cars()) {
            int pos = car.has("pos") ? car.get("pos").asInt() : 0;
            if (pos == tick.playerPos() + 1) { carBehind = car; break; }
        }
        if (carBehind == null) {
            s.previousGapBehind = -1f;
            return Optional.empty();
        }

        // Sanity-check the car behind: must be on the same lap, moving at racing
        // speed, and not in the pit lane. In one-shot quali (now blocked above)
        // the "behind" slot is filled by a parked AI; that misfire stays blocked
        // here for any other session where AIs sit out.
        int behindPitStatus = carBehind.has("pitStatus") ? carBehind.get("pitStatus").asInt() : 0;
        int behindSpeed = carBehind.has("speed") ? carBehind.get("speed").asInt() : 0;
        if (behindPitStatus != 0 || behindSpeed < CAR_BEHIND_MIN_SPEED_KMH) {
            s.previousGapBehind = -1f;
            return Optional.empty();
        }

        int playerLap = tick.currentLap();
        int behindLap = carBehind.has("lap") ? carBehind.get("lap").asInt() : 0;
        if (playerLap != behindLap) {
            s.previousGapBehind = -1f;
            return Optional.empty();
        }

        float behindLapDist = carBehind.has("lapDist") ? (float) carBehind.get("lapDist").asDouble() : 0f;
        float gap = tick.playerLapDist() - behindLapDist;
        if (gap < 0) gap += tick.trackLength();
        float gapSeconds = gap / METRES_PER_SECOND;

        boolean closing = s.previousGapBehind > 0 && gapSeconds < s.previousGapBehind;
        s.previousGapBehind = gapSeconds;
        if (gapSeconds >= GAP_THRESHOLD_SEC) return Optional.empty();
        if (!closing) return Optional.empty();

        int behindIdx = carBehind.has("idx") ? carBehind.get("idx").asInt() : -1;
        long now = tick.wallClockMs();
        Long lastFired = s.cooldownByCar.get(behindIdx);
        if (lastFired != null && (now - lastFired) < COOLDOWN_MS) return Optional.empty();

        String behindName = carBehind.has("name") ? carBehind.get("name").asText() : "Car behind";
        s.cooldownByCar.put(behindIdx, now);
        return Optional.of(new EngineerMessage(
                Priority.HIGH,
                behindName + " closing fast behind, let them through.",
                now, tick.currentLap(), 2));
    }

    private static boolean isOnSlowLap(State s) {
        if (s.throttleBuffer.size() < SAMPLE_BUFFER_SIZE) return false;
        float throttleSum = 0f;
        for (Float f : s.throttleBuffer) throttleSum += f;
        float avgThrottle = throttleSum / s.throttleBuffer.size();
        if (avgThrottle >= SLOW_LAP_THROTTLE_THRESHOLD) return false;

        float speedSum = 0f;
        for (Float f : s.speedBuffer) speedSum += f;
        float avgSpeed = speedSum / Math.max(1, s.speedBuffer.size());
        return avgSpeed < SLOW_LAP_SPEED_THRESHOLD_KMH;
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        stateByUid.put(sessionUid, new State());
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        stateByUid.remove(sessionUid);
    }

    private static class State {
        final Deque<Float> throttleBuffer = new ArrayDeque<>();
        final Deque<Float> speedBuffer = new ArrayDeque<>();
        final Map<Integer, Long> cooldownByCar = new HashMap<>();
        float previousGapBehind = -1f;
    }
}
