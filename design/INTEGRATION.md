# Integration Points Between Components

## System Overview

```
┌─────────────┐    UDP     ┌─────────────┐   JDBC    ┌──────────────┐
│  F1 2025    │──────────→ │  Telemetry  │─────────→ │   Oracle DB  │
│  Game       │   20Hz     │  (Plain Java)│  writes   │   26ai       │
└─────────────┘            └──────┬──────┘           └──────┬───────┘
                                  │ TCP push                │ JDBC reads
                                  │ JSON-lines ~1Hz         │
                                  ▼                         │
                           ┌─────────────┐                  │
                  ┌───────→│  Backend    │←─────────────────┘
                  │        │  (Spring Boot)
                  │        └──────┬──────┘
                  │               │ WebSocket
                  │               ▼
                  │        ┌─────────────┐
                  │        │   Portal    │
                  │        │  (Angular)  │
                  │        └─────────────┘
                  │
           ┌──────┴──────┐
           │  Simulator  │←── reads coefficients from DB
           │  (Plain Java)│──→ writes results to DB
           └─────────────┘
```

## 1. Telemetry → Database (JDBC, direct write)

The telemetry server writes parsed UDP data directly to Oracle via JDBC + Oracle UCP connection pool. No intermediary.

- **Protocol:** JDBC (Oracle thin driver)
- **Direction:** Telemetry → Oracle
- **Data:** `sessions`, `participants`, `sector_snapshots`, `session_events`, `tyre_sets`, `final_classifications` (all 6 data tables from `DATABASE_DESIGN.md`)
- **Rate:** ~60 rows/lap for sector_snapshots (3 sectors x 20 cars), plus sporadic event/metadata rows
- **Batching:** JDBC `addBatch()` / `executeBatch()` for sector_snapshots (all 20 cars snapshotted at each sector boundary)
- **Connection config:** `telemetry/config.properties` — host, port, service name, credentials

## 2. Telemetry → Backend (TCP push, JSON-lines)

Live race state and session lifecycle events flow from telemetry to backend over a persistent TCP socket. Decided in `reports/TCP_PUSH_ARCHITECTURE.md`.

- **Protocol:** Plain TCP socket, newline-delimited JSON (`\n`-separated)
- **Direction:** Telemetry → Backend (telemetry connects to backend's TCP server port)
- **Data carried:**
  - **Race state** (~1Hz): current lap, positions, gaps, tyre compound/age, fuel, pit status, damage levels, weather — enough for the portal to render a live dashboard without querying the DB
  - **Session lifecycle events:** `sessionStarted`, `sessionEnded`, `safetyCarDeployed`, `retiredCar`, etc. — backend uses these to update its in-memory session state and notify the portal via WebSocket
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
- **Rate:** ~1Hz during a live session, matching the TCP push cadence
- **Topic:** `/topic/race-state` (STOMP destination)

### REST (historical / on-demand)
- **Protocol:** HTTP REST (JSON)
- **Direction:** Portal → Backend → Portal (request/response)
- **Endpoints (planned):**
  - `GET /api/sessions` — list sessions
  - `GET /api/sessions/{uid}` — session detail with participants
  - `GET /api/sessions/{uid}/sectors` — sector snapshot data for charts
  - `GET /api/calibration/{trackId}` — calibration coefficient status
  - `POST /api/simulation/run` — trigger a simulation run
  - `GET /api/simulation/{id}/results` — fetch simulation results
  - `GET /api/driver-ratings` — driver skill ratings (for outlier detection cold start)
  - `PUT /api/driver-ratings/{name}` — update a driver's skill rating

## 5. Calibration Trigger

Calibration is a batch process that refits model coefficients from accumulated historical data. It runs in the backend process.

- **Trigger:** Automatic, fired when the backend receives a `sessionEnded` event via the TCP stream
- **Flow:** `sessionEnded` event arrives on TCP → backend fires calibration as an async task → calibration reads `sector_snapshots` (filtered, `outlier = 0`) from Oracle → fits coefficients → writes to `calibration_coefficients` table
- **Manual fallback:** `POST /api/calibration/run?trackId={id}` — portal can trigger a manual recalibration for a specific track
- **Scope:** Recalibrates all knobs for the track of the just-completed session, both PLAYER and AI regimes
- **Duration:** Seconds for early data volumes; the async task prevents blocking the main thread

## 6. Simulation Trigger

Simulation runs are requested by the user through the portal.

- **Trigger:** User clicks "Run Simulation" in the portal → `POST /api/simulation/run` with parameters (track, strategy options, iteration count)
- **Flow:** Portal → REST → Backend validates request → Backend invokes simulator → Simulator reads `calibration_coefficients` + current race state from DB → runs Monte Carlo iterations → writes results to DB → Backend returns result ID
- **Simulator invocation:** The simulator is a separate module but invoked in-process by the backend (direct Java method call, not a subprocess). The backend includes the simulator module as a Gradle dependency
- **Results:** Stored in the database (a future `simulation_results` table, defined when todo 14 is implemented). Portal fetches results via `GET /api/simulation/{id}/results`
- **Live re-simulation:** During a live race, the portal can trigger re-simulation with updated race state. Debounced to at most once per lap to avoid overload

## 7. Shared Database

All components that access the database connect to the same Oracle AI Database 26ai instance and the same schema.

- **Instance:** Single Oracle 26ai container (Podman), exposed on the default port
- **Schema:** Single schema owner. All tables live in one schema — no cross-schema access needed
- **Connection config per component:**
  - `telemetry/config.properties` — JDBC URL, username, password
  - `backend/src/main/resources/application.properties` — Spring datasource config (same DB, same schema)
  - Simulator reads via the backend's connection (invoked in-process)
- **Concurrent access:** Telemetry writes continuously; backend reads on demand; calibration reads/writes after sessions. No write conflicts — telemetry owns data ingestion, calibration owns coefficient updates, simulation results are written by the backend/simulator
- **Schema ownership:** A single DB user owns all tables. No per-component users for the POC

## Summary of Protocols

| From | To | Protocol | Direction | Data Format | Frequency |
|------|-----|----------|-----------|-------------|-----------|
| F1 Game | Telemetry | UDP | Game → Telemetry | Binary (game spec) | 20Hz |
| Telemetry | Oracle DB | JDBC | Telemetry → DB | SQL (prepared stmts) | ~60 rows/lap |
| Telemetry | Backend | TCP socket | Telemetry → Backend | JSON-lines | ~1Hz |
| Backend | Oracle DB | JDBC | Backend ↔ DB | SQL | On demand |
| Backend | Portal | WebSocket | Backend → Portal | JSON | ~1Hz (live) |
| Portal | Backend | HTTP REST | Portal → Backend | JSON | On demand |
| Backend | Simulator | In-process | Backend → Simulator | Java method call | On demand |
