# F1 2025 Race Strategy Simulation

A proof-of-concept system that ingests real-time telemetry from F1 2025, stores it in a database, and runs Monte Carlo simulations to predict race outcomes under different strategy choices.

## Modules

| Module | Description | Tech |
|--------|-------------|------|
| `telemetry/` | UDP server that receives F1 2025 game telemetry, parses packet headers, and tracks statistics | Java 21, plain Gradle |
| `backend/` | API server for communication between portal, database, and simulation | Spring Boot 3.5.3, Java 23 |
| `portal/` | Backoffice for setup, data inspection, and live telemetry streaming | Angular 21 |
| `simulator/` | Monte Carlo race strategy simulations and model calibration | TBD |
| `database/` | Database configuration and schema setup | TBD |
| `client/` | iOS app for real-time race engineer data to the pilot | Not yet implemented |

## Running

### Telemetry Server

```bash
cd telemetry
./gradlew run
```

Listens on UDP port 20777 (configurable in `src/main/resources/config.properties`). Point the F1 2025 game's telemetry output to this address.

**Test client** (simulates F1 25 telemetry):

```bash
cd telemetry
./gradlew runClient
```

### Backend

```bash
cd backend
./gradlew bootRun
```

Runs on http://localhost:8080.

### Portal

```bash
cd portal
npm start
```

Runs on http://localhost:4200, proxies API/WS calls to the backend.

## Design Docs

See `design/` for architecture decisions, database schema, calibration pipeline, and Monte Carlo simulation design.

See `docs/` for F1 25 telemetry UDP specification and packet structures.
