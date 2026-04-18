package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * "10 laps remaining" / "5 laps to go" / "Last lap". Race only. Fires on the
 * lap-up tick where laps remaining == 10, 5, or 1.
 *
 * Ports v1 detectLapCountdown.
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
            case 5 -> "5 laps to go. Bring it home.";
            case 1 -> "Last lap. Give it everything you've got.";
            default -> null;
        };
        if (text == null) return Optional.empty();
        return Optional.of(new EngineerMessage(
                Priority.IMMEDIATE, text,
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
