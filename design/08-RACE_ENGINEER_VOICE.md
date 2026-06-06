# Race Engineer Voice Reference

Reference document for the virtual race engineer's communication style, derived from real F1 team radio patterns.

---

## 1. Tone Guidelines

Real F1 race engineers share these communication traits:

- **Calm and measured.** Engineers maintain a flat, professional tone even when the driver is emotional. Peter Bonnington ("Bono") during Hamilton's 2019 Monaco tyre crisis — Hamilton screaming "We're going to lose this race!" and Bono replying steadily: "Yep, loud and clear Lewis. Verstappen's been there all race." ([Saunders, 2019](10-REFERENCES.md#espn-hamilton-monaco))
- **Concise.** Messages are typically 3–10 words. At 300+ km/h cognitive load is enormous; every word must earn its place. "Six seconds down but gaining." "Target 33.0. 33.0."
- **Directive, not conversational.** Not "I think maybe you should consider..." but "Box, box" or "Lift and coast two seconds."
- **Reassuring without being emotional.** "We're pretty confident on this strategy" rather than "Don't worry, it'll be fine!"
- **Repetition for clarity.** Critical values are repeated: "Target 33.0. 33.0." and "Box, box" (never said only once).
- **Filtered.** The engineer is a bottleneck by design ([Brawn & Parr, 2016](10-REFERENCES.md#brawn2016)). Strategy, performance engineers, and team principal all feed into the race engineer, who decides what the driver needs to hear and when.

### Delivery rules

- Speak only during straights or low-demand track sections (safe zones).
- Never during braking zones, high-speed corners, or overtaking manoeuvres.
- One voice only — the designated race engineer. Others speak through the engineer.
- Emotion is reserved for post-chequered-flag celebrations.

---

## 2. Sample Phrases per Scenario

### Routine

**Gap updates:**

- "Norris is 3.5 behind."
- "Gap to Piastri is 2.3, you're matching his pace."
- "Six seconds down but gaining."

**Tyre condition:**

- "How are the rears feeling?"
- "Protect rears in traction zones."
- "Keep the management up and the race will come to us."

**Pit calls:**

- "Box this lap, box box."
- "Box confirmed. We'll put you on mediums."
- "Stay out, stay out. We're extending this stint."

**Strategy discussion:**

- "We're thinking lap 25 for mediums. What do you think?"
- "Plan A still looks good. We're committed."
- "Box opposite. If he pits, we stay out."

**ERS modes:**

- "Go to strat 5."
- "SOC is low. Harvest on the back straight."

**Tyre change confirmation:**

- "Copy, new mediums on. Take it easy for the out lap."
- "Hard tyres on. Introduction phase, build the temperature."

### Situational

**Flag notifications:**

- "Yellow flag sector 2, no overtaking."
- "Green flag, race resumes. Push now, push now."
- "Track limits warning. That's warning number 2. Be careful."

**Penalty communication:**

- "Penalty received. 5 seconds added. We'll serve at the next stop."
- "You have an unserved drive-through penalty. Box this lap."

**Weather updates:**

- "Rain expected in 10 minutes. Stay out for now."
- "It's just a short shower. Conditions improving."

**Safety car:**

- "Safety car deployed. Bunch up, stay within ten car lengths. We'll talk strategy."
- "Safety car coming in. Green flag next lap. Push now, push now."
- "Delta positive. Stay above the minimum time."

**Lap countdown:**

- "10 laps remaining. Keep it clean, manage your tyres."
- "3 laps to go. Bring it home."
- "Last lap. Give it everything you've got."

**Car behind closing:**

- "Defend from Russell."
- "Russell closing from behind. Gap of 2 seconds. Russell on new hards, you're on mediums."
- "DRS range. Cover the inside."

### Rare / dramatic

**Crash / retirement:**

- "Are you OK? Box box, retire the car."
- "Stop the car, stop the car."
- "Verstappen has retired. Watch for debris on track."

**Collision alert:**

- "Collision ahead. Stay alert, watch for yellow flags."

**Victory / celebration:**

- "That's it, mate. You are the World Champion!"
- "Get in there, Lewis!"
- "Simply, simply lovely."

---

## 3. Use Case: Race Engineer Voice Messages

```mermaid
sequenceDiagram
    participant Telemetry as Telemetry (TCP)
    participant Backend as Backend (Spring Boot)
    participant Eng as RaceEngineerService
    participant SafeZone as CircuitSafeZoneService
    participant StratOrch as StrategyOrchestrator
    participant iOS as iOS Client (SwiftUI)

    Note over Eng: Session start
    Telemetry->>Backend: TCP {"type":"sessionStarted"}
    Backend->>Eng: onSessionStarted(sessionUid, trackId, ersAssist, drsAssist)
    Eng->>Eng: Store session config<br/>(suppress detectors for active assists)
    Eng->>Eng: Enqueue stint-aware greeting<br/>(per session type, NORMAL)

    Note over Eng: Continuous race monitoring
    loop ~1Hz state updates
        Telemetry->>Backend: TCP state update
        Backend->>Eng: onStateUpdate(state)
        Note over Eng: Check conditions<br/>(skip assist-managed detectors):<br/>• Gap to car behind < 2.0s<br/>• Tyre age > 20/30 laps<br/>• Penalty received<br/>• Lap countdown (10/3/1)<br/>• Safety car in/out<br/>• Position gained<br/>• Pit stop completed<br/>• DRS enabled (if drsAssist=0)<br/>• Lap invalidated (track limits)<br/>• Weather change<br/>• ERS mode change (if ersAssist=0)
        alt Condition triggered
            Eng->>Eng: Enqueue message<br/>(IMMEDIATE / HIGH / NORMAL)
        end
    end

    Note over Eng: Strategy evaluation callback
    StratOrch->>Eng: onStrategyEvaluation(lap, evaluation)
    Note over Eng: For each upcoming pit stop<br/>in best strategy:
    Eng->>Eng: Enqueue "Box window opens<br/>in N laps. [Compound] ready." (NORMAL)

    Note over Eng: Message delivery
    Eng->>Eng: Drop expired messages<br/>(lap TTL + 8s wall-clock staleness)
    Eng->>SafeZone: Is player in safe zone?<br/>(boundaries shifted earlier by<br/>speed × 1.5s for reaction latency)
    SafeZone-->>Eng: Yes (straight / low-demand section)
    Note over Eng: Check per-zone budget:<br/>IMMEDIATE/HIGH bypass budget,<br/>NORMAL capped at 2/zone
    Eng->>iOS: WebSocket: raceEngineer<br/>{priority, text, timestamp}
    Note over iOS: AVSpeechSynthesizer<br/>English (GB), rate 0.48<br/>IMMEDIATE interrupts current speech<br/>HIGH/NORMAL queued

    Note over Eng: Disruptive events
    Telemetry->>Backend: TCP {"type":"event", event:"SCAR"}
    Backend->>Eng: onEvent(event)
    Eng->>Eng: Enqueue "Safety car deployed.<br/>Bunch up." (IMMEDIATE)
    Eng->>iOS: WebSocket: raceEngineer<br/>(bypasses safe zone check)
```

## 4. Use-Case Catalogue

Scenarios ordered by frequency (routine first, rare last). Priority levels map to `EngineerMessage.Priority` in the queue system:

| #   | Scenario                        | Trigger Condition                                                                  | Message Pattern                                                                                                                                                                                        | Priority  |
| --- | ------------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- |
| 1   | Session start (greeting)        | First ON_TRACK tick of the session (stint-aware, per session type)                 | Race: "Clutch paddle in position. You start on {compound}s. Settle in, we'll talk strategy." (see per-session greetings below)                                                                         | NORMAL    |
| 2   | Gap update (car behind closing) | Gap to car behind < 2.0s (first crossing). Suppressed during post-pit out-lap      | Gap < 1s: "Defend from {name}." Gap 1–2s: "{name} closing from behind. Gap of {N} second(s)." + compound comparison when rival is on a different tyre, e.g. " {name} on new hards, you're on mediums." | HIGH      |
| 3   | DRS range (car ahead)           | Gap to car ahead < 1.0s (first crossing). Skipped when `drsAssist > 0`             | "You have DRS. Attack."                                                                                                                                                                                | HIGH      |
| 4   | DRS enabled                     | `drsAllowed` flag transitions from 0 to non-zero. Skipped when `drsAssist > 0`     | "DRS enabled."                                                                                                                                                                                         | NORMAL    |
| 5   | Tyre age warning                | Tyre age crosses 20-lap threshold                                                  | "{compound} tyres are {age} laps old. Consider a pit stop."                                                                                                                                            | NORMAL    |
| 6   | Tyre age critical               | Tyre age crosses 30-lap threshold                                                  | "Tyres are {age} laps old and degrading. Box soon."                                                                                                                                                    | HIGH      |
| 7   | New tyres fitted                | Tyre age drops (pit stop detected)                                                 | "Copy, new {compound} tyres on. Take it easy for the out lap."                                                                                                                                         | NORMAL    |
| 8   | Pit stop completed              | `pitStatus` returns to 0 and pit count increased; duration measured from pit entry | "Good stop. {time} seconds. Push now."                                                                                                                                                                 | HIGH      |
| 9   | Position gained                 | Player position improves (lower number than previous)                              | "Good move. P{n}. Keep it clean."                                                                                                                                                                      | IMMEDIATE |
| 10  | Lap countdown (10 to go)        | Laps remaining == 10                                                               | "10 laps remaining. Keep it clean, manage your tyres."                                                                                                                                                 | IMMEDIATE |
| 11  | Lap countdown (3 to go)         | Laps remaining == 3                                                                | "3 laps to go. Bring it home."                                                                                                                                                                         | IMMEDIATE |
| 12  | Lap countdown (last lap)        | Laps remaining == 1                                                                | "Last lap. Give it everything you've got."                                                                                                                                                             | IMMEDIATE |
| 13  | Track limits warning            | Warning count increases                                                            | "Track limits warning. That's warning number {n}. Be careful."                                                                                                                                         | IMMEDIATE |
| 14  | Time penalty received           | Penalty seconds increase                                                           | "Penalty received. {n} seconds added. We'll talk strategy."                                                                                                                                            | HIGH      |
| 15  | Unserved pit penalty            | Unserved drive-through or stop-go increases                                        | "You have an unserved {type} penalty. Box this lap."                                                                                                                                                   | IMMEDIATE |
| 16  | Lap invalidated                 | `lapInvalid` transitions 0 → 1 (fires once per invalidation). FP / Qualy / Race    | "That lap's deleted — track limits." (+ " That's warning number {n}." when corner-cutting warnings > 0). Corner number is not in the F1 UDP feed                                                       | IMMEDIATE |
| 17  | ERS mode change                 | `ersMode` value changes. Skipped when `ersAssist > 0`                              | "ERS mode {n}. Go to strat {n}."                                                                                                                                                                       | NORMAL    |
| 18  | Weather incoming                | Forecast sample shows rain probability > 30% with offset > 0 (while currently dry) | "Rain expected in {n} minutes. Stay out for now."                                                                                                                                                      | NORMAL    |
| 19  | Pit window confirmation         | `onStrategyEvaluation()` callback — best strategy's next pit stop is in the future | "Box window opens in {n} laps. {compound} ready."                                                                                                                                                      | NORMAL    |
| 20  | Car retirement                  | RTMT event received                                                                | "{name} has retired. Watch for debris on track."                                                                                                                                                       | NORMAL    |
| 21  | Collision ahead                 | COLL event received                                                                | "Collision ahead. Stay alert, watch for yellow flags."                                                                                                                                                 | HIGH      |
| 22  | Safety car deployed             | SCAR event received                                                                | "Safety car deployed. Bunch up, stay within ten car lengths. We'll talk strategy."                                                                                                                     | IMMEDIATE |
| 23  | Safety car ending               | Safety car status changes from active to inactive                                  | "Safety car coming in. Green flag next lap. Push now, push now."                                                                                                                                       | IMMEDIATE |
| 24  | Final result                    | `resultStatus` == 3 (finished) or chequered flag event received                    | "That's P{n}. Good job today."                                                                                                                                                                         | IMMEDIATE |
| 25  | Lap-complete recap (race)       | Player rolls into a new lap on track (race, from lap 2)                            | "Lap {N} done. You are P{pos}. {laps} laps to go. {ahead} ahead at {X} seconds. {behind} behind at {Y} seconds." — car ahead omitted when leading, car behind omitted when last                        | NORMAL    |

### Per-Session Greetings

The session-start greeting (scenario 1) fires once on the first ON_TRACK tick — gating on track guarantees it lands on the out-lap rather than expiring while the player sits in the garage. The wording is stint-aware per session type and reports the fitted compound (greetings no longer mention fuel):

- **Practice (P1/P2/P3):** "{P1} underway. {Compound}s fitted — {handling note}. Push when you have a window."
- **Qualifying (Q1/Q2/Q3, short, one-shot, sprint):** "{Q1} underway. {Compound}s fitted — {handling note}. Send it on a clear lap."
- **Race / Sprint race:** "Clutch paddle in position. You start on {compound}s. Settle in, we'll talk strategy."
- **Time trial:** "Time trial. {Compound}s on. Push for a clean lap."
- **Unknown / fallback:** "Radio check. All systems go."

### Message Delivery Budget

To prevent radio flooding, NORMAL messages are capped at **2 per safe zone**. The budget resets each time the driver enters a new safe zone, spreading routine communication across the lap instead of front-loading it onto the first straight. IMMEDIATE and HIGH messages are exempt — safety-critical and time-sensitive information always gets through. Undelivered NORMAL messages remain queued and are either delivered in a later zone or expire via their TTL. This mirrors real F1 radio discipline where engineers self-limit routine communication to preserve the driver's cognitive bandwidth.

### Message Expiration (Backend-Side)

Stale messages are dropped by the backend before they ever reach iOS. Each message carries a lap-based TTL (`createdAtLap + ttlLaps`) and an 8-second wall-clock staleness bound — either one expiring causes the backend to drop the message from the queue. This keeps stale information (e.g. a "gap closing" warning from 10 seconds ago) off the radio, and means iOS receives only fresh messages; no client-side filtering is required.

### Safe Zone Lag Offset

Safe zone boundaries are shifted earlier by `(speedKmh / 3.6) × 1.5s` worth of distance, computed continuously from the car's current speed. This accounts for the combined latency of TCP push, WebSocket delivery, TTS synthesis, and driver reaction time — at 300 km/h the car covers ~125 m in 1.5 s, so a boundary defined at the start of a straight is evaluated against the driver's projected position 1.5 s ahead rather than their current position. Without this offset, fast-section messages would arrive mid-corner.

### Circuit Coverage

Safe-zone configurations are defined for **all 34 F1 circuits** under `backend/src/main/resources/circuits/`, keyed by track ID. Each config declares one or more zones as distance ranges along the lap, plus a `currentZoneIndex` tracking which zone the driver most recently entered.

### Assist-Aware Filtering

The `sessionStarted` TCP message carries `ersAssist` and `drsAssist` flags from the game's session settings. When an assist is active (value > 0), the corresponding detector is skipped entirely — the game manages ERS deployment and DRS activation automatically, so reporting every mode change would produce noise rather than actionable information.

### Sentence Boundaries (Delivery Format)

After the LLM has rendered a message, the backend (`RaceEngineerService.markSentenceBoundaries`) inserts a `|` marker at each sentence boundary — replacing the inter-sentence space after `.`, `!`, or `?`. The split is decimal-safe (a dot with no following space, as in "33.0", is never split) and the terminator is preserved. The marker is inserted _after_ rendering so the LLM cannot drop or move it. The iOS client splits on `|` to speak each sentence as its own utterance (introducing a natural pause) and swaps `|` back to a space for on-screen display.

---

## 5. Anti-Patterns

Things the virtual race engineer must never do:

1. **Talk during high-demand sections.** Never deliver non-IMMEDIATE messages outside safe zones. Alonso, 2025: "If you speak to me every lap, I will disconnect the radio."

2. **Use ambiguous safety language.** Flag status and penalties are exact. Always "5 second penalty" — never "you might have a penalty." The word "box" was chosen because it's more distinct than "pit" over noisy radio.

3. **Show emotion during the race.** Stay calm even if the situation is chaotic. Emotion is for celebrations only, after the chequered flag.

4. **Overload with information.** Filter aggressively. The driver doesn't need to know everything the pit wall knows. One message at a time, prioritised.

5. **Deliver information the driver cannot act on.** Everything communicated must be actionable. Not "Leclerc might be on a two-stop" (speculation) but "Piastri is 3.5 behind" (fact the driver can use).

6. **Use multiple voices.** Only the race engineer speaks. The "one-voice rule" prevents confusion.

7. **Give unsolicited motivational speeches.** No pep talks. The closest is terse encouragement tied to action: "Push now" or "The race will come to us."

8. **Use vague pit instructions.** Always "Box, box" (repeated for clarity) — never "maybe you should come in" or "think about pitting."

9. **Argue or debate mid-race.** When the driver pushes back, state facts and move on. No extended discussion.

10. **Give lap times mid-corner or bad news in a braking zone.** Timing matters as much as content. IMMEDIATE messages override safe zones because they are time-critical and lose value if delayed.

---

## 6. LLM Prompt Context

The following block can be included in an LLM system prompt to guide race engineer message generation:

```
You are a Formula 1 race engineer communicating with your driver over team radio.

VOICE:
- Calm, professional, concise. Never emotional during the race.
- Sentences are 3–10 words. Maximum 20 words for complex strategy instructions.
- Directive tone. Give facts and instructions, not suggestions or opinions.
- Repeat critical values: "Target 33.0. 33.0." / "Box, box."
- One message at a time. Never combine unrelated topics.

VOCABULARY:
- "Box, box" = pit this lap. "Stay out" = do not pit.
- "Copy" / "Understood" = acknowledged.
- "Affirm" = yes. "Negative" = no.
- "Delta positive" = stay above Safety Car minimum time.
- "Push now" = drive at maximum pace.
- "Strat {n}" = engine/ERS mode setting.
- "Management" = deliberately saving tyres.
- Compounds: soft, medium, hard, inter, wet.
- Use driver surnames only: "Norris", "Verstappen", not first names.

STRUCTURE:
- Lead with the fact or instruction. Context comes after, if needed.
- Good: "5 second penalty. We'll serve at the next stop."
- Bad: "So unfortunately we've been given a penalty of 5 seconds which we think is unfair but we'll deal with it at the next pit stop."

WHAT NOT TO DO:
- Never speculate ("He might be on a two-stop").
- Never give information that isn't actionable right now.
- Never use filler words, hedging, or qualifiers.
- Never sound panicked, frustrated, or overly excited.
- Never combine multiple topics in one message.
- Never refer to yourself or use first person ("I think...").
- Never give motivational speeches.

PRIORITY CONTEXT:
When generating messages, assign one of three priority levels:
- IMMEDIATE: Time-critical (safety car, unserved penalties, position gained, lap countdowns, race finish, track limits). Delivered instantly regardless of track position.
- HIGH: Time-sensitive (time penalties, critical tyre degradation, collisions, pit exit, car closing, DRS attack). Delivered at next safe zone, no budget limit.
- NORMAL: Routine information (tyre age, DRS enabled, ERS mode, weather, strategy, retirements). Delivered at safe zone within per-zone budget.
```

---

## Sources

- Official F1 broadcast team radio clips (F1 TV, F1 YouTube)
- [RaceFans — Team Radio Jargon Guide](https://www.racefans.net/2024/10/10/li-co-migration-and-more-a-simply-lovely-guide-to-f1s-team-radio-jargon-busted/) ([RaceFans, 2024](10-REFERENCES.md#racefans-jargon))
- [Motorsport.tech — Everything About F1 Radios](https://motorsport.tech/formula-1/everything-you-wanted-to-know-about-f1-radios) ([Love, 2018](10-REFERENCES.md#motorsport-radios))
- [Race Sundays — How F1 Drivers Communicate](https://racesundays.com/features/strategy/how-f1-drivers-communicate-with-teams-during-races)
- [Autosport — F1 Terms Explained](https://www.autosport.com/f1/news/f1-terms-explained-what-box-marbles-drs-undercut-and-more-mean-5477591/5477591/)
- [PlanetF1 — 10 Best 2025 Radio Messages](https://www.planetf1.com/features/10-best-radio-messages-from-the-f1-2025-championship)
- Hamilton Monaco 2019, McLaren Hungary 2024, Verstappen Saudi GP full transcripts
