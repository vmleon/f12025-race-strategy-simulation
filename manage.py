#!/usr/bin/env python3
"""CLI for managing the F1 Strategy local development environment."""

import csv
import datetime
import glob
import json
import os
import secrets
import shutil
import subprocess
import sys
import time
from decimal import Decimal

import click
from dotenv import load_dotenv, set_key
from rich.console import Console
from rich.panel import Panel

console = Console()

CONTAINER_NAME = "f1strategy_db"
CONTAINER_IMAGE = "container-registry.oracle.com/database/free:latest"
DB_READY_TIMEOUT = 300  # 5 minutes
DB_READY_POLL_INTERVAL = 5
ENV_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
LIQUIBASE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "database", "liquibase")
GRANT_SCRIPT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "database", "scripts", "local_pdb_grant.sql"
)
BACKUP_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "database", "backups")
EXPORTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "database", "exports")
CIRCUITS_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "backend", "src", "main", "resources", "circuits",
)
COMPOSE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "compose.yaml")

_CONFIG_TEMPLATES = [
    (
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "telemetry", "src", "main", "resources", "config.properties.template"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "telemetry", "src", "main", "resources", "config.properties"),
    ),
    (
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "backend", "src", "main", "resources", "application.properties.template"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "backend", "src", "main", "resources", "application.properties"),
    ),
    (
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "simulator", "config.properties.template"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "simulator", "config.properties"),
    ),
    (
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "calibration", "config.properties.template"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "calibration", "config.properties"),
    ),
]


def _generate_configs(password):
    """Generate service config files from templates."""
    for template_path, output_path in _CONFIG_TEMPLATES:
        with open(template_path) as f:
            content = f.read()
        content = content.replace("{{DB_PASSWORD}}", password)
        with open(output_path, "w") as f:
            f.write(content)
        console.print(f"  [green]Generated[/green] {os.path.relpath(output_path)}")


def _clean_configs():
    """Delete generated config files."""
    for _, output_path in _CONFIG_TEMPLATES:
        if os.path.exists(output_path):
            os.remove(output_path)
            console.print(f"  [green]Removed[/green] {os.path.relpath(output_path)}")


_EXPORT_TABLES = [
    ("sessions", ["session_uid"]),
    ("participants", ["session_uid", "car_index"]),
    ("sector_snapshots", ["session_uid", "car_index", "lap_number", "sector_number"]),
    ("session_events", ["event_id"]),
    ("tyre_sets", ["session_uid", "car_index", "set_index"]),
    ("final_classifications", ["session_uid", "car_index"]),
    ("calibration_coefficients", ["coefficient_id"]),
    ("sector_pace_baselines",
     ["track_id", "sector_number", "compound", "regime", "fuel_bucket_kg", "weather", "track_temp_bucket_c"]),
    ("tyre_age_pace_offsets",
     ["track_id", "compound", "regime", "sector_number", "tyre_age_laps"]),
    ("radio_messages", ["message_id"]),
    ("simulation_runs", ["run_id"]),
    ("simulation_accuracy", ["accuracy_id"]),
]

_SEQUENCES = [
    ("seq_session_events", "session_events", "event_id"),
    ("seq_calibration_coefficients", "calibration_coefficients", "coefficient_id"),
    ("seq_radio_messages", "radio_messages", "message_id"),
    ("seq_simulation_runs", "simulation_runs", "run_id"),
    ("seq_simulation_accuracy", "simulation_accuracy", "accuracy_id"),
]

# CSV output column order: traceability, render context, payload.
_RADIO_CSV_HEADER = [
    "message_id", "session_uid", "sent_at",
    "priority", "session_type", "track_id", "circuit_name",
    "lap_number", "total_laps", "player_position", "driver_name",
    "tyre_compound", "tyre_age_laps", "sector",
    "message_text", "best_strategies", "rendered_text",
]

