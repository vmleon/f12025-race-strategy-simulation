# Local Setup Guide

## Prerequisites

- Java 23+ (telemetry and backend)
- Node 22+ (portal)
- Python 3.12+ (manage.py, simulator, calibration)
- Podman (database container + compose stack)
- Liquibase CLI (database migrations)

## Quick start (everything in one go)

From a clean clone, get the whole system up with the Oracle DB first, then the compose stack.

```bash
python -m venv venv                  # one-time: create virtualenv at project root
source venv/bin/activate             # Windows: venv\Scripts\activate
pip install -r requirements.txt      # installs manage.py + podman-compose
```

```bash
python manage.py local setup         # Oracle container + Liquibase + generated configs + Java build (~5-6 min)
python manage.py local import        # restore the latest backup so the dataset keeps growing (skip on first run — no backup yet)
podman compose up --build -d         # build + start telemetry, backend, simulator, calibration, portal
```

Check endpoints and container status:

```bash
python manage.py info                # consolidated endpoints + game setup info
podman compose ps                    # container status
podman compose logs -f <service>     # follow one service's log (e.g. backend)
```

Portal is at http://localhost:4200.

### Clean up

**Export before cleaning** — `local clean` removes the Oracle container and all its data. The
backup keeps every session + calibration table (sector snapshots, fitted coefficients, pace
baselines, sim-run history, …) so the dataset — and the simulation's precision — keeps growing
across rebuilds. Restore it with `local import` after the next `setup`.

```bash
python manage.py local export        # back up all data to database/backups/ (do this BEFORE clean)
podman compose down                  # stop and remove the 5 service containers
python manage.py local clean         # stop Oracle + remove container + clear password from .env
```

## Running services one by one (manual)

When iterating on a single service it's often faster to run it directly on the host than rebuild the container. Start Oracle first, then run each service in its own terminal.

### Database (Oracle in Podman)

```bash
pip install -r requirements.txt
```

```bash
python manage.py local setup    # starts Oracle container, runs migrations
```

```bash
python manage.py local status   # check it's running
```

```bash
python manage.py local export   # back up all data to database/backups/
```

```bash
python manage.py local import   # restore the latest backup (run after setup, before the stack)
```

```bash
python manage.py local repair-sectors          # preview 3rd-sector rows corrupted by the old capture bug
python manage.py local repair-sectors --apply   # ...and write the fixes (re-derive S3, quarantine the rest)
```

```bash
python manage.py local clean    # tear down — export first to keep your accumulated data
```

### Telemetry (Plain Java UDP)

**Native:**

```bash
cd telemetry
./gradlew run                   # UDP server on port 20777
```

```bash
cd telemetry
./gradlew runClient             # synthetic test client
```

**Container:**

```bash
cd telemetry
./gradlew installDist
```

```bash
podman build -f Containerfile -t f1strategy-telemetry telemetry/
```

```bash
podman run --network host f1strategy-telemetry
```

### Backend (Spring Boot)

**Native:**

```bash
export $(grep F1STRATEGY_DB_PASSWORD .env | tr -d "'")
cd backend
./gradlew bootRun               # Spring Boot on port 8080
```

**Container:**

```bash
cd backend
./gradlew build
```

```bash
podman build -f Containerfile -t f1strategy-backend backend/
```

```bash
podman run --network host -e F1STRATEGY_DB_PASSWORD=<pw> f1strategy-backend
```

### Portal (Angular)

**Native:**

```bash
cd portal
npm start                       # Angular dev server on port 4200
```

**Container:**

```bash
podman build -f Containerfile -t f1strategy-portal portal/
```

```bash
podman run -p 4200:4200 f1strategy-portal
```

### Simulator (FastAPI)

**Native:**

```bash
python -m simulator                          # FastAPI on port 8081
```

```bash
SIMULATOR_USE_DB=false python -m simulator   # without Oracle (uses defaults)
```

**Container:**

```bash
podman build -f simulator/Containerfile -t f1strategy-simulator .
```

```bash
podman run --network host f1strategy-simulator
```

### Calibration (batch CLI, native only)

```bash
python -m calibration run <trackId>          # run calibration pipeline
```
