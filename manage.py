#!/usr/bin/env python3
"""CLI for managing the F1 Strategy local development environment."""

import os
import secrets
import shutil
import subprocess
import sys
import time

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
    return os.environ.get("LOCAL_DB_PASSWORD", "")


@click.group()
def cli():
    """F1 Strategy development environment manager."""


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
            ["podman", "machine", "info"], capture_output=True, text=True
        )
        if result.returncode != 0:
            console.print("[red]Error:[/red] podman machine is not running. Start it with: podman machine start")
            sys.exit(1)

    # Generate password
    password = secrets.token_urlsafe(12)
    set_key(ENV_FILE, "LOCAL_DB_PASSWORD", password)
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

    # Run Liquibase
    console.print("[bold]Running Liquibase migrations...[/bold]")
    _run(
        ["liquibase", f"--password={password}", "update"],
        cwd=LIQUIBASE_DIR,
    )

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
def clean():
    """Stop and remove the Oracle container, clear password from .env."""
    if _container_exists():
        console.print(f"[bold]Stopping and removing '{CONTAINER_NAME}'...[/bold]")
        subprocess.run(["podman", "rm", "-f", CONTAINER_NAME], capture_output=True)
        console.print("[green]Container removed.[/green]")
    else:
        console.print(f"[yellow]Container '{CONTAINER_NAME}' does not exist.[/yellow]")

    if os.path.exists(ENV_FILE):
        set_key(ENV_FILE, "LOCAL_DB_PASSWORD", "")
        console.print("[green]Password cleared from .env[/green]")


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


if __name__ == "__main__":
    cli()