# Columns selected from radio_messages (circuit_name and driver_name are resolved,
# not selected). Order is the lookup key for rows passed to _radio_csv_row.
_RADIO_DB_COLUMNS = [
    "message_id", "session_uid", "sent_at",
    "priority", "session_type", "track_id",
    "lap_number", "total_laps", "player_position",
    "tyre_compound", "tyre_age_laps", "sector",
    "message_text", "best_strategies", "rendered_text",
]


def _check_command(name):
    if shutil.which(name) is None:
        console.print(f"[red]Error:[/red] '{name}' is not installed or not in PATH.")
        sys.exit(1)


def _run(cmd, **kwargs):
    result = subprocess.run(cmd, capture_output=True, text=True, **kwargs)
    if result.returncode != 0:
        console.print(f"[red]Command failed:[/red] {' '.join(cmd)}")
        if result.stderr:
            console.print(result.stderr)
        sys.exit(1)
    return result


def _container_exists():
    result = subprocess.run(
        ["podman", "container", "exists", CONTAINER_NAME], capture_output=True
    )
    return result.returncode == 0


def _container_running():
    result = subprocess.run(
        ["podman", "inspect", "--format", "{{.State.Running}}", CONTAINER_NAME],
        capture_output=True,
        text=True,
    )
    return result.returncode == 0 and result.stdout.strip() == "true"


def _get_password():
    load_dotenv(ENV_FILE)
    return os.environ.get("F1STRATEGY_DB_PASSWORD", "")


def _get_host_ip():
    """Return this host's primary non-loopback IPv4 address.

    Uses the UDP-connect trick: opening a UDP socket to any address tells the
    kernel which local interface would be used, without sending a packet.
    Falls back to 127.0.0.1 if no non-loopback route is available.
    """
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def _probe_http(url, timeout=2.5):
    """GET url and report health as rich-markup. 200 = up, other status =
    degraded, connection error/timeout = down."""
    import urllib.error
    import urllib.request

    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            code = r.status
    except urllib.error.HTTPError as e:
        code = e.code
    except Exception as e:
        return f"[red]down[/red] ({type(e).__name__})"
    if code == 200:
        return "[green]up[/green]"
    return f"[yellow]degraded[/yellow] (HTTP {code})"


def _parse_vllm_target():
    """Read ENGINEER_LLM_HOST/PORT from compose.yaml (the running config). Falls
    back to the application.properties defaults if absent."""
    host, port = "localhost", "8000"
    try:
        with open(COMPOSE_FILE) as f:
            for line in f:
                line = line.strip().lstrip("- ")
                if line.startswith("ENGINEER_LLM_HOST="):
                    host = line.split("=", 1)[1].strip()
                elif line.startswith("ENGINEER_LLM_PORT="):
                    port = line.split("=", 1)[1].strip()
    except OSError:
        pass
    return host, port


def _service_running(keyword):
    """Report whether a running container's name contains keyword (for services
    with no HTTP endpoint, e.g. telemetry/calibration)."""
    result = subprocess.run(
        ["podman", "ps", "--format", "{{.Names}}"], capture_output=True, text=True
    )
    if result.returncode == 0 and any(keyword in n for n in result.stdout.split()):
        return "[green]running[/green] (container)"
    return "[red]not running[/red]"


def _format_sql_value(val):
    """Format a Python value as an Oracle SQL literal."""
    if val is None:
        return "NULL"
    if isinstance(val, (int, float, Decimal)):
        return str(val)
    if isinstance(val, datetime.datetime):
        s = val.strftime("%Y-%m-%d %H:%M:%S")
        if val.microsecond:
            return f"TO_TIMESTAMP('{s}.{val.microsecond:06d}', 'YYYY-MM-DD HH24:MI:SS.FF6')"
        return f"TO_TIMESTAMP('{s}', 'YYYY-MM-DD HH24:MI:SS')"
    escaped = str(val).replace("'", "''")
    return f"'{escaped}'"


def _db_connect(password):
    """Connect to the local Oracle database via oracledb thin driver."""
    import oracledb

    return oracledb.connect(user="pdbadmin", password=password, dsn="localhost:1521/FREEPDB1")


@click.group()
def cli():
    """F1 Strategy development environment manager."""


