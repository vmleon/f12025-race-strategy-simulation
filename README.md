# F1 2025 Race Strategy Simulation

A proof-of-concept system that ingests real-time telemetry from the F1 2025 game, stores per-sector snapshots in Oracle AI Database 26ai, calibrates physics models from accumulated data, and runs Monte Carlo simulations to predict race outcomes under different pit strategy choices.

## Architecture

```mermaid
graph LR
    Game["F1 2025 Game<br/><i>UDP @ 20777</i>"]
    Ingest["Telemetry Server<br/><i>Plain Java 23</i>"]
    DB[("Oracle AI DB 26ai<br/><i>8 tables</i>")]
    Backend["Backend API<br/><i>Spring Boot 3.5.3</i>"]
    Portal["Web Portal<br/><i>Angular 21</i>"]
    Client["iOS Client<br/><i>planned</i>"]

    Game -- "UDP packets<br/>~100 Hz" --> Ingest
    Ingest -- "sector snapshots<br/>events, sessions" --> DB
    Ingest -- "TCP push<br/>port 9090" --> Backend
    DB -- "historical data" --> Ingest
    DB -- "coefficients +<br/>race state" --> Backend
    Backend -- "REST + WebSocket<br/>port 8080" --> Portal
    Backend -- "REST" --> Client
```

### Data Flow

The system operates as four connected pipelines:

**1. Ingestion** — The telemetry server listens for UDP packets from the F1 2025 game (~80–100 packets/sec). It maintains an in-memory snapshot of all 20 cars and writes to the database only on **sector transitions** (3 per lap × 20 cars ≈ 60 rows/lap). Discrete events (safety car, penalties, retirements, collisions) are captured immediately. Live race state is pushed to the backend via **TCP (port 9090)** at ~1 Hz.

**2. Calibration** — After each session ends, the backend auto-triggers the calibration pipeline, which reads all accumulated sector snapshots for the track and fits 11 model knobs (tyre degradation per compound, fuel effect, dirty air, DRS advantage, damage penalties, overtake probability, safety car rate). Coefficients are fitted **separately for Player and AI cars** because the game uses different physics models for each.

**3. Simulation** — During a race the Monte Carlo engine (1,000–10,000 iterations with early stopping) loads the fitted coefficients and current race state, then simulates at per-sector granularity. Each iteration samples from the calibrated distributions to project sector times, overtakes, and pit stop outcomes. The output is a probability distribution of finishing positions for each car. Simulations auto-trigger on lap completions, pit stops, safety car deployments, and disruptive events (with 3-second debounce).

**4. Presentation** — The backend broadcasts simulation results, calibration status, and live race state to the portal via WebSocket. The portal provides four views: live race table, strategy comparison, calibration dashboard, and session browser.

```mermaid
graph TD
    subgraph Ingestion
        UDP["UDP Packets<br/><i>~100/sec</i>"]
        Mem["In-Memory State<br/><i>latest per car</i>"]
        Snap["Sector Snapshot"]
        TCP["TCP Push<br/><i>~1 Hz to Backend</i>"]
        UDP --> Mem
        Mem -- "sector transition<br/>detected" --> Snap
        Mem -- "live state" --> TCP
    end

    subgraph Database
        SS[("sector_snapshots<br/><i>~60 cols, denormalized</i>")]
        CC[("calibration_coefficients")]
        DR[("driver_ratings")]
        Aux[("sessions · participants<br/>events · tyre_sets<br/>final_classifications")]
    end

    subgraph Calibration
        Fit["Fit 11 Knobs<br/><i>per track, per regime<br/>(PLAYER / AI)</i>"]
    end

    subgraph Simulation
        MC["Monte Carlo<br/><i>per-sector, 20 cars<br/>1k–10k iterations</i>"]
        Out["P(position) per car<br/><i>mean ± CI, P(podium),<br/>P(points), P(DNF)</i>"]
        MC --> Out
    end

    subgraph Presentation
        WS["WebSocket<br/><i>/ws/race</i>"]
        REST["REST API<br/><i>/api/*</i>"]
        Views["Portal Views<br/><i>Race · Strategy<br/>Calibration · Sessions</i>"]
        WS --> Views
        REST --> Views
    end

    Snap --> SS
    Snap --> Aux
    TCP --> WS
    SS -- "accumulated<br/>sessions" --> Fit
    Fit --> CC
    CC --> MC
    SS -- "current race<br/>state" --> MC
    Out --> WS
    Out --> REST
```

