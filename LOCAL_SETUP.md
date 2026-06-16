# Local Setup Guide

## Prerequisites

- Java 23+ (telemetry and backend)
- Node 22+ (portal)
- Python 3.12+ (manage.py, simulator, calibration)
- Podman (database container + compose stack)
- Liquibase CLI (database migrations)

## Quick start (everything in one go)

Run these in order from the project root, top to bottom. Works from any starting point. Each
command is its own snippet so it's easy to copy and paste one at a time.

Give the Podman VM 8 GiB (the `--no-cache` build of all 5 services OOMs otherwise):

```bash
podman machine stop
```

```bash
podman machine set --memory 8192
```

```bash
podman machine start
```

Create and activate the Python virtualenv, install deps:

```bash
python -m venv venv
```

```bash
source venv/bin/activate
```

```bash
pip install -r requirements.txt
```

Tear down any running stack:

```bash
podman compose down
```

Start Oracle + run Liquibase migrations (rotates the DB password):

```bash
python manage.py local setup
```

Restore the latest backup (accumulated telemetry — calibration needs it for sample size):

```bash
python manage.py local import
```

Rebuild all 5 service images with the fresh config baked in:

```bash
podman compose build --no-cache
```

Start the 5 services:

```bash
podman compose up -d
```

Seed the calibration model for the track from the imported data. `4` is the **track id**
(4 = Catalunya / Circuit de Barcelona-Catalunya); pass the id of whatever circuit you'll
drive. This populates `calibration_coefficients`, `sector_pace_baselines` and the per-age
`tyre_age_pace_offsets` curve so the simulator has a tyre model immediately (otherwise it
waits for the first Free Practice session to trigger calibration):

```bash
python -m calibration run 4
```

Show consolidated service info + health:

```bash
python manage.py info
```

Container status:

```bash
podman compose ps
```

Follow one service's log (e.g. backend):

```bash
podman compose logs -f <service>
```

Portal is at http://localhost:4200.

### Clean up

**Export before cleaning** — `local clean` removes the Oracle container and all its data. The
backup keeps every session + calibration table (sector snapshots, fitted coefficients, pace
baselines, sim-run history, …) so the dataset — and the simulation's precision — keeps growing
across rebuilds. Restore it with `local import` after the next `setup`.

Back up all data to database/backups/ (do this BEFORE clean):

```bash
python manage.py local export
```

Stop and remove the 5 service containers:

```bash
podman compose down
```

Stop Oracle + remove container + clear password from .env:

```bash
python manage.py local clean
```

## Running services one by one (manual)

When iterating on a single service it's often faster to run it directly on the host than rebuild the container. Start Oracle first, then run each service in its own terminal.

### Database (Oracle in Podman)

```bash
pip install -r requirements.txt
```

Starts Oracle container, runs migrations:

```bash
python manage.py local setup
```

Check it's running:

```bash
python manage.py local status
```

Back up all data to database/backups/:

```bash
python manage.py local export
```

Restore the latest backup (run after setup, before the stack):

```bash
python manage.py local import
```

Preview 3rd-sector rows corrupted by the old capture bug:

```bash
python manage.py local repair-sectors
```

...and write the fixes (re-derive S3, quarantine the rest):

```bash
python manage.py local repair-sectors --apply
```

Tear down — export first to keep your accumulated data:

```bash
python manage.py local clean
```

### Telemetry (Plain Java UDP)

**Native:**

UDP server on port 20777:

```bash
cd telemetry
./gradlew run
```

Synthetic test client:

```bash
cd telemetry
./gradlew runClient
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

Spring Boot on port 8080:

```bash
export $(grep F1STRATEGY_DB_PASSWORD .env | tr -d "'")
cd backend
./gradlew bootRun
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

Angular dev server on port 4200:

```bash
cd portal
npm start
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

FastAPI on port 8081:

```bash
python -m simulator
```

Without Oracle (uses defaults):

```bash
SIMULATOR_USE_DB=false python -m simulator
```

**Container:**

```bash
podman build -f simulator/Containerfile -t f1strategy-simulator .
```

```bash
podman run --network host f1strategy-simulator
```

### Calibration (batch CLI, native only)

Run calibration pipeline:

```bash
python -m calibration run <trackId>
```