@cli.command()
def info():
    """Show consolidated connection info for the running stack."""
    host_ip = _get_host_ip()

    db_exists = _container_exists()
    db_running = db_exists and _container_running()
    if db_running:
        db_status = "[green]running[/green]"
    elif db_exists:
        db_status = "[yellow]stopped[/yellow]"
    else:
        db_status = "[red]not set up[/red] (run: python manage.py local setup)"

    password = _get_password()
    password_note = "(set)" if password else "(not set)"

    # Probe component health: HTTP for services that expose an endpoint, a
    # container check for telemetry/calibration (UDP-only / no port).
    console.print("[dim]Probing component health...[/dim]")
    telemetry_status = _service_running("telemetry")
    portal_status = _probe_http("http://localhost:4200")
    backend_status = _probe_http("http://localhost:8080/api/health")
    simulator_status = _probe_http("http://localhost:8081/health")
    calibration_status = _service_running("calibration")
    vllm_host, vllm_port = _parse_vllm_target()
    vllm_status = _probe_http(f"http://{vllm_host}:{vllm_port}/health")

    console.print(Panel(
        f"[bold]F1 2025 game → Telemetry[/bold]  {telemetry_status}\n"
        f"  IP:     {host_ip}  (use 127.0.0.1 if the game runs on this machine)\n"
        f"  UDP:    20777\n"
        f"  Format: 2025   Broadcast: off\n"
        f"\n"
        f"[bold]Portal[/bold]  {portal_status}\n"
        f"  http://localhost:4200\n"
        f"\n"
        f"[bold]Backend[/bold]  {backend_status}\n"
        f"  REST:      http://localhost:8080\n"
        f"  WebSocket: ws://localhost:8080/ws/race\n"
        f"  TCP in:    localhost:9090  (from telemetry)\n"
        f"\n"
        f"[bold]Simulator[/bold]  {simulator_status}\n"
        f"  http://localhost:8081\n"
        f"\n"
        f"[bold]Calibration[/bold]  {calibration_status}\n"
        f"  worker (no HTTP endpoint)\n"
        f"\n"
        f"[bold]vLLM[/bold]  {vllm_status}\n"
        f"  http://{vllm_host}:{vllm_port}\n"
        f"\n"
        f"[bold]Database[/bold]  {db_status}\n"
        f"  localhost:1521/FREEPDB1   user: pdbadmin   password: {password_note}",
        title="F1 Strategy — Service Info",
    ))


def _build_circuit_names():
    """Map track_id -> circuit name from the backend circuit resource files.

    Each backend/src/main/resources/circuits/track_<id>_<slug>.json carries
    {"trackId": int, "name": str}. Returns {} if the directory is absent; skips
    any unreadable/malformed file (callers fall back to "Track <id>").
    """
    names = {}
    if not os.path.isdir(CIRCUITS_DIR):
        return names
    for path in glob.glob(os.path.join(CIRCUITS_DIR, "track_*.json")):
        try:
            with open(path) as f:
                data = json.load(f)
            track_id = data.get("trackId")
            name = data.get("name")
            if track_id is not None and name:
                names[int(track_id)] = name
        except (OSError, ValueError):
            continue
    return names


def _radio_csv_row(row, circuit_names, driver_names):
    """Map one radio_messages row (dict keyed by _RADIO_DB_COLUMNS) to a CSV row
    in _RADIO_CSV_HEADER order. Resolves circuit_name (fallback "Track <id>") and
    driver_name (empty when unknown); formats sent_at; renders NULLs as "".
    """
    def s(v):
        return "" if v is None else str(v)

    track_id = row["track_id"]
    if track_id is None:
        circuit = ""
    else:
        circuit = circuit_names.get(int(track_id), f"Track {track_id}")
    driver = driver_names.get(row["session_uid"], "")
    sent = row["sent_at"]
    sent_str = (sent.strftime("%Y-%m-%d %H:%M:%S")
                if isinstance(sent, datetime.datetime) else s(sent))

    return [
        s(row["message_id"]), s(row["session_uid"]), sent_str,
        s(row["priority"]), s(row["session_type"]), s(track_id), circuit,
        s(row["lap_number"]), s(row["total_laps"]), s(row["player_position"]), driver,
        s(row["tyre_compound"]), s(row["tyre_age_laps"]), s(row["sector"]),
        s(row["message_text"]), s(row["best_strategies"]), s(row["rendered_text"]),
    ]


