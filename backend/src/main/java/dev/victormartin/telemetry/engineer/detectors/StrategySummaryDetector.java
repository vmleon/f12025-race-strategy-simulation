package dev.victormartin.telemetry.engineer.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.EngineerMessageHelpers;
import dev.victormartin.telemetry.engineer.EngineerTick;
import dev.victormartin.telemetry.engineer.PitState;
import dev.victormartin.telemetry.engineer.RadioDetector;
import dev.victormartin.telemetry.engineer.SessionKind;
import dev.victormartin.telemetry.simulation.RaceSnapshot.PitStrategy.PitStop;
import dev.victormartin.telemetry.simulation.StrategyEvaluation;
import dev.victormartin.telemetry.simulation.StrategyEvaluation.RankedStrategy;

/**
 * Periodic spoken strategy context: the current plan plus the best alternative,
 * e.g. "Current plan: box lap 9 for Mediums. Next best: box lap 9 for Hards."
 *
 * The ranked alternatives are already computed and reach the radio layer, but
 * only the terse box-window line is otherwise spoken. The latest evaluation is
 * pushed in via {@link #setEvaluation}. Announces only when the recommendation
 * materially changes (rank-1/rank-2 pit lap or compound differs from the last
 * spoken plan) and stays quiet through the opening lap (item 8's quiet period).
 */
public class StrategySummaryDetector implements RadioDetector {

    /** Through this lap the standing-start scramble makes strategy chatter pointless. */
    private static final int OPENING_LAP = 1;

    /**
     * Quiet window after the player rejoins from a pit stop. The gap-to-leader (and
     * thus the seeded race time) is transiently distorted by the pit-lane loss, so a
     * strategy evaluated right after the stop can briefly flip to nonsense (e.g. a
     * second stop). Don't speak it until the picture settles.
     */
    private static final long POST_PIT_SETTLE_MS = 12_000;

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "StrategySummary"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(PitState.ON_TRACK); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(SessionKind.RACE, SessionKind.SPRINT_RACE); }

    /** Strategy callback — orchestrator pushes the latest ranked evaluation. */
    public void setEvaluation(String sessionUid, StrategyEvaluation eval) {
        State s = stateByUid.computeIfAbsent(sessionUid, k -> new State());
        s.eval = eval;
    }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        StrategyEvaluation eval = s.eval;
        if (eval == null || eval.strategies() == null || eval.strategies().isEmpty()) {
            return Optional.empty();
        }
        // Opening-lap quiet period: don't update the signature, so the first
        // post-scramble plan still announces once.
        if (tick.currentLap() <= OPENING_LAP) return Optional.empty();

        // Post-pit settle window: arm on the rejoin tick (first ON_TRACK tick after
        // a pit) and stay quiet until it elapses. We don't touch lastSignature, so
        // the settled plan still announces once the window closes.
        if (tick.previousPitState() != PitState.ON_TRACK) {
            s.settleUntilMs = tick.wallClockMs() + POST_PIT_SETTLE_MS;
        }
        if (tick.wallClockMs() < s.settleUntilMs) return Optional.empty();

        RankedStrategy r1 = eval.strategies().get(0);
        RankedStrategy r2 = eval.strategies().size() > 1 ? eval.strategies().get(1) : null;
        Plan p1 = nextStop(r1, tick.currentLap());
        Plan p2 = r2 != null ? nextStop(r2, tick.currentLap()) : null;

        String signature = sig(p1) + "|" + (r2 == null ? "none" : sig(p2));
        if (signature.equals(s.lastSignature)) return Optional.empty();
        s.lastSignature = signature;

        return Optional.of(new EngineerMessage(
                Priority.NORMAL, render(p1, r2 != null, p2, tick),
                tick.wallClockMs(), tick.currentLap(), 2));
    }

    /** First planned stop on or after the current lap; null when the plan runs to the end. */
    private static Plan nextStop(RankedStrategy r, int currentLap) {
        if (r.candidate() == null || r.candidate().stops() == null) return null;
        for (PitStop stop : r.candidate().stops()) {
            if (stop.onLap() >= currentLap) return new Plan(stop.onLap(), stop.newCompound());
        }
        return null;
    }

    private static String sig(Plan p) {
        return p == null ? "end" : p.lap() + ":" + p.compound();
    }

    private static String render(Plan p1, boolean hasRunnerUp, Plan p2, EngineerTick tick) {
        StringBuilder sb = new StringBuilder();
        if (p1 == null) {
            sb.append("Current plan: stay out to the end on ")
              .append(EngineerMessageHelpers.tyreSpokenName(playerTyre(tick))).append(".");
        } else {
            sb.append("Current plan: box lap ").append(p1.lap())
              .append(" for ").append(EngineerMessageHelpers.compoundDisplayName(p1.compound())).append(".");
        }
        if (hasRunnerUp) {
            if (p2 == null) {
                sb.append(" Next best: stay out to the end.");
            } else {
                sb.append(" Next best: box lap ").append(p2.lap())
                  .append(" for ").append(EngineerMessageHelpers.compoundDisplayName(p2.compound())).append(".");
            }
        }
        return sb.toString();
    }

    private static String playerTyre(EngineerTick tick) {
        return tick.playerCar() != null && tick.playerCar().has("tyre")
                ? tick.playerCar().get("tyre").asText() : null;
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        stateByUid.put(sessionUid, new State());
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        stateByUid.remove(sessionUid);
    }

    private record Plan(int lap, int compound) {}

    private static class State {
        volatile StrategyEvaluation eval;
        String lastSignature = "";
        long settleUntilMs = 0;
    }
}