## Modules

| Module | Role | Tech |
|--------|------|------|
| `telemetry/` | UDP server: receives F1 2025 packets, maintains in-memory state, snapshots on sector transitions, pushes live state via TCP, runs calibration pipeline | Plain Java 23, Oracle UCP, Oracle JDBC |
| `backend/` | REST/WebSocket API: bridges portal with database and Monte Carlo simulation engine, orchestrates calibration and simulation triggers | Spring Boot 3.5.3, Java 23 |
| `portal/` | Web UI: live race table, strategy comparison, calibration dashboard, session browser | Angular 21 |
| `database/` | Oracle AI Database 26ai schema (8 tables), Liquibase migrations | Liquibase, SQL |
| `client/` | iOS app: real-time race engineer display for the driver | Planned |

### Key Design Choices

- **Plain Java for ingestion** — No HTTP needed; a blocking UDP socket loop with 2 dependencies (Oracle UCP + Oracle JDBC) starts instantly and handles the packet rate with minimal overhead.
- **Raw JDBC over ORM** — 99% inserts into flat, denormalized tables. Batch `addBatch()`/`executeBatch()` outperforms entity lifecycle management; analytical reads are aggregates (`AVG`, `GROUP BY`), not object graphs.
- **Per-sector granularity** — Captures sector-specific overtakes, DRS zones, and dirty air effects that per-lap resolution would miss. 3× more rows but still manageable (~60/lap).
- **Snapshot-on-transition** — Instead of storing every packet, the server keeps the latest state in memory and writes only when a sector boundary is crossed. Reduces DB volume by ~99%.
- **TCP push for live state** — Telemetry pushes race state to backend at ~1 Hz over TCP (newline-delimited JSON), avoiding polling and enabling real-time WebSocket broadcast to the portal.
- **Dual calibration regimes** — Separate PLAYER and AI coefficient sets because the game applies different physics to each. Player data accumulates 19× slower (1 car vs 19).
- **Intentional denormalization** — Weather, tyre wear (4 wheels × 3 metrics), car damage (8 components), and temperatures are stored as flat columns in `sector_snapshots` because they are always read together and never sparse.

## Running

### Prerequisites

- Java 23
- Node 22+
- Python 3.10+ (for `manage.py`)
- Podman (for Oracle database container)

### 1. Database

```bash
pip install -r requirements.txt   # one-time: install oracledb driver
python manage.py local setup      # start Oracle container + run Liquibase migrations
python manage.py local status     # verify DB is ready
```

### 2. Telemetry Server

```bash
cd telemetry && ./gradlew run
```

Listens on UDP port 20777 (configurable in `src/main/resources/config.properties`). Point the F1 2025 game's telemetry output to this address.

**Test client** (simulates F1 25 telemetry):

```bash
cd telemetry && ./gradlew runClient
```

### 3. Backend

```bash
cd backend && ./gradlew bootRun
```

Runs on http://localhost:8080. Connects to telemetry via TCP on port 9090.

### 4. Portal

```bash
cd portal && npm start
```

Runs on http://localhost:4200, proxies `/api` and `/ws` calls to the backend.

## Docs

- `design/` — Architecture decisions, database schema, calibration pipeline, Monte Carlo simulation design
- `docs/` — F1 25 telemetry UDP specification and packet structures