@cli.group()
def local():
    """Manage local Oracle database."""


@local.command()
def setup():
    """Create Oracle container, apply grants, and run Liquibase migrations."""
    console.print("[bold]Checking prerequisites...[/bold]")
    _check_command("podman")
    _check_command("liquibase")

    # Check podman machine on macOS
    if sys.platform == "darwin":
        result = subprocess.run(
            ["podman", "info"], capture_output=True, text=True
        )
        if result.returncode != 0:
            console.print("[red]Error:[/red] podman machine is not running. Start it with: podman machine start")
            sys.exit(1)

    # Generate password
    password = secrets.token_urlsafe(12)
    set_key(ENV_FILE, "F1STRATEGY_DB_PASSWORD", password)
    console.print("[green]Generated database password and saved to .env[/green]")

    # Create container
    if _container_exists():
        console.print(f"[yellow]Container '{CONTAINER_NAME}' already exists. Removing...[/yellow]")
        subprocess.run(["podman", "rm", "-f", CONTAINER_NAME], capture_output=True)

    console.print("[bold]Creating Oracle container...[/bold]")
    _run([
        "podman", "run", "--name", CONTAINER_NAME, "-d",
        "-p", "1521:1521",
        "-e", f"ORACLE_PWD={password}",
        CONTAINER_IMAGE,
    ])

    # Wait for database ready
    console.print("[bold]Waiting for database to be ready (up to 5 min)...[/bold]")
    start = time.time()
    ready = False
    while time.time() - start < DB_READY_TIMEOUT:
        result = subprocess.run(
            ["podman", "logs", CONTAINER_NAME], capture_output=True, text=True
        )
        if "DATABASE IS READY TO USE" in result.stdout:
            ready = True
            break
        time.sleep(DB_READY_POLL_INTERVAL)

    if not ready:
        console.print("[red]Error:[/red] Database did not become ready within 5 minutes.")
        sys.exit(1)

    console.print("[green]Database is ready.[/green]")

    # Execute grants
    console.print("[bold]Applying PDB grants...[/bold]")
    with open(GRANT_SCRIPT) as f:
        grant_sql = f.read()

    _run([
        "podman", "exec", "-i", CONTAINER_NAME,
        "sqlplus", "-s", "sys/{}@FREEPDB1 as sysdba".format(password),
    ], input=grant_sql)

    # Fix A from the GP postmortem: "DATABASE IS READY TO USE" only means SYS
    # is reachable. The PDB and the pdbadmin account take a few more seconds
    # to be ready for application logins; services racing in during that gap
    # used to pile up enough failed logins to lock the account. Block here
    # until pdbadmin can actually authenticate.
    console.print("[bold]Waiting for pdbadmin login to succeed...[/bold]")
    pdbadmin_ready = False
    pdbadmin_start = time.time()
    while time.time() - pdbadmin_start < 60:
        result = subprocess.run(
            ["podman", "exec", "-i", CONTAINER_NAME,
             "sqlplus", "-s", "-l", f"pdbadmin/{password}@FREEPDB1"],
            input="SELECT 1 FROM dual;\nEXIT;\n",
            capture_output=True, text=True,
        )
        if result.returncode == 0 and "ORA-" not in result.stdout:
            pdbadmin_ready = True
            break
        time.sleep(2)
    if not pdbadmin_ready:
        console.print("[red]Error:[/red] pdbadmin login did not succeed within 60s.")
        sys.exit(1)
    console.print("[green]pdbadmin login confirmed.[/green]")

    # Run Liquibase
    console.print("[bold]Running Liquibase migrations...[/bold]")
    _run(
        ["liquibase", f"--password={password}", "update"],
        cwd=LIQUIBASE_DIR,
    )

    # Generate service config files
    console.print("[bold]Generating service config files...[/bold]")
    _generate_configs(password)

    # Rebuild Java artifacts so compose images pick up fresh configs.
    console.print("[bold]Rebuilding telemetry + backend artifacts...[/bold]")

    base_dir = os.path.dirname(os.path.abspath(__file__))

    telemetry_dir = os.path.join(base_dir, "telemetry")
    console.print("  [dim]./gradlew installDist (telemetry)...[/dim]")
    _run(["./gradlew", "installDist", "-q"], cwd=telemetry_dir)
    console.print("  [green]Built[/green] telemetry/build/install/telemetry/")

    backend_dir = os.path.join(base_dir, "backend")
    console.print("  [dim]./gradlew bootJar (backend)...[/dim]")
    _run(["./gradlew", "bootJar", "-q"], cwd=backend_dir)
    console.print("  [green]Built[/green] backend/build/libs/")

    console.print(Panel(
        f"[green]Database setup complete![/green]\n\n"
        f"  Host:     localhost\n"
        f"  Port:     1521\n"
        f"  Service:  FREEPDB1\n"
        f"  User:     pdbadmin\n"
        f"  Password: (see .env)",
        title="Connection Info",
    ))


