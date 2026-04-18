package dev.victormartin.telemetry.engineer.v2;

/**
 * Coarse session category. Maps from F1 25 raw sessionType ints (PacketSessionData).
 *
 * The official appendix in {@code docs/F1 25 Telemetry Output Structures.txt} only
 * documents values 0-13. Empirically the F1 25 game emits 15 for what shows up as a
 * Spanish GP "Race" in the result screen — the appendix is incomplete or the game
 * compresses sprint-weekend sessions into the 14-17 band depending on the chosen
 * weekend structure. We therefore treat 10-12 AND 14-17 as RACE so race-only
 * detectors don't get silenced just because the user picked a sprint format.
 *
 * Mapping:
 *   0       → OTHER (unknown)
 *   1-4     → PRACTICE (FP1, FP2, FP3, Short P)
 *   5-9     → QUALIFYING (Q1, Q2, Q3, Short Q, One-Shot Q)
 *   10-12   → RACE (R, R2, R3)
 *   13      → TIME_TRIAL
 *   14      → QUALIFYING (Sprint Qualifying — semantically a best-lap session,
 *                          so it gets the same detector set as a regular qualifying)
 *   15-17   → RACE (Sprint Race in sprint weekend + Race overloads observed in F1 25
 *                    — best-position effort, race-style messaging)
 *   18+     → OTHER
 *
 * SPRINT_QUALIFYING / SPRINT_RACE enum values are retained because several detectors
 * still list them in {@code appliesToSessions()}; no sessionType currently routes to
 * them, so those branches are dead but harmless.
 */
public enum SessionKind {
    PRACTICE,
    QUALIFYING,
    RACE,
    SPRINT_QUALIFYING,
    SPRINT_RACE,
    TIME_TRIAL,
    OTHER;

    public static SessionKind fromSessionType(int sessionType) {
        if (sessionType >= 1 && sessionType <= 4) return PRACTICE;
        if (sessionType >= 5 && sessionType <= 9) return QUALIFYING;
        if (sessionType >= 10 && sessionType <= 12) return RACE;
        if (sessionType == 13) return TIME_TRIAL;
        if (sessionType == 14) return QUALIFYING;
        if (sessionType >= 15 && sessionType <= 17) return RACE;
        return OTHER;
    }
}
