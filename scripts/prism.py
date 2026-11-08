#!/usr/bin/env python3
"""Local development tool. Run through the AI service environment:

    uv run --project services/ai python scripts/prism.py <command>

Commands: setup, db-start, db-stop, db-status, db-reset, migrate, seed, run-ai, run-core, run-web,
install-temporal, run-temporal, install-pgvector, run-model-stub, e2e-stack.

`setup` creates a project-owned PostgreSQL cluster under .data/ using the installed
PostgreSQL binaries. It leaves the machine's PostgreSQL service untouched and needs no
existing database password. All generated secrets go to the gitignored .env and .data/.

`e2e-stack` runs a complete, separate copy of Prism beside the development services: its own ports,
database, documents, and Temporal server, with a stand-in for the model provider. It stays up until
stopped. Run the browser tests against it with:

    $env:PRISM_E2E_PORT="3100"; $env:PRISM_E2E_CREDENTIALS="../.data/e2e/synthetic-users.md"; npx playwright test
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

import psutil
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

# pgvector has no official Windows binaries, so it is built from its release source (D-074). The
# hash pins this exact archive, downloaded from the official repository on 2026-11-07.
PGVECTOR_VERSION = "0.8.6"
PGVECTOR_URL = f"https://github.com/pgvector/pgvector/archive/refs/tags/v{PGVECTOR_VERSION}.zip"
PGVECTOR_SHA256 = "e93a1567219c9ce523ca16473f6c41cc80e01345b2d91ccdee40b473b7c5dd0a"
PGVECTOR_HOME = DATA / "pgvector"
VSWHERE = Path(r"C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe")

E2E_DATA = DATA / "e2e"
E2E_DATABASE = "prism_e2e"
E2E_WEB_PORT = 3100
E2E_CORE_PORT = 8180
E2E_AI_PORT = 8190
E2E_TEMPORAL_PORT = 7333
MODEL_STUB_PORT = 8099
STARTUP_TIMEOUT_SECONDS = 180


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
        # Real variables win over the file, so that e2e-stack can point at its own database.
        env={**read_env(), **os.environ},
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


def vcvars() -> Path:
    """The Visual Studio script that sets up the C++ compiler, which a Windows build needs."""
    if not VSWHERE.exists():
        sys.exit("Install Visual Studio 2022 Build Tools with the C++ workload first. See docs/decisions.md D-074.")
    found = subprocess.run(
        [str(VSWHERE), "-latest", "-products", "*", "-requires",
         "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    script = Path(found) / "VC/Auxiliary/Build/vcvars64.bat"
    if not found or not script.exists():
        sys.exit("The C++ build tools were not found. Add the 'Desktop development with C++' workload.")
    return script


def install_pgvector() -> None:
    """Builds pgvector for the installed PostgreSQL 18 and installs it under .data/, so that the
    machine's PostgreSQL installation is untouched and no administrator rights are needed."""
    library = PGVECTOR_HOME / "lib" / "vector.dll"
    if not library.exists():
        downloads = DATA / "downloads"
        downloads.mkdir(parents=True, exist_ok=True)
        archive = downloads / f"pgvector-{PGVECTOR_VERSION}.zip"
        if not archive.exists():
            urllib.request.urlretrieve(PGVECTOR_URL, archive)  # noqa: S310 - fixed HTTPS URL
        if hashlib.sha256(archive.read_bytes()).hexdigest() != PGVECTOR_SHA256:
            archive.unlink()
            sys.exit("The pgvector download does not match its pinned hash, so it was removed.")
        build = DATA / "build"
        shutil.rmtree(build, ignore_errors=True)
        build.mkdir(parents=True)
        with zipfile.ZipFile(archive) as contents:
            contents.extractall(build)
        source = build / f"pgvector-{PGVECTOR_VERSION}"
        pg_root = Path(pg_tool("pg_config")).resolve().parent.parent
        subprocess.run(
            f'call "{vcvars()}" >nul && nmake /NOLOGO /F Makefile.win PGROOT="{pg_root}"',
            cwd=source, shell=True, check=True,
        )
        extension = PGVECTOR_HOME / "share" / "extension"
        extension.mkdir(parents=True, exist_ok=True)
        library.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / "vector.dll", library)
        for script in (source / "sql").glob("vector--*.sql"):
            shutil.copy2(script, extension)
        # A bare module name is looked up on dynamic_library_path; $libdir would point at Program Files.
        control = (source / "vector.control").read_text(encoding="ascii")
        (extension / "vector.control").write_text(
            control.replace("module_pathname = '$libdir/vector'", "module_pathname = 'vector'"), encoding="ascii"
        )
        shutil.rmtree(build, ignore_errors=True)
    configure_pgvector()
    print(f"pgvector {PGVECTOR_VERSION} is installed for the local cluster.")