@local.command()
def build():
    """Rebuild artifacts + container images (no cache), then restart the stack.

    Run this after `setup`. `setup` rotates the DB password, regenerates configs,
    and rebuilds the Gradle artifacts — but the password is baked into the images,
    and `podman compose up --build` reuses cached COPY layers, so the running
    containers keep the OLD password and every service fails with ORA-01017.
    This forces a `--no-cache` image build so the fresh config is actually baked in.
    """
    _check_command("podman")
    base_dir = os.path.dirname(os.path.abspath(__file__))

    # Java Containerfiles COPY these pre-built outputs; refresh them in case the
    # source changed since the last `setup` (no-op/fast when already current).
    console.print("[bold]Rebuilding Java artifacts...[/bold]")
    console.print("  [dim]./gradlew installDist (telemetry)...[/dim]")
    _run(["./gradlew", "installDist", "-q"], cwd=os.path.join(base_dir, "telemetry"))
    console.print("  [dim]./gradlew bootJar (backend)...[/dim]")
    _run(["./gradlew", "bootJar", "-q"], cwd=os.path.join(base_dir, "backend"))

    # Force a clean image build — `up --build` caches stale layers and bakes the
    # old password. --no-cache is the whole point of this command.
    console.print("[bold]Building images (--no-cache, can take a few minutes)...[/bold]")
    if subprocess.run(["podman", "compose", "build", "--no-cache"], cwd=base_dir).returncode != 0:
        console.print("[red]Error:[/red] image build failed.")
        sys.exit(1)

    # `podman compose up -d` will NOT recreate already-running containers, so it
    # leaves them on the OLD image. Tear them down first so `up` creates fresh
    # containers from the rebuilt images. (Oracle is not in compose, so it stays up.)
    console.print("[bold]Recreating containers from the new images...[/bold]")
    subprocess.run(["podman", "compose", "down"], cwd=base_dir)
    if subprocess.run(["podman", "compose", "up", "-d"], cwd=base_dir).returncode != 0:
        console.print("[red]Error:[/red] compose up failed.")
        sys.exit(1)

    console.print("[green]Rebuild complete. Services restarted with current config.[/green]")


@local.command()
def clean():
    """Stop and remove the Oracle container, clear password from .env."""
    if _container_exists():
        console.print(f"[bold]Stopping and removing '{CONTAINER_NAME}'...[/bold]")
        subprocess.run(["podman", "rm", "-f", CONTAINER_NAME], capture_output=True)
        console.print("[green]Container removed.[/green]")
    else:
        console.print(f"[yellow]Container '{CONTAINER_NAME}' does not exist.[/yellow]")

    if os.path.exists(ENV_FILE):
        set_key(ENV_FILE, "F1STRATEGY_DB_PASSWORD", "")
        console.print("[green]Password cleared from .env[/green]")

    # Remove generated config files
    console.print("[bold]Cleaning generated config files...[/bold]")
    _clean_configs()


