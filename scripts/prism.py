#!/usr/bin/env python3
"""Local development tool. Run through the AI service environment:

    uv run --project services/ai python scripts/prism.py <command>

Commands: setup, db-start, db-stop, db-status, db-reset, migrate, seed, run-ai, run-core, run-web,
install-temporal, run-temporal.

`setup` creates a project-owned PostgreSQL cluster under .data/ using the installed
PostgreSQL binaries. It leaves the machine's PostgreSQL service untouched and needs no
existing database password. All generated secrets go to the gitignored .env and .data/.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import secrets
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / ".data"
CLUSTER = DATA / "postgres"
KEYS = DATA / "keys"
ENV_FILE = ROOT / ".env"

PG_PORT = 5544
DATABASE = "prism"
OWNER = "prism_owner"
RUNTIME_ROLES = ("prism_core_app", "prism_ai_app", "prism_audit_reader")

TOOLS = DATA / "tools"
TEMPORAL_VERSION = "1.10.0"
TEMPORAL_ARCHIVE = f"temporal_cli_{TEMPORAL_VERSION}_windows_amd64.zip"
TEMPORAL_URL = f"https://github.com/temporalio/cli/releases/download/v{TEMPORAL_VERSION}/{TEMPORAL_ARCHIVE}"
TEMPORAL_SHA256 = "b95695d08a564621b02322592bbb525a924f76cc892084fc3dd7d2e90f42b8bd"


def pg_tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    candidates = sorted(Path("C:/Program Files/PostgreSQL").glob(f"*/bin/{name}.exe"), reverse=True)
    if candidates:
        return str(candidates[0])
    sys.exit(f"Could not find {name}. Install PostgreSQL 18 or add its bin directory to PATH.")


def run(*args: str, env: dict[str, str] | None = None) -> None:
    subprocess.run(args, check=True, env={**os.environ, **(env or {})})


def new_secret() -> str:
    return secrets.token_urlsafe(32)


def read_env() -> dict[str, str]:
    if not ENV_FILE.exists():
        sys.exit("No .env file. Run the setup command first.")
    pairs = (line.split("=", 1) for line in ENV_FILE.read_text().splitlines() if "=" in line and not line.startswith("#"))
    return {key.strip(): value.strip() for key, value in pairs}


def write_service_keys() -> tuple[Path, Path]:
    KEYS.mkdir(parents=True, exist_ok=True)
    private_path, public_path = KEYS / "service-jwt-private.pem", KEYS / "service-jwt-public.pem"
    if private_path.exists():
        return private_path, public_path
    key = ec.generate_private_key(ec.SECP256R1())
    private_path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    public_path.write_bytes(
        key.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )
    return private_path, public_path


def db_start() -> None:
    status = subprocess.run([pg_tool("pg_ctl"), "status", "-D", str(CLUSTER)], capture_output=True)
    if status.returncode == 0:
        print("Database already running.")
        return
    # The server outlives this script. Standard streams must not be inherited, or a caller
    # reading this script's output would wait on them until the database stops.
    subprocess.run(
        [pg_tool("pg_ctl"), "start", "-w", "-D", str(CLUSTER), "-l", str(DATA / "postgres.log")],
        check=True,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def db_stop() -> None:
    run(pg_tool("pg_ctl"), "stop", "-m", "fast", "-D", str(CLUSTER))


def db_status() -> None:
    subprocess.run([pg_tool("pg_ctl"), "status", "-D", str(CLUSTER)])


def init_cluster(owner_password: str) -> None:
    DATA.mkdir(exist_ok=True)
    password_file = DATA / "initdb-password.tmp"
    password_file.write_text(owner_password)
    try:
        run(
            pg_tool("initdb"), "-D", str(CLUSTER), "-U", OWNER, "--pwfile", str(password_file),
            "--auth=scram-sha-256", "--encoding=UTF8", "--locale=C",
        )
    finally:
        password_file.unlink()
    with (CLUSTER / "postgresql.conf").open("a") as config:
        config.write(f"\nport = {PG_PORT}\nlisten_addresses = '127.0.0.1'\n")


def psql(owner_password: str, database: str, sql: str) -> None:
    run(
        pg_tool("psql"), "-q", "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1", "-p", str(PG_PORT),
        "-U", OWNER, "-d", database, "-c", sql,
        env={"PGPASSWORD": owner_password},
    )


def setup() -> None:
    if ENV_FILE.exists() or CLUSTER.exists():
        sys.exit("Already set up: .env or .data/postgres exists. Remove both to start again.")

    owner_password = new_secret()
    passwords = {role: new_secret() for role in RUNTIME_ROLES}

    init_cluster(owner_password)
    db_start()
    psql(owner_password, "postgres", f"CREATE DATABASE {DATABASE} ENCODING 'UTF8' TEMPLATE template0")
    for role, password in passwords.items():
        psql(owner_password, "postgres", f"CREATE ROLE {role} LOGIN PASSWORD '{password}'")

    private_key, public_key = write_service_keys()
    jdbc = f"jdbc:postgresql://127.0.0.1:{PG_PORT}/{DATABASE}"
    values = {
        "PRISM_ENVIRONMENT": "local",
        "PRISM_CORE_DB_URL": jdbc,
        "PRISM_CORE_DB_USER": "prism_core_app",
        "PRISM_CORE_DB_PASSWORD": passwords["prism_core_app"],
        "PRISM_MIGRATION_DB_URL": jdbc,
        "PRISM_MIGRATION_DB_USER": OWNER,
        "PRISM_MIGRATION_DB_PASSWORD": owner_password,
        "PRISM_AI_DB_URL": (
            f"postgresql://prism_ai_app:{passwords['prism_ai_app']}@127.0.0.1:{PG_PORT}/{DATABASE}"
        ),
        "PRISM_TEST_ADMIN_DB_URL": f"jdbc:postgresql://127.0.0.1:{PG_PORT}/postgres",
        "PRISM_TEST_ADMIN_DB_USER": OWNER,
        "PRISM_TEST_ADMIN_DB_PASSWORD": owner_password,
        "PRISM_SERVICE_JWT_PRIVATE_KEY_PATH": private_key.as_posix(),
        "PRISM_SERVICE_JWT_PUBLIC_KEY_PATH": public_key.as_posix(),
        "PRISM_AI_BASE_URL": "http://127.0.0.1:8090",
        "PRISM_CORE_BASE_URL": "http://127.0.0.1:8080",
        "PRISM_WEB_BASE_URL": "http://localhost:3000",
        "PRISM_THROTTLE_HMAC_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
        "PRISM_COOKIE_SECURE": "false",
        "PRISM_FORWARDED_HEADER_TRUSTED": "true",
        "PRISM_BOOTSTRAP_ADMIN_EMAIL": "",
        "PRISM_BOOTSTRAP_INVITE_FILE": (DATA / "bootstrap-invitation.txt").as_posix(),
        "PRISM_SEED_CREDENTIALS_FILE": (DATA / "synthetic-users.md").as_posix(),
        "PRISM_DOCUMENT_STORE_PATH": (DATA / "documents").as_posix(),
    }
    ENV_FILE.write_text("".join(f"{key}={value}\n" for key, value in values.items()))
    print(f"Created .env and a local database on port {PG_PORT}. Next: the migrate command.")


def maven(*goals: str) -> None:
    executable = "mvnw.cmd" if os.name == "nt" else "./mvnw"
    subprocess.run(
        [str(ROOT / "services/core" / executable), "-q", "-B", "-ntp", *goals],
        cwd=ROOT / "services/core",
        check=True,
        env={**os.environ, **read_env()},
        shell=os.name == "nt",
    )


def migrate() -> None:
    maven("flyway:migrate")
    print("Migrations applied.")


def seed() -> None:
    maven("spring-boot:run", "-Dspring-boot.run.profiles=seed")


def db_reset() -> None:
    """Restores a clean local database. The audit trail cannot be deleted row by row, by design,
    so the local database is dropped and rebuilt instead."""
    env = read_env()
    if env.get("PRISM_ENVIRONMENT") != "local" or f":{PG_PORT}/" not in env["PRISM_MIGRATION_DB_URL"]:
        sys.exit("Refusing to reset: this only runs against the project's local database.")
    owner_password = env["PRISM_MIGRATION_DB_PASSWORD"]
    psql(owner_password, "postgres", f"DROP DATABASE IF EXISTS {DATABASE} WITH (FORCE)")
    psql(owner_password, "postgres", f"CREATE DATABASE {DATABASE} ENCODING 'UTF8' TEMPLATE template0")
    migrate()
    seed()
    print("Local database reset and seeded. Passwords are in .data/synthetic-users.md.")


def run_ai() -> None:
    subprocess.run([sys.executable, "-m", "prism_ai.serve"], cwd=ROOT / "services/ai", check=True)


def run_core() -> None:
    maven("spring-boot:run")


def run_web() -> None:
    subprocess.run("npm run dev", cwd=ROOT / "web", check=True, shell=True)


def temporal_binary() -> Path:
    return TOOLS / f"temporal-{TEMPORAL_VERSION}" / "temporal.exe"


def install_temporal() -> None:
    """Installs the native Temporal CLI, which includes a local server. Prism uses no Docker."""
    binary = temporal_binary()
    if binary.exists():
        print(f"Temporal {TEMPORAL_VERSION} is already installed.")
        return
    if os.name != "nt":
        sys.exit(f"Install Temporal CLI {TEMPORAL_VERSION} from temporal.io, and add it to PATH.")
    TOOLS.mkdir(parents=True, exist_ok=True)
    archive = TOOLS / TEMPORAL_ARCHIVE
    urllib.request.urlretrieve(TEMPORAL_URL, archive)  # noqa: S310 - fixed HTTPS URL
    try:
        if hashlib.sha256(archive.read_bytes()).hexdigest() != TEMPORAL_SHA256:
            sys.exit("The Temporal download does not match its published checksum. Nothing was installed.")
        with zipfile.ZipFile(archive) as contents:
            contents.extract("temporal.exe", binary.parent)
    finally:
        archive.unlink()
    print(f"Temporal {TEMPORAL_VERSION} installed.")


def run_temporal() -> None:
    binary = shutil.which("temporal") or temporal_binary()
    if not Path(binary).exists():
        sys.exit("Temporal is not installed. Run the install-temporal command first.")
    # A database file keeps workflows across restarts. The server listens on this machine only.
    subprocess.run(
        [str(binary), "server", "start-dev", "--ip", "127.0.0.1", "--db-filename", str(DATA / "temporal.db")],
        check=True,
    )


COMMANDS = {
    "setup": setup,
    "db-start": db_start,
    "db-stop": db_stop,
    "db-status": db_status,
    "db-reset": db_reset,
    "migrate": migrate,
    "seed": seed,
    "run-ai": run_ai,
    "run-core": run_core,
    "run-web": run_web,
    "install-temporal": install_temporal,
    "run-temporal": run_temporal,
}


def main() -> None:
    parser = argparse.ArgumentParser(description="Prism local development tool")
    parser.add_argument("command", choices=COMMANDS)
    COMMANDS[parser.parse_args().command]()


if __name__ == "__main__":
    main()
