package dev.victormartin.telemetry.engineer.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.EngineerMessageHelpers;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.RadioDetector;
import dev.victormartin.telemetry.engineer.SessionKind;

/**
 * "Box window opens in 5 laps" / "Box next lap" / "Box, box, box". Race only.
 *
 * The recommended pit lap and compound are pushed in by the strategy layer
 * via {@link #setRecommendation(String, int, int)}.
 */
public class PitWindowMessagesDetector implements RadioDetector {

    // Pit entry is late in the lap at most circuits, so the final sector is the
    // "commit now" point on the planned stop lap.
    private static final int FINAL_SECTOR = 2;
    // Matches PerCornerWearDetector's "finished" threshold: at/above this a corner
    // is past the cliff, so "box window opens in 5 laps" is no longer credible.
    private static final int HIGH_WEAR_PCT = 37;
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
            boolean pitted = playerPits != s.lastPlayerPits;
            boolean recovery = s.lastAnnouncedTarget > 0
                    && s.lastKind.ordinal() >= Kind.T_MINUS_1.ordinal()
                    && target > s.lastAnnouncedTarget
                    && !pitted;
            s.lastAnnouncedTarget = target;
            s.lastPlayerPits = playerPits;
            // Reset the announced-threshold ladder only for a genuinely new window:
            // a fresh stint after a pit, or a recovery re-plan. On a plain target
            // drift within the same stint, keep lastKind so we don't re-announce an
            // already-given threshold (e.g. "opens in 5 laps" twice a few laps apart).
            if (pitted || recovery) {
                s.lastKind = Kind.NONE;
            }
            if (recovery) {
                return Optional.of(new EngineerMessage(
                        Priority.HIGH,
                        "We've missed that window. New plan, box lap " + target + ", "
                                + compound + " ready.",
                        tick.wallClockMs(), tick.currentLap(), 2));
            }
        }

        int delta = target - tick.currentLap();

        if (delta == 5 && s.lastKind == Kind.NONE && !tyresFinished(tick.playerCar())) {
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
            // Commit call on the planned stop lap, on entering the final sector —
            // unless we're already in the pit lane. Fires once per stop (Kind.BOX).
            if (tick.pitState() == PitState.ON_TRACK && playerSector(tick) >= FINAL_SECTOR) {
                s.lastKind = Kind.BOX;
                return Optional.of(new EngineerMessage(
                        Priority.IMMEDIATE,
                        "Box this lap. " + compound + " ready.",
                        tick.wallClockMs(), tick.currentLap(), 1));
            }
        }
        return Optional.empty();
    }

    private static int playerSector(EngineerTick tick) {
        return tick.playerCar() != null && tick.playerCar().has("sector")
                ? tick.playerCar().get("sector").asInt() : 0;
    }

    /** True when any corner is at/over the "finished" wear threshold. */
    private static boolean tyresFinished(JsonNode playerCar) {
        JsonNode wear = playerCar != null ? playerCar.get("tyreWear") : null;
        if (wear == null || !wear.isArray() || wear.size() < 4) return false;
        for (int i = 0; i < 4; i++) {
            if ((int) wear.get(i).asDouble() >= HIGH_WEAR_PCT) return true;
        }
        return false;
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