@local.command()
def status():
    """Show container status and connection info."""
    if not _container_exists():
        console.print(f"[yellow]Container '{CONTAINER_NAME}' does not exist.[/yellow]")
        return

    if _container_running():
        password = _get_password()
        console.print(Panel(
            f"[green]Container is running.[/green]\n\n"
            f"  Host:     localhost\n"
            f"  Port:     1521\n"
            f"  Service:  FREEPDB1\n"
            f"  User:     pdbadmin\n"
            f"  Password: {'(set)' if password else '(not set)'}",
            title="Connection Info",
        ))
    else:
        console.print(f"[yellow]Container '{CONTAINER_NAME}' exists but is not running.[/yellow]")


@local.command()
def unlock():
    """Unlock the pdbadmin account after ORA-28000 (too many failed logins)."""
    password = _get_password()
    if not password:
        console.print("[red]Error:[/red] No password in .env. Is the database set up?")
        sys.exit(1)
    if not _container_running():
        console.print("[red]Error:[/red] Database container is not running.")
        sys.exit(1)

    console.print("[bold]Unlocking pdbadmin account...[/bold]")
    sql = (
        'ALTER USER pdbadmin ACCOUNT UNLOCK;\n'
        f'ALTER USER pdbadmin IDENTIFIED BY "{password}";\n'
        'EXIT;\n'
    )
    _run(
        ["podman", "exec", "-i", CONTAINER_NAME,
         "sqlplus", "-s", f"sys/{password}@FREEPDB1 as sysdba"],
        input=sql,
    )
    console.print("[green]pdbadmin unlocked and failed-login counter reset.[/green]")


@local.command(name="export")
def export_cmd():
    """Export all session and calibration data to a SQL backup file."""
    password = _get_password()
    if not password:
        console.print("[red]Error:[/red] No password in .env. Is the database set up?")
        sys.exit(1)
    if not _container_running():
        console.print("[red]Error:[/red] Database container is not running.")
        sys.exit(1)

    os.makedirs(BACKUP_DIR, exist_ok=True)
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_path = os.path.join(BACKUP_DIR, f"export_{timestamp}.sql")

    console.print("[bold]Connecting to database...[/bold]")
    conn = _db_connect(password)
    cursor = conn.cursor()

    total_rows = 0
    with open(backup_path, "w") as f:
        f.write(f"-- F1 Strategy Database Export\n")
        f.write(f"-- Date: {datetime.datetime.now().isoformat()}\n\n")
        f.write("SET DEFINE OFF\n\n")

        for table_name, pk_cols in _EXPORT_TABLES:
            cursor.execute(f"SELECT * FROM {table_name}")
            columns = [col[0].lower() for col in cursor.description]
            rows = cursor.fetchall()

            f.write(f"-- Table: {table_name} ({len(rows)} rows)\n")
            console.print(f"  {table_name}: {len(rows)} rows")

            for row in rows:
                select_parts = ", ".join(
                    f"{_format_sql_value(v)} AS {c}" for c, v in zip(columns, row)
                )
                on_clause = " AND ".join(f"t.{c} = s.{c}" for c in pk_cols)
                insert_cols = ", ".join(columns)
                insert_vals = ", ".join(f"s.{c}" for c in columns)

                f.write(
                    f"MERGE INTO {table_name} t "
                    f"USING (SELECT {select_parts} FROM dual) s "
                    f"ON ({on_clause}) "
                    f"WHEN NOT MATCHED THEN INSERT ({insert_cols}) "
                    f"VALUES ({insert_vals});\n"
                )

            f.write("COMMIT;\n\n")
            total_rows += len(rows)

        # Sequence reset
        f.write("-- Reset sequences\n")
        for seq_name, table_name, column_name in _SEQUENCES:
            f.write(f"DECLARE\n  v_max NUMBER;\nBEGIN\n")
            f.write(f"  SELECT NVL(MAX({column_name}), 0) + 1 INTO v_max FROM {table_name};\n")
            f.write(
                f"  EXECUTE IMMEDIATE "
                f"'ALTER SEQUENCE {seq_name} RESTART START WITH ' || v_max;\n"
            )
            f.write("END;\n/\n\n")

        f.write("COMMIT;\nEXIT;\n")

    conn.close()
    console.print(f"\n[green]Export complete:[/green] {backup_path} ({total_rows} total rows)")


