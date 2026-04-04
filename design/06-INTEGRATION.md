# Integration Points Between Components

## System Overview

```mermaid
graph TD
    Game["F1 2025 Game"] -- "UDP 20Hz" --> Telemetry["Telemetry — Plain Java"]
    Telemetry -- "JDBC writes" --> DB["Oracle DB 26ai"]
    Telemetry -- "TCP push ~1Hz" --> Backend["Backend — Spring Boot"]
    DB -- "JDBC reads" --> Backend
    DB -- "TxEventQ" --> Backend
    Backend -- WebSocket --> Portal["Portal — Angular"]
    Backend -- WebSocket --> iOS["iOS Client — SwiftUI"]
    Simulator["Simulator — Python"] -- "SIMULATION_REQUEST" --> DB
    DB -- "SIMULATION_RESULT" --> Backend
    Backend -- "STRATEGY_REQUEST" --> DB
    DB -- "STRATEGY_RESULT" --> Backend
    Simulator -- "STRATEGY_REQUEST" --> DB
    Simulator -- "reads coefficients" --> DB
```

## Use Case: Data Ingestion & Persistence

```mermaid
sequenceDiagram
    participant Game as F1 2025 Game
    participant Telemetry as Telemetry (Java)
    participant DB as Oracle DB

    Game->>Telemetry: UDP packets (20Hz)
    activate Telemetry
    Note over Telemetry: Parse packet header<br/>(format, year, packet type)

    alt Packet 1 — Session
        Telemetry->>DB: INSERT sessions (once per session)
    else Packet 4 — Participants
        Telemetry->>DB: INSERT participants (once per session)
    else Packet 2 — LapData
        Note over Telemetry: Detect sector transition<br/>(sector field 0→1, 1→2, 2→0)
        Telemetry->>DB: INSERT sector_snapshots<br/>(batch: 20 cars × 3 sectors)
    else Packet 3 — Event
        Telemetry->>DB: INSERT session_events<br/>(SCAR, RTMT, PENA, COLL, etc.)
    else Packet 12 — TyreSets
        Telemetry->>DB: INSERT tyre_sets<br/>(session start + on pit stop)
    else Packet 8 — FinalClassification
        Telemetry->>DB: INSERT final_classifications<br/>(once at session end)
    else Packets 6, 7, 10 — Telemetry/Status/Damage
        Note over Telemetry: Update in-memory state only<br/>(snapshotted on next sector transition)
    end
    deactivate Telemetry
```

## Use Case: Live Race Dashboard

```mermaid
sequenceDiagram
    participant Game as F1 2025 Game
    participant Telemetry as Telemetry (Java)
    participant Backend as Backend (Spring Boot)
    participant Portal as Portal (Angular)

    Game->>Telemetry: UDP packets (20Hz)
    Note over Telemetry: Update in-memory RaceState<br/>(22 cars: position, lap, sector,<br/>tyres, fuel, damage, weather)

    loop ~1Hz (TcpSender thread)
        Telemetry->>Backend: TCP JSON-line<br/>{"type":"state", cars:[...],<br/>weather, safetyCarStatus, ...}
        Backend->>Portal: WebSocket broadcast<br/>(type: "state")
    end

    alt Session lifecycle
        Telemetry->>Backend: TCP {"type":"sessionStarted", sessionUid, trackId, ersAssist, drsAssist}
        Backend->>Portal: WebSocket (type: "sessionStarted")
        Note over Backend: Auto-assign driver<br/>if only one exists in DB
    else Disruptive event
        Telemetry->>Backend: TCP {"type":"event", event:"SCAR"|"RTMT"|...}
        Backend->>Portal: WebSocket (type: "event")
    end
```

## 1. Telemetry → Database (JDBC, direct write)

