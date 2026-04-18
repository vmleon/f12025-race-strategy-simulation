package dev.victormartin.telemetry.engineer.v2.detectors;

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
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * "X closing fast behind, let them through" — fires when the player is on a
 * slow lap and a faster car is approaching from behind.
 *
 * Replaces v1 detectSlowLapTrafficWarning. The v1 detector judged "slow lap"
 * by throttle alone and never gated on pit state, so it fired while the
 * player was parked in the box (zero throttle = "slow"). Phase C bug 3.2.
 *
 * v2 fix: declared {@code appliesToStates = {ON_TRACK}}. The orchestrator
 * never calls this detector outside ON_TRACK, so the throttle-only "slow lap"
 * heuristic can no longer mistake garage-park for an out-lap.
 */
public class SlowLapTrafficWarningDetector implements RadioDetector {

    private static final int THROTTLE_BUFFER_SIZE = 3;
    private static final float SLOW_LAP_THROTTLE_THRESHOLD = 0.40f;
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
        return Set.of(SessionKind.PRACTICE, SessionKind.QUALIFYING,
                SessionKind.SPRINT_QUALIFYING);
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());

        s.throttleBuffer.addLast(tick.playerThrottle());
        while (s.throttleBuffer.size() > THROTTLE_BUFFER_SIZE) {
            s.throttleBuffer.removeFirst();
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
        if (s.throttleBuffer.size() < THROTTLE_BUFFER_SIZE) return false;
        float sum = 0f;
        for (Float f : s.throttleBuffer) sum += f;
        return (sum / s.throttleBuffer.size()) < SLOW_LAP_THROTTLE_THRESHOLD;
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
        final Map<Integer, Long> cooldownByCar = new HashMap<>();
        float previousGapBehind = -1f;
    }
}