@local.command(name="import")
@click.argument("backup_file", required=False)
def import_cmd(backup_file):
    """Import data from a backup file. Defaults to the latest backup."""
    password = _get_password()
    if not password:
        console.print("[red]Error:[/red] No password in .env. Is the database set up?")
        sys.exit(1)
    if not _container_running():
        console.print("[red]Error:[/red] Database container is not running.")
        sys.exit(1)

    if backup_file is None:
        if not os.path.isdir(BACKUP_DIR):
            console.print("[red]Error:[/red] No backups directory found.")
            sys.exit(1)
        backups = sorted(
            f for f in os.listdir(BACKUP_DIR)
            if f.startswith("export_") and f.endswith(".sql")
        )
        if not backups:
            console.print("[red]Error:[/red] No backup files found in database/backups/.")
            sys.exit(1)
        backup_file = os.path.join(BACKUP_DIR, backups[-1])

    if not os.path.isfile(backup_file):
        console.print(f"[red]Error:[/red] File not found: {backup_file}")
        sys.exit(1)

    console.print(f"[bold]Importing from:[/bold] {os.path.basename(backup_file)}")

    with open(backup_file) as f:
        sql = f.read()

    _run(
        ["podman", "exec", "-i", CONTAINER_NAME,
         "sqlplus", "-s", f"pdbadmin/{password}@FREEPDB1"],
        input=sql,
    )

    console.print("[green]Import complete.[/green]")


@local.command(name="export-radio")
@click.option("--output", "output_path", default=None,
              help="CSV output path (default: database/exports/radio_dataset_<timestamp>.csv).")