The telemetry server writes parsed UDP data directly to Oracle via JDBC + Oracle UCP connection pool ([Oracle Corporation, 2025](10-REFERENCES.md#oracle-ucp)). No intermediary.

- **Protocol:** JDBC (Oracle thin driver)
- **Direction:** Telemetry → Oracle
- **Data:** `sessions`, `participants`, `sector_snapshots`, `session_events`, `tyre_sets`, `final_classifications` (all 6 data tables from `04-DATABASE_DESIGN.md`)
- **Rate:** ~60 rows/lap for sector_snapshots (3 sectors x 20 cars), plus sporadic event/metadata rows
- **Batching:** JDBC `addBatch()` / `executeBatch()` for sector_snapshots (all 20 cars snapshotted at each sector boundary)
- **Connection config:** `telemetry/config.properties` — host, port, service name, credentials

## 2. Telemetry → Backend (TCP push, JSON-lines)

Live race state and session lifecycle events flow from telemetry to backend over a persistent TCP socket.

- **Protocol:** Plain TCP socket, newline-delimited JSON (`\n`-separated)
- **Direction:** Telemetry → Backend (telemetry connects to backend's TCP server port)
- **Data carried:**
  - **Race state** (~1Hz): current lap, positions, gaps, tyre compound/age, fuel, pit status, damage levels, weather, `lastLapTimeMs` (last lap time per car, used by the portal for gap calculations) — enough for the portal to render a live dashboard without querying the DB
  - **Session lifecycle events:** `sessionStarted`, `sessionEnded`, `safetyCarDeployed`, `retiredCar`, etc. — backend uses these to update its in-memory session state and notify the portal via WebSocket. `sessionStarted` includes session assist settings (`ersAssist`, `drsAssist`) parsed from the UDP session packet, used by the race engineer to suppress messages for game-managed assists. On `sessionStarted`, the backend auto-assigns the session to a driver if only one driver exists in the `drivers` table (idempotent via duplicate key check)
- **Format:** Each message is a single JSON object on one line. A `type` field discriminates message kinds (e.g. `{"type":"raceState","data":{...}}`)
- **Reconnection:** Exponential backoff 3s → 6s → 12s → 24s → cap 30s, resets on success
- **Backend recovery on restart:** Queries DB for active session state (catch-up), then resumes from TCP stream

## 3. Database → Backend (JDBC, on-demand reads)

The backend reads from Oracle for historical data, calibration results, and simulation outputs. This is the standard request-driven path — not polling.

- **Protocol:** JDBC (Oracle thin driver + Oracle UCP)
- **Direction:** Backend ← Oracle
- **When:** In response to REST API requests from the portal (historical session data, calibration status, simulation results)
- **Tables read:** All tables, but primarily `sessions`, `sector_snapshots`, `calibration_coefficients`, and simulation result data
- **Connection config:** `backend/src/main/resources/application.properties` — Spring datasource config

## 4. Backend → Portal (WebSocket + REST)

Two channels serving different purposes:

### WebSocket (live push)

- **Protocol:** WebSocket over HTTP (Spring WebSocket / STOMP)
- **Direction:** Backend → Portal (server push)
- **Data:** Live race state relayed from the TCP stream (positions, gaps, tyres, fuel, weather, events). The backend receives race state via TCP from telemetry and immediately broadcasts it to connected WebSocket clients
- **Message types:**
  - `state` — live race state (~1Hz, matching TCP push cadence)
  - `sessionStarted`, `sessionEnded` — session lifecycle events
  - `event` — discrete race events (safety car, retirement, penalty, etc.)
  - `simulationResult` — Monte Carlo simulation results
  - `calibrationComplete`, `calibrationFailed` — calibration pipeline status
  - `strategyEvaluation` — ranked strategy leaderboard with `evaluatedAtLap`, `stale` flag, and full `StrategyEvaluation` payload (see section 6b)
- **Rate:** ~1Hz during a live session for race state; other message types are event-driven
- **Topic:** `/topic/race-state` (STOMP destination)

### REST (historical / on-demand)

- **Protocol:** HTTP REST (JSON)
- **Direction:** Portal → Backend → Portal (request/response)
- **Endpoints (planned):**
  - `GET /api/sessions` — list sessions (includes assigned driver name if linked via `driver_sessions`)
  - `GET /api/sessions/{uid}` — session detail with participants (includes assigned driver id and name)
  - `GET /api/sessions/{uid}/sectors` — sector snapshot data for charts
  - `GET /api/calibration/{trackId}` — calibration coefficient status
  - `POST /api/simulation/run` — trigger a simulation run
  - `GET /api/simulation/{id}/results` — fetch simulation results
  - `GET /api/driver-ratings` — driver skill ratings (for outlier detection cold start)
  - `PUT /api/driver-ratings/{name}` — update a driver's skill rating

### Use Case: Session History & REST API

```mermaid
sequenceDiagram
    participant Portal as Portal (Angular)
    participant Backend as Backend (Spring Boot)
    participant DB as Oracle DB

    Portal->>Backend: GET /api/sessions?trackId=X&limit=20
    Backend->>DB: SELECT sessions<br/>JOIN driver_sessions, drivers
    DB-->>Backend: Session list + driver names
    Backend-->>Portal: JSON [{sessionUid, trackId, driverName, ...}]

    Portal->>Backend: GET /api/sessions/{uid}
    Backend->>DB: SELECT session + participants<br/>JOIN driver_sessions
    DB-->>Backend: Session detail + driver info
    Backend-->>Portal: JSON {session, participants, driverId, driverName}

    Portal->>Backend: GET /api/sessions/{uid}/sectors?carIndex=0
    Backend->>DB: SELECT sector_snapshots<br/>WHERE session_uid=? AND car_index=?
    DB-->>Backend: Sector-level telemetry rows
    Backend-->>Portal: JSON [{lap, sector, sectorTimeMs, tyre, fuel, ...}]

    Portal->>Backend: GET /api/calibration/status?trackId=X
    Backend->>DB: SELECT calibration_coefficients<br/>WHERE track_id=?
    DB-->>Backend: Fitted coefficients per knob/regime
    Backend-->>Portal: JSON [{knobName, regime, value, confidence, ...}]

    Portal->>Backend: POST /api/simulation/trigger
    Backend->>Backend: SimulationOrchestrator.triggerNow()
    Backend-->>Portal: 202 {jobId, status: "started"}
```

## 5. Calibration Trigger (via TxEventQ)

Calibration is a batch process that refits model coefficients from accumulated historical data. It runs as a standalone long-running Python service that consumes requests from TxEventQ ([Oracle Corporation, 2025](10-REFERENCES.md#oracle-txeventq)), mirroring the simulator's architecture.

- **Trigger:** Automatic, fired when a session ends. `SessionStateHolder` enqueues to `CALIBRATION_REQUEST` via `QueueService`
- **Flow:** `sessionEnded` → `CALIBRATION_REQUEST` enqueued → standalone Calibration Service (`python -m calibration service`) dequeues → runs calibration pipeline in-process → reads `sector_snapshots` from Oracle → fits coefficients → writes to `calibration_coefficients` table → WebSocket broadcast
- **Manual fallback:** `POST /api/calibration/run?trackId={id}` → enqueues to `CALIBRATION_REQUEST` → returns `202 Accepted`
- **Session lifecycle:** `TelemetryTcpServer` also enqueues session start/end events to `SESSION_LIFECYCLE` (multi-consumer queue) for future consumers
- **Scope:** Recalibrates all knobs for the track of the just-completed session, both PLAYER and AI regimes
- **Duration:** Seconds for early data volumes; the async task prevents blocking the main thread

## 6. Simulation Trigger (via TxEventQ)

Simulations are triggered automatically during live races (lap completions, pit stops, safety car changes) and manually via the portal.

- **Automatic trigger:** `SimulationOrchestrator` detects trigger conditions from the TCP state stream, debounces (3s), assembles a `RaceSnapshot`, and enqueues to `SIMULATION_REQUEST` via `QueueService`
- **Manual trigger:** Portal → `POST /api/simulation/run` or `/api/simulation/trigger` → Backend enqueues to `SIMULATION_REQUEST` → returns `202 Accepted` with jobId
- **Flow:** `SIMULATION_REQUEST` queue → Python simulator worker dequeues → loads calibration coefficients → `MonteCarloEngine.simulate()` → enqueues result to `SIMULATION_RESULT` → `SimulationResultConsumer` (Java) dequeues → updates job store → broadcasts via WebSocket
- **Simulator:** Python FastAPI service ([Ramirez, 2025](10-REFERENCES.md#fastapi)) (port 8081). REST endpoints remain available for direct use and testing. A background daemon thread polls the queue when `SIMULATOR_USE_DB=true`
- **Results:** Cached in-memory in `SimulationOrchestrator` (up to 50 jobs). Portal fetches via `GET /api/simulation/results/{jobId}`
- **Live re-simulation:** Debounced to at most once per 3 seconds to avoid flooding the queue

## 6b. Strategy Evaluation (via TxEventQ)

Strategy evaluation is an automated pipeline that generates, evaluates, and ranks pit stop strategies during a live race. It builds on the simulation infrastructure (section 6) but adds candidate generation and comparative ranking.

### Use Case: Automated Strategy Evaluation

```mermaid
sequenceDiagram
    participant Telemetry as Telemetry (TCP)
    participant Orch as StrategyOrchestrator
    participant DB as Oracle DB
    participant Q1 as STRATEGY_REQUEST queue
    participant Sim as Simulator (Python)
    participant Q2 as STRATEGY_RESULT queue
    participant Consumer as StrategyResultConsumer
    participant Eng as RaceEngineerService
    participant Portal as Portal / iOS

    Telemetry->>Orch: TCP state update (~1Hz)
    Note over Orch: Detect trigger:<br/>player lap ✓ | pit stop ✓<br/>safety car ✓ | weather ✓
    Orch->>Orch: Debounce (3s)
    Orch->>DB: Load tyre_sets for session
    DB-->>Orch: Tyre set availability per car
    Note over Orch: Assemble RaceSnapshot<br/>(cars + tyre sets + weather)
    Orch->>Portal: WebSocket: stale=true
    Orch->>Q1: Enqueue {jobId, playerCarIndex, snapshot}

    Q1->>Sim: Dequeue request
    activate Sim
    Note over Sim: generate_candidates()<br/>0/1/2-stop strategies<br/>(max 50, two-compound rule)
    Sim->>DB: Load calibration coefficients
    DB-->>Sim: Fitted coefficients for track
    loop For each candidate strategy
        Note over Sim: MonteCarloEngine.simulate()<br/>(1K iterations per candidate)
    end
    Note over Sim: Rank by mean position<br/>Compute CI, DNF prob,<br/>top-3 prob, expected points
    Sim->>Q2: Enqueue {jobId, evaluatedAtLap, result}
    deactivate Sim

    Q2->>Consumer: Dequeue result
    Consumer->>Orch: completeJob(jobId, evaluation)
    Orch->>Portal: WebSocket: strategyEvaluation<br/>(stale=false, ranked strategies)
    Consumer->>Eng: onStrategyEvaluation()
    Note over Eng: Generate pit window messages<br/>"Box window opens in N laps"
    Eng->>Portal: WebSocket: raceEngineer message
```

- **Trigger:** `StrategyOrchestrator` monitors the TCP state stream and fires on: player lap completion, any car pit stop, safety car status change, or weather change. Debounced at 3 seconds — same as simulation triggers
- **Flow:**
  1. `StrategyOrchestrator.onStateUpdate()` detects trigger → assembles `RaceSnapshot` enriched with tyre set availability from `tyre_sets` table → enqueues to `STRATEGY_REQUEST` with `jobId`, `playerCarIndex`, and full snapshot
  2. When the request is enqueued, the leaderboard is marked `stale=true` and broadcast via WebSocket so the portal shows an "updating" indicator
  3. `run_strategy_worker()` (Python, daemon thread in simulator) dequeues → calls `generate_candidates()` to produce plausible strategies → calls `StrategyEvaluator.evaluate()` which runs a full Monte Carlo batch per candidate → enqueues result to `STRATEGY_RESULT` with `jobId`, `evaluatedAtLap` (the lap from the race snapshot at trigger time, not at result completion), and ranked strategies
  4. `StrategyResultConsumer` (Java, daemon thread in backend) dequeues → calls `StrategyOrchestrator.completeJob()` → updates leaderboard (`stale=false`) → broadcasts via WebSocket → notifies `RaceEngineerService` for voice message generation
- **Candidate generation (`candidate_generator.py`):** Generates 0-stop (if two-compound rule already met and tyres can last), 1-stop (varying pit lap across remaining distance), and 2-stop (if 15+ laps remain) strategies. Enforces F1 two-compound rule, prunes compounds whose lap delta exceeds ±5 s vs the fitted set, caps at 50 candidates
- **Evaluation metrics per candidate:** mean finishing position, position std dev, 95% CI, DNF probability, top-3 probability, points-finish probability, expected F1 championship points
- **Queues:** `PDBADMIN.STRATEGY_REQUEST` (single consumer) and `PDBADMIN.STRATEGY_RESULT` (single consumer). Both use Oracle TxEventQ with JSON payloads, same as the simulation queues

## 7. Shared Database

All components that access the database connect to the same Oracle AI Database 26ai instance and the same schema.

- **Instance:** Single Oracle 26ai container (Podman), exposed on the default port
- **Schema:** Single schema owner. All tables live in one schema — no cross-schema access needed
- **Connection config per component:**
  - `telemetry/config.properties` — JDBC URL, username, password
  - `backend/src/main/resources/application.properties` — Spring datasource config (same DB, same schema)
  - `simulator/config.properties` — oracledb pool config (same DB, same schema)
  - `calibration/config.properties` — oracledb pool config (same DB, same schema)
- **Config generation:** Each component stores a `.template` file (e.g. `telemetry/src/main/resources/config.properties.template`). `python manage.py local setup` generates the actual config files by injecting the DB password. `python manage.py local clean` removes them. Generated files are git-ignored.
- **Concurrent access:** Telemetry writes continuously; backend reads on demand; calibration reads/writes after sessions; simulator reads coefficients and communicates via TxEventQ queues. No write conflicts — telemetry owns data ingestion, calibration owns coefficient updates
- **Schema ownership:** A single DB user owns all tables. No per-component users for the POC

## 8. Build Independence & Interface Versioning

Backend and telemetry are kept as independent Gradle projects — no root multi-project build, no shared `common` module.

**Rationale:**

- They scale differently (telemetry is high-throughput plain Java; backend is Spring Boot)
- They have different lifecycles (telemetry may be redeployed independently of backend)
- Sharing compiled code creates coupling that outweighs the convenience for a PoC

**Interface between telemetry and backend:**
The only runtime interface is the TCP push socket (section 2 above). Both sides must agree on the JSON-lines message schema. To allow independent evolution:

- **Message versioning:** Each JSON message includes a `version` field (integer, starting at `1`). The producer (telemetry) sets the version; the consumer (backend) must handle all supported versions.
- **Backward compatibility rule:** New fields may be added without bumping the version. Removing or renaming a field requires a version bump. The consumer ignores unknown fields.
- **Schema definition:** The canonical message schemas are documented in `design/06-INTEGRATION.md` (this file) and updated when the interface changes. There is no shared code artifact — each side implements its own serialization/deserialization.

**Interface between backend and database:**
Both telemetry and backend connect to the same Oracle schema (section 7 above). The database schema (managed by Liquibase, see todo 05) is the shared contract. Both sides depend on the table definitions, not on each other's code.

**Duplicate dependencies are accepted:** Both projects independently declare their JDBC driver and other shared dependencies. This is a conscious trade-off for build isolation.

## 9. Portal Live Dashboard (Angular)

The portal's Race view is a modular Angular component tree that renders live race state received via WebSocket.

### Architecture

```mermaid
graph TD
    Race["RaceComponent"]
    Race --> Selector["Session Selector"]
    Race --> Circuit["CircuitMapComponent"]
    Race --> Gap["GapIndicatorComponent"]
    Race --> Penalties["PenaltiesPanelComponent"]
    Race --> Damage["DamagePanelComponent"]
    Race --> Tyres["TyresPanelComponent"]
    Race --> Weather["WeatherPanelComponent"]
    Race --> Strategy["StrategyWidgetComponent"]
    Strategy -.-> StrategyView["StrategyComponent (full leaderboard)"]
```

- **Reactivity:** Angular signals ([Google, 2025](10-REFERENCES.md#angular-signals)) (`signal()`, `computed()`) for granular state tracking. The race service exposes signals that child components bind to directly — no manual subscription management.
- **Session selector:** Queries `GET /api/sessions` for active sessions. Selecting a session switches the WebSocket subscription and reloads all child components with new state.
- **Circuit map:** SVG-based rendering with a fixed viewBox. Car positions are mapped to track coordinates using sector progress. Renders DRS zones, yellow flag sectors, pit entry/exit. Team colours from a static lookup table.
- **Gap indicator:** Displays sector-by-sector time deltas (in milliseconds) between the player car and the car immediately ahead and behind. Sector 3 time is derived from `lastLapTimeMs` on lap transitions.
- **Strategy widget:** Compact sidebar card showing the top 3 ranked strategies from the latest strategy evaluation — expected finishing position and podium probability for each. Shows the evaluation lap and a stale indicator while a new evaluation is in progress. Links to the full Strategy view.
- **Strategy view (full leaderboard):** Separate routed component (`StrategyComponent`) displaying all evaluated strategies in a ranked table with comprehensive metrics: mean position, 95% CI, DNF probability, points-finish probability, and expected championship points.
- **Info panels:** Each panel subscribes to a slice of the race state (penalties, damage, tyres, weather) and renders a focused view. Standalone components with no cross-dependencies.

## 10. iOS Voice Client (SwiftUI)

A native iOS app that receives race engineer messages via WebSocket and speaks them aloud using text-to-speech. This is the delivery mechanism for the race engineer voice described in `08-RACE_ENGINEER_VOICE.md`.

### Architecture

Three-layer design:

```mermaid
graph TD
    subgraph UI["UI — SwiftUI"]
        Connect["ConnectView"]
        Live["LiveView"]
        Settings["SettingsView"]
    end
    subgraph Services
        WS["WebSocketService"]
        Speech["SpeechService"]
    end
    subgraph Protocol
        Msg["EngineerMessage"]
    end
    Connect --> WS
    Live --> WS
    Live --> Speech
    Settings --> Speech
    WS --> Msg
    Speech --> Msg
```

- **WebSocketService:** `@Observable` for SwiftUI binding. Connection states: disconnected → connecting → connected (with reconnecting as a transient state). Exponential backoff reconnect: delay = min(2^attempt, 30) seconds, max 10 attempts. Automatically converts HTTP/HTTPS URLs to WS/WSS. Filters incoming messages by `sessionUid` to prevent cross-session contamination.
- **SpeechService:** `AVSpeechSynthesizer` ([Apple Inc., 2025](10-REFERENCES.md#apple-tts)) with a priority queue. IMMEDIATE-priority messages interrupt current speech; NORMAL-priority messages queue behind active speech. Audio session configured as `.playback` with `.duckOthers` (lowers game audio during speech). Voice: English (GB), rate 0.48, 0.1s inter-message delay.
- **Thread safety:** `@unchecked Sendable` + `@MainActor` for safe concurrent access from WebSocket callbacks to UI state updates.

## Decision Rationale: TCP Push Architecture

Five options were evaluated for the telemetry-to-backend data path. Plain TCP won.

### Options Evaluated

| Option            | Approach                                      | Why it lost                                                                                                                                                                                                               |
| ----------------- | --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **A. REST**       | HTTP POST from telemetry to backend endpoints | Adds HTTP client overhead per message. Request/response model is wrong for a persistent data stream. Telemetry must handle HTTP client concerns (timeouts, retries, connection pooling)                                   |
| **B. WebSocket**  | Telemetry opens WS connection to backend      | HTTP upgrade handshake and frame masking are designed for browser security — unnecessary between two JVM processes. Requires a WebSocket client library in telemetry                                                      |
| **C. gRPC**       | Protobuf streaming RPCs                       | Right tool for production microservices with multiple consumers and strict API contracts. For a single-instance PoC with one producer and one consumer, protobuf compilation and gRPC runtime are overhead without payoff |
| **D. DB polling** | Backend polls Oracle for new rows             | Works but adds 1-2s latency. Extra read load on the database for data that's immediately available in memory                                                                                                              |
| **E. Plain TCP**  | Persistent socket, newline-delimited JSON     | **Chosen** — see below                                                                                                                                                                                                    |

### Why Plain TCP Won

- **Zero new dependencies.** `java.net.Socket` and `java.io.OutputStream` are in the JDK. The telemetry server stays zero-dependency
- **Natural fit for a persistent data stream.** TCP provides reliable, ordered byte stream — no per-message connection overhead, no HTTP framing, no compilation step
- **Sub-second latency.** Data goes from telemetry memory → socket write → backend socket read → WebSocket broadcast. Full pipeline (UDP arrival to portal display) under 100ms
- **One channel for everything.** Same TCP connection carries continuous race state and discrete lifecycle events. No protocol splitting
- **Simple failure model.** Connection drops are detected immediately by both sides. Telemetry reconnects with exponential backoff. Backend accepts new connection and resumes

### JSON-lines Protocol Choice

Newline-delimited JSON over alternatives:

- **Length-prefixed binary:** More efficient but harder to debug. `tail -f` on a TCP stream shows readable JSON; binary requires tooling
- **Protobuf/MessagePack:** Better compression but adds serialization dependencies. At ~1KB per message at 1Hz, bandwidth is irrelevant
- **Custom binary format:** Unnecessary — the parsing cost is on the UDP side, not the TCP side

### Single Backend Instance Assumption

This architecture assumes a single backend instance. If multiple instances were needed, options would include a message broker (Redis Pub/Sub, Kafka), connecting to all instances, or a shared state store. None are needed for the PoC.

## Summary of Protocols

| From      | To          | Protocol   | Direction                | Data Format          | Frequency      | Queue Name          |
| --------- | ----------- | ---------- | ------------------------ | -------------------- | -------------- | ------------------- |
| F1 Game   | Telemetry   | UDP        | Game → Telemetry         | Binary (game spec)   | 20Hz           | —                   |
| Telemetry | Oracle DB   | JDBC       | Telemetry → DB           | SQL (prepared stmts) | ~60 rows/lap   | —                   |
| Telemetry | Backend     | TCP socket | Telemetry → Backend      | JSON-lines           | ~1Hz           | —                   |
| Backend   | Oracle DB   | JDBC       | Backend ↔ DB             | SQL                  | On demand      | —                   |
| Backend   | Portal      | WebSocket  | Backend → Portal         | JSON                 | ~1Hz (live)    | —                   |
| Portal    | Backend     | HTTP REST  | Portal → Backend         | JSON                 | On demand      | —                   |
| Backend   | Simulator   | TxEventQ   | Backend → DB → Simulator | JSON (queue)         | On trigger     | SIMULATION_REQUEST  |
| Simulator | Backend     | TxEventQ   | Simulator → DB → Backend | JSON (queue)         | On completion  | SIMULATION_RESULT   |
| Backend   | Simulator   | TxEventQ   | Backend → DB → Simulator | JSON (queue)         | On trigger     | STRATEGY_REQUEST    |
| Simulator | Backend     | TxEventQ   | Simulator → DB → Backend | JSON (queue)         | On completion  | STRATEGY_RESULT     |
| Backend   | Calibration | TxEventQ   | Backend → DB → Consumer  | JSON (queue)         | On session end | CALIBRATION_REQUEST |
| Backend   | iOS Client  | WebSocket  | Backend → iOS            | JSON                 | ~1Hz (live)    | —                   |