def configure_pgvector() -> None:
    """Points the cluster at .data/pgvector, then proves the extension loads in a scratch database."""
    password = read_env()["PRISM_MIGRATION_DB_PASSWORD"]
    share = (PGVECTOR_HOME / "share").as_posix()
    lib = (PGVECTOR_HOME / "lib").as_posix()
    psql(password, "postgres", f"ALTER SYSTEM SET extension_control_path = '$system;{share}'")
    psql(password, "postgres", f"ALTER SYSTEM SET dynamic_library_path = '$libdir;{lib}'")
    psql(password, "postgres", "SELECT pg_reload_conf()")
    psql(password, "postgres", "DROP DATABASE IF EXISTS prism_pgvector_check")
    psql(password, "postgres", "CREATE DATABASE prism_pgvector_check")
    try:
        psql(password, "prism_pgvector_check", "CREATE EXTENSION vector")
        psql(password, "prism_pgvector_check", "SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector")
    finally:
        psql(password, "postgres", "DROP DATABASE IF EXISTS prism_pgvector_check")


def run_model_stub() -> None:
    subprocess.run([sys.executable, "-m", "tests.support.stub_openrouter"], cwd=ROOT / "services/ai", check=True)


def wait_for_port(port: int, name: str) -> None:
    deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        with socket.socket() as probe:
            probe.settimeout(1)
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                print(f"{name} is up on port {port}.")
                return
        time.sleep(1)
    sys.exit(f"{name} did not start on port {port}.")


def e2e_environment(env: dict[str, str]) -> dict[str, str]:
    """Points every service at the separate stack. The development database is never touched."""

    def other_database(url: str) -> str:
        return re.sub(rf"/{DATABASE}(?=$|\?)", f"/{E2E_DATABASE}", url)

    return {
        "PRISM_MIGRATION_DB_URL": other_database(env["PRISM_MIGRATION_DB_URL"]),
        "PRISM_CORE_DB_URL": other_database(env["PRISM_CORE_DB_URL"]),
        "PRISM_AI_DB_URL": other_database(env["PRISM_AI_DB_URL"]),
        "PRISM_AI_BASE_URL": f"http://127.0.0.1:{E2E_AI_PORT}",
        "PRISM_AI_PORT": str(E2E_AI_PORT),
        "PRISM_CORE_BASE_URL": f"http://127.0.0.1:{E2E_CORE_PORT}",
        "PRISM_WEB_BASE_URL": f"http://localhost:{E2E_WEB_PORT}",
        "SERVER_PORT": str(E2E_CORE_PORT),
        "PRISM_TEMPORAL_TARGET": f"127.0.0.1:{E2E_TEMPORAL_PORT}",
        "PRISM_DOCUMENT_STORE_PATH": str(E2E_DATA / "documents"),
        "PRISM_SEED_CREDENTIALS_FILE": str(E2E_DATA / "synthetic-users.md"),
        "PRISM_BOOTSTRAP_INVITE_FILE": str(E2E_DATA / "first-admin-invite.txt"),
        "PRISM_OPENROUTER_BASE_URL": f"http://127.0.0.1:{MODEL_STUB_PORT}/api/v1",
        "OPENROUTER_API_KEY": "stub-key-not-valid-anywhere",
    }