def export_radio_cmd(output_path):
    """Export radio messages with their full LLM render context to a CSV.

    One row per radio message: traceability IDs, the render context the LLM
    renderer receives (RadioRenderContext) with circuit and driver names resolved,
    plus the original message_text and the current rendered_text baseline. For
    offline testing of different models / custom instructions.
    """
    password = _get_password()
    if not password:
        console.print("[red]Error:[/red] No password in .env. Is the database set up?")
        sys.exit(1)
    if not _container_running():
        console.print("[red]Error:[/red] Database container is not running.")
        sys.exit(1)

    if output_path is None:
        os.makedirs(EXPORTS_DIR, exist_ok=True)
        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        output_path = os.path.join(EXPORTS_DIR, f"radio_dataset_{timestamp}.csv")
    else:
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)

    console.print("[bold]Resolving circuit names...[/bold]")
    circuit_names = _build_circuit_names()

    console.print("[bold]Connecting to database...[/bold]")
    conn = _db_connect(password)
    cursor = conn.cursor()

    cursor.execute(
        "SELECT session_uid, MAX(driver_name) FROM participants "
        "WHERE ai_controlled = 0 AND TRIM(driver_name) IS NOT NULL "
        "GROUP BY session_uid"
    )
    driver_names = {uid: name for uid, name in cursor.fetchall()}

    cursor.execute(
        "SELECT " + ", ".join(_RADIO_DB_COLUMNS) + " FROM radio_messages "
        "ORDER BY session_uid, message_id"
    )

    count = 0
    with open(output_path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(_RADIO_CSV_HEADER)
        for db_row in cursor:
            row = dict(zip(_RADIO_DB_COLUMNS, db_row))
            writer.writerow(_radio_csv_row(row, circuit_names, driver_names))
            count += 1

    conn.close()
    console.print(f"\n[green]Radio export complete:[/green] {output_path} ({count} rows)")


@local.command(name="repair-sectors")
@click.option("--apply", "apply_changes", is_flag=True,
              help="Write the fixes. Without it, only previews what would change.")
def repair_sectors_cmd(apply_changes):
    """Repair third-sector rows corrupted by the old telemetry capture bug.

    Historically sector_number=2 stored the whole lap time instead of the third
    sector. This re-derives S3 = lap_time_ms - S1 - S2 from the sibling sector
    rows, and quarantines (outlier=1) any that can't be derived. Idempotent and
    safe to re-run. Defaults to a dry run; pass --apply to write. Re-calibrate
    affected tracks afterwards (`python -m calibration run <trackId>`).
    """
    password = _get_password()
    if not password:
        console.print("[red]Error:[/red] No password in .env. Is the database set up?")
        sys.exit(1)
    if not _container_running():
        console.print("[red]Error:[/red] Database container is not running.")
        sys.exit(1)

    conn = _db_connect(password)
    cur = conn.cursor()
    # A real third sector can never be >= the whole lap, so that flags the bug
    # precisely and leaves already-correct (and already-repaired) rows untouched.
    cur.execute(
        "SELECT t.session_uid, t.car_index, t.lap_number, t.lap_time_ms, "
        "       s0.sector_time_ms, s1.sector_time_ms, s.track_id "
        "FROM sector_snapshots t "
        "JOIN sessions s ON s.session_uid = t.session_uid "
        "LEFT JOIN sector_snapshots s0 ON s0.session_uid = t.session_uid "
        "  AND s0.car_index = t.car_index AND s0.lap_number = t.lap_number AND s0.sector_number = 0 "
        "LEFT JOIN sector_snapshots s1 ON s1.session_uid = t.session_uid "
        "  AND s1.car_index = t.car_index AND s1.lap_number = t.lap_number AND s1.sector_number = 1 "
        "WHERE t.sector_number = 2 AND t.lap_time_ms > 0 AND t.sector_time_ms >= t.lap_time_ms"
    )
    fixes, quarantine, tracks = [], [], set()
    for uid, car, lap, laptime, s1, s2, track in cur:
        tracks.add(track)
        if s1 and s2 and s1 > 0 and s2 > 0 and (laptime - s1 - s2) > 0:
            fixes.append((laptime - s1 - s2, uid, car, lap))
        else:
            quarantine.append((uid, car, lap))

    total = len(fixes) + len(quarantine)
    console.print(f"[bold]Corrupted third-sector rows:[/bold] {total} "
                  f"(tracks {sorted(tracks) if tracks else '—'})")
    console.print(f"  re-derivable (S3 = lap - S1 - S2): {len(fixes)}")
    console.print(f"  not derivable -> quarantine (outlier=1): {len(quarantine)}")
    for new_s3, _uid, car, lap in fixes[:5]:
        console.print(f"    e.g. car {car} lap {lap}: S3 -> {new_s3} ms")

    if total == 0:
        console.print("[green]Nothing to repair.[/green]")
        conn.close()
        return

    if not apply_changes:
        console.print("\n[yellow]Dry run[/yellow] — re-run with [bold]--apply[/bold] to write the changes.")
        conn.close()
        return

    if fixes:
        cur.executemany(
            "UPDATE sector_snapshots SET sector_time_ms = :1 "
            "WHERE session_uid = :2 AND car_index = :3 AND lap_number = :4 AND sector_number = 2",
            fixes)
    if quarantine:
        cur.executemany(
            "UPDATE sector_snapshots SET outlier = 1 "
            "WHERE session_uid = :1 AND car_index = :2 AND lap_number = :3 AND sector_number = 2",
            quarantine)
    conn.commit()
    conn.close()
    console.print(f"[green]Repaired {len(fixes)} rows, quarantined {len(quarantine)}.[/green]")
    console.print("Re-calibrate affected tracks: "
                  + "  ".join(f"python -m calibration run {t}" for t in sorted(tracks)))


if __name__ == "__main__":
    cli()
