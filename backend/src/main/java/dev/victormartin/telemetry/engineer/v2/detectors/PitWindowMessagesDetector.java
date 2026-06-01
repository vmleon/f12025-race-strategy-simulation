package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerMessageHelpers;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * "Box window opens in 5 laps" / "Box next lap" / "Box, box, box". Race only.
 *
 * The recommended pit lap and compound are pushed in by the strategy layer
 * via {@link #setRecommendation(String, int, int)}. Ports v1 detectPitWindowMessages.
 */
public class PitWindowMessagesDetector implements RadioDetector {

    private static final float BOX_BOX_LAP_FRACTION = 0.8f;
    enum Kind { NONE, T_MINUS_5, T_MINUS_1, BOX }

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "PitWindowMessages"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE); }

    /** Strategy callback hook — called by orchestrator when a new recommended stop arrives. */
    public void setRecommendation(String sessionUid, int targetLap, int compound) {
        State s = stateByUid.computeIfAbsent(sessionUid, k -> new State());
        s.recommendedLap = targetLap;
        s.recommendedCompound = compound;
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        int target = s.recommendedLap;
        if (target <= 0 || target < tick.currentLap()) return Optional.empty();

        String compound = EngineerMessageHelpers.compoundDisplayName(s.recommendedCompound);
        int playerPits = (tick.playerCar() != null && tick.playerCar().has("pits"))
                ? tick.playerCar().get("pits").asInt() : 0;

        if (target != s.lastAnnouncedTarget) {
            // A missed window: the old target was already a live box call, and the
            // new recommendation is for a later lap. Announce the recovery now —
            // delta to the new target is ≥3, so the normal gates would sit silent.
            boolean recovery = s.lastAnnouncedTarget > 0
                    && s.lastKind.ordinal() >= Kind.T_MINUS_1.ordinal()
                    && target > s.lastAnnouncedTarget
                    && playerPits == s.lastPlayerPits;
            s.lastAnnouncedTarget = target;
            s.lastPlayerPits = playerPits;
            s.lastKind = Kind.NONE;
            if (recovery) {
                return Optional.of(new EngineerMessage(
                        Priority.HIGH,
                        "We've missed that window. New plan, box lap " + target + ", "
                                + compound + " ready.",
                        tick.wallClockMs(), tick.currentLap(), 2));
            }
        }

        int delta = target - tick.currentLap();

        if (delta == 5 && s.lastKind == Kind.NONE) {
            s.lastKind = Kind.T_MINUS_5;
            return Optional.of(new EngineerMessage(
                    Priority.NORMAL,
                    "Box window opens in 5 laps. " + compound + " ready.",
                    tick.wallClockMs(), tick.currentLap(), 2));
        }
        if (delta == 1 && s.lastKind.ordinal() < Kind.T_MINUS_1.ordinal()) {
            s.lastKind = Kind.T_MINUS_1;
            return Optional.of(new EngineerMessage(
                    Priority.HIGH,
                    "Box next lap. " + compound + " ready.",
                    tick.wallClockMs(), tick.currentLap(), 1));
        }
        if (delta == 0 && s.lastKind.ordinal() < Kind.BOX.ordinal()) {
            if (tick.trackLength() > 0 && tick.playerLapDist() >= tick.trackLength() * BOX_BOX_LAP_FRACTION) {
                s.lastKind = Kind.BOX;
                return Optional.of(new EngineerMessage(
                        Priority.IMMEDIATE,
                        "Box, box, box.",
                        tick.wallClockMs(), tick.currentLap(), 1));
            }
        }
        return Optional.empty();
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
        int recommendedLap = -1;
        int recommendedCompound = 0;
        int lastAnnouncedTarget = -1;
        int lastPlayerPits = 0;
        Kind lastKind = Kind.NONE;
    }
}