def stop_all(processes: list[subprocess.Popen[bytes]]) -> None:
    """Stops whole process trees: Maven and npm run the real server as a child."""
    for process in processes:
        try:
            parent = psutil.Process(process.pid)
        except psutil.NoSuchProcess:
            continue
        for member in [*parent.children(recursive=True), parent]:
            try:
                member.kill()
            except psutil.NoSuchProcess:
                continue


def e2e_stack() -> None:
    db_start()
    env = read_env()
    overrides = e2e_environment(env)
    os.environ.update(overrides)
    if E2E_DATA.exists():
        shutil.rmtree(E2E_DATA)
    E2E_DATA.mkdir(parents=True)

    owner_password = env["PRISM_MIGRATION_DB_PASSWORD"]
    psql(owner_password, "postgres", f"DROP DATABASE IF EXISTS {E2E_DATABASE} WITH (FORCE)")
    psql(owner_password, "postgres", f"CREATE DATABASE {E2E_DATABASE} ENCODING 'UTF8' TEMPLATE template0")
    migrate()
    seed()

    binary = shutil.which("temporal") or temporal_binary()
    executable = "mvnw.cmd" if os.name == "nt" else "./mvnw"
    build = subprocess.run("npm run build", cwd=ROOT / "web", shell=True, check=False)
    if build.returncode != 0:
        sys.exit("The web build failed.")

    log = (E2E_DATA / "services.log").open("ab")
    start = {"stdout": log, "stderr": subprocess.STDOUT, "stdin": subprocess.DEVNULL}
    processes = [
        subprocess.Popen(
            [
                str(binary), "server", "start-dev", "--ip", "127.0.0.1", "--port", str(E2E_TEMPORAL_PORT),
                "--ui-port", str(E2E_TEMPORAL_PORT + 1000), "--headless",
                "--db-filename", str(E2E_DATA / "temporal.db"),
            ],
            **start,
        ),
        subprocess.Popen([sys.executable, "-m", "tests.support.stub_openrouter"], cwd=ROOT / "services/ai", **start),
        subprocess.Popen([sys.executable, "-m", "prism_ai.serve"], cwd=ROOT / "services/ai", **start),
        subprocess.Popen(
            [str(ROOT / "services/core" / executable), "-q", "-B", "-ntp", "spring-boot:run"],
            cwd=ROOT / "services/core",
            env={**env, **os.environ},
            shell=os.name == "nt",
            **start,
        ),
        subprocess.Popen(f"npm run start -- -p {E2E_WEB_PORT}", cwd=ROOT / "web", shell=True, **start),
    ]
    try:
        wait_for_port(E2E_TEMPORAL_PORT, "Temporal")
        wait_for_port(MODEL_STUB_PORT, "Model stub")
        wait_for_port(E2E_AI_PORT, "AI service")
        wait_for_port(E2E_CORE_PORT, "Core service")
        wait_for_port(E2E_WEB_PORT, "Web")
        print(f"E2E stack is ready at http://localhost:{E2E_WEB_PORT}. Stop this command to shut it down.", flush=True)
        while True:
            time.sleep(5)
    finally:
        stop_all(processes)


def demo_prepare() -> None:
    """Publishes every policy and loads the presentation claims, through the API of the running stack."""
    sys.path.insert(0, str(ROOT / "scripts"))
    import demo_prepare as preparation

    preparation.main(read_env()["PRISM_CORE_BASE_URL"])


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
    "install-pgvector": install_pgvector,
    "run-model-stub": run_model_stub,
    "e2e-stack": e2e_stack,
    "demo-prepare": demo_prepare,
}


def main() -> None:
    parser = argparse.ArgumentParser(description="Prism local development tool")
    parser.add_argument("command", choices=COMMANDS)
    COMMANDS[parser.parse_args().command]()


if __name__ == "__main__":
    main()
