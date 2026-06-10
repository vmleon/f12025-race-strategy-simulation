package dev.victormartin.telemetry.engineer.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.RadioDetector;
import dev.victormartin.telemetry.engineer.SessionKind;

/**
 * "10 laps remaining" / "3 laps to go" / "Last lap". Race only. Fires on the
 * lap-up tick where laps remaining == 10, 3, or 1. The 10-lap line is NORMAL
 * (safe-zone gated); the closing laps are HIGH. IMMEDIATE is reserved for
 * safety-critical messages.
 */
public class LapCountdownDetector implements RadioDetector {

    private final Map<String, Integer> lastLapByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "LapCountdown"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        if (tick.totalLaps() <= 0) return Optional.empty();
        int currentLap = tick.currentLap();
        Integer last = lastLapByUid.put(tick.sessionUid(), currentLap);
        if (last != null && currentLap <= last) return Optional.empty();

        int remaining = tick.totalLaps() - currentLap + 1;
        String text = switch (remaining) {
            case 10 -> "10 laps remaining. Keep it clean, manage your tyres.";
            case 3 -> "3 laps to go. Bring it home.";
            case 1 -> "Last lap. Give it everything you've got.";
            default -> null;
        };
        if (text == null) return Optional.empty();
        // IMMEDIATE is reserved for safety-critical calls (safety car / damage). The
        // 10-lap line is generic filler → NORMAL (deliver in a safe zone); the closing
        // laps are notable but still not safety-critical → HIGH.
        Priority priority = remaining == 10 ? Priority.NORMAL : Priority.HIGH;
        return Optional.of(new EngineerMessage(
                priority, text,
                tick.wallClockMs(), currentLap, 1));
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        lastLapByUid.remove(sessionUid);
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        lastLapByUid.remove(sessionUid);
    }
}
