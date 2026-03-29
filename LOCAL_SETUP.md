# Local Setup Guide

## Prerequisites

- Java 23+ (telemetry and backend)
- Node 22+ (portal)
- Python 3.12+ (manage.py, simulator, calibration)
- Podman (database container)
- Liquibase CLI (database migrations)

## Database (Oracle in Podman)

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
python manage.py local clean    # tear down
```

## Telemetry (Plain Java UDP)

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

## Backend (Spring Boot)

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

## Portal (Angular)

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

## Simulator (FastAPI)

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

## Calibration (batch CLI, native only)

```bash
python -m calibration run <trackId>          # run calibration pipeline
```
