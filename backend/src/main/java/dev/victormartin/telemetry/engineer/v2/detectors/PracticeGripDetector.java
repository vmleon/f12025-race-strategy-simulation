package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * Periodic "ambient" grip flavour message during free practice.
 *
 * Replaces v1 detectPracticeGripMessages. v1 woke after lap 2 and re-armed
 * every 5-7 laps — too sparse for a 14-min FP session with frequent spins
 * that kept currentLap low (Phase C bug 3.4).
 *
 * v2 retune:
 * - wake after lap 1 (was 2)
 * - re-arm every 2 laps with ±1 jitter (was 5-7)
 *
 * Gates on {@link PitState#ON_TRACK} so grip messages don't trigger while the
 * player is parked.
 */
public class PracticeGripDetector implements RadioDetector {

    private static final String[] GRIP_MESSAGES = {
            "Grip coming in, keep pushing.",
            "Still warming up, take it easy.",
            "Rear feels settled, good balance.",
            "Fronts biting nicely.",
            "Watch the understeer through the high-speed stuff."
    };

    private static final int RE_ARM_BASE = 2;
    private static final int RE_ARM_JITTER = 2; // result in [RE_ARM_BASE, RE_ARM_BASE + RE_ARM_JITTER - 1]

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();
    private final Random random;

    public PracticeGripDetector() {
        this(new Random());
    }

    /** Test seam — pass a deterministic Random for reproducible test output. */
    public PracticeGripDetector(Random random) {
        this.random = random;
    }

    @Override
    public String name() { return "PracticeGrip"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.PRACTICE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        int lap = tick.currentLap();
        if (lap < 1) return Optional.empty();

        if (s.nextFireLap == 0) {
            s.nextFireLap = lap + RE_ARM_BASE + random.nextInt(RE_ARM_JITTER);
            return Optional.empty();
        }
        if (lap < s.nextFireLap) return Optional.empty();
        if (lap == s.lastFiredLap) return Optional.empty();

        String text = GRIP_MESSAGES[random.nextInt(GRIP_MESSAGES.length)];
        s.lastFiredLap = lap;
        s.nextFireLap = lap + RE_ARM_BASE + random.nextInt(RE_ARM_JITTER);
        return Optional.of(new EngineerMessage(
                Priority.NORMAL, text,
                tick.wallClockMs(), lap, 3));
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
        int lastFiredLap = 0;
        int nextFireLap = 0;
    }
}
