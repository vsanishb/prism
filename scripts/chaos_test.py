#!/usr/bin/env python3
"""Proves that restarting services mid-process loses nothing. Run against a running e2e-stack:

    python -m uv run --project services/ai python scripts/chaos_test.py

Kills real processes while claims are being processed, restarts them, and checks that each claim
still finishes with exactly one extraction per document. Start the model stub with
PRISM_STUB_DELAY_SECONDS=6 first, so that kills land while a model call is in flight.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import uuid
from collections.abc import Callable
from pathlib import Path

import httpx
import psutil

sys.path.insert(0, str(Path(__file__).parent))
import prism

CORE = f"http://127.0.0.1:{prism.E2E_CORE_PORT}"
TEST_DATA = prism.ROOT / "test-data/claims/appendectomy"
DOCUMENTS = (
    ("CLAIM_FORM", "claim-form.pdf"),
    ("HOSPITAL_BILL", "hospital-bill.pdf"),
    ("DISCHARGE_SUMMARY", "discharge-summary.pdf"),
)
FINISH_TIMEOUT_SECONDS = 150


def kill_port(port: int) -> None:
    for connection in psutil.net_connections("tcp"):
        if connection.laddr and connection.laddr.port == port and connection.status == "LISTEN" and connection.pid:
            psutil.Process(connection.pid).kill()


def service_env() -> dict[str, str]:
    env = prism.read_env()
    return {**env, **os.environ, **prism.e2e_environment(env)}


def spawn(command: list[str] | str, cwd: Path | None = None, shell: bool = False) -> None:
    with (prism.E2E_DATA / "services.log").open("ab") as log:
        subprocess.Popen(command, cwd=cwd, env=service_env(), stdout=log, stderr=log, stdin=subprocess.DEVNULL, shell=shell)


def start_ai() -> None:
    spawn([sys.executable, "-m", "prism_ai.serve"], prism.ROOT / "services/ai")
    prism.wait_for_port(prism.E2E_AI_PORT, "AI service")


def start_core() -> None:
    executable = "mvnw.cmd" if os.name == "nt" else "./mvnw"
    spawn([str(prism.ROOT / "services/core" / executable), "-q", "-B", "-ntp", "spring-boot:run"],
          prism.ROOT / "services/core", shell=os.name == "nt")
    prism.wait_for_port(prism.E2E_CORE_PORT, "Core service")


def start_temporal() -> None:
    spawn([str(prism.temporal_binary()), "server", "start-dev", "--ip", "127.0.0.1",
           "--port", str(prism.E2E_TEMPORAL_PORT), "--ui-port", str(prism.E2E_TEMPORAL_PORT + 1000),
           "--headless", "--db-filename", str(prism.E2E_DATA / "temporal.db")])
    prism.wait_for_port(prism.E2E_TEMPORAL_PORT, "Temporal")


class Session:
    def __init__(self) -> None:
        sheet = (prism.E2E_DATA / "synthetic-users.md").read_text(encoding="utf-8")
        match = re.search(r"\| CLAIMS_PROCESSOR \| (\S+) \| `([^`]+)`", sheet)
        assert match
        self.email, self.password = match[1], match[2]
        self.http = httpx.Client(base_url=CORE, timeout=30)
        self.sign_in()

    def sign_in(self) -> None:
        self.http.cookies.clear()
        self.http.get("/api/v1/auth/csrf")
        response = self.post("/api/v1/auth/sign-in", json={"email": self.email, "password": self.password})
        response.raise_for_status()
        self.http.get("/api/v1/auth/csrf")

    def post(self, path: str, **options: object) -> httpx.Response:
        headers = {"X-XSRF-TOKEN": self.http.cookies.get("XSRF-TOKEN", ""), **options.pop("headers", {})}
        return self.http.post(path, headers=headers, **options)

    def get(self, path: str) -> httpx.Response:
        return self.http.get(path)

    def create_claim(self, documents: tuple[tuple[str, str], ...] = DOCUMENTS) -> str:
        policy = self.get("/api/v1/policies").json()["items"][0]["id"]
        body = {
            "type": "REIMBURSEMENT", "policyId": policy, "claimantName": "Synthetic Policyholder One",
            "patientName": "Synthetic Patient Asha", "hospitalName": "Synthetic Care Hospital",
            "admissionDate": "2026-03-14", "dischargeDate": "2026-03-17", "claimedAmount": "106000.00",
        }
        claim = self.post("/api/v1/claims", json=body, headers={"Idempotency-Key": str(uuid.uuid4())})
        claim.raise_for_status()
        claim_id = claim.json()["summary"]["id"]
        for document_type, name in documents:
            self.upload(claim_id, document_type, name)
        return claim_id

    def upload(self, claim_id: str, document_type: str, name: str) -> None:
        self.post(
            f"/api/v1/claims/{claim_id}/documents?documentType={document_type}",
            files={"file": (name, (TEST_DATA / name).read_bytes())},
        ).raise_for_status()

    def submit(self, claim_id: str) -> None:
        self.post(f"/api/v1/claims/{claim_id}/submission").raise_for_status()

    def status(self, claim_id: str) -> str:
        return self.get(f"/api/v1/claims/{claim_id}").json()["summary"]["status"]

    def wait_for_processing(self, claim_id: str) -> None:
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            stages = self.get(f"/api/v1/claims/{claim_id}/processing").json()["stages"]
            if any(stage["status"] == "PROCESSING" for stage in stages):
                return
            time.sleep(0.2)
        sys.exit("Processing never started.")

    def wait_for(self, claim_id: str, expected: str) -> float:
        started = time.monotonic()
        while time.monotonic() - started < FINISH_TIMEOUT_SECONDS:
            try:
                if self.status(claim_id) == expected:
                    return time.monotonic() - started
            except (httpx.HTTPError, ValueError, KeyError):
                self.reconnect()
            time.sleep(1)
        raise TimeoutError(f"Claim stayed {self.status(claim_id)}, expected {expected}")

    def reconnect(self) -> None:
        try:
            self.sign_in()
        except httpx.HTTPError:
            time.sleep(1)


def database(sql: str, claim_id: str) -> str:
    env = prism.read_env()
    query = sql.replace(":claim", f"'{claim_id}'")
    result = subprocess.run(
        [prism.pg_tool("psql"), "-At", "-h", "127.0.0.1", "-p", str(prism.PG_PORT), "-U", prism.OWNER,
         "-d", prism.E2E_DATABASE, "-c", query],
        env={**os.environ, "PGPASSWORD": env["PRISM_MIGRATION_DB_PASSWORD"]},
        capture_output=True, text=True, check=True,
    )
    return result.stdout.strip()


def check_nothing_lost(claim_id: str, documents: int = 3) -> None:
    extractions = int(database("SELECT count(*) FROM core.document_extractions WHERE claim_id = :claim", claim_id))
    distinct = int(database("SELECT count(DISTINCT document_id) FROM core.document_extractions WHERE claim_id = :claim", claim_id))
    unpublished = int(database("SELECT count(*) FROM core.outbox_events WHERE aggregate_id = :claim AND published_at IS NULL", claim_id))
    facts = int(database(
        "SELECT count(*) FROM core.claim_facts f JOIN core.claim_consolidations c ON c.id = f.consolidation_id"
        " WHERE c.claim_id = :claim", claim_id))
    assert extractions == documents == distinct, f"extractions {extractions}, distinct {distinct}, wanted {documents}"
    assert unpublished == 0, f"{unpublished} outbox events undelivered"
    assert facts > 0, "no facts stored"


def core_killed_mid_activity(session: Session) -> None:
    claim = session.create_claim()
    session.submit(claim)
    session.wait_for_processing(claim)
    kill_port(prism.E2E_CORE_PORT)
    time.sleep(3)
    start_core()
    session.reconnect()
    print(f"  finished {session.wait_for(claim, 'IN_REVIEW'):.0f}s after restart")
    check_nothing_lost(claim)


def ai_killed_mid_activity(session: Session) -> None:
    claim = session.create_claim()
    session.submit(claim)
    session.wait_for_processing(claim)
    kill_port(prism.E2E_AI_PORT)
    time.sleep(8)
    start_ai()
    print(f"  finished {session.wait_for(claim, 'IN_REVIEW'):.0f}s after restart")
    check_nothing_lost(claim)


def temporal_down_at_submission(session: Session) -> None:
    claim = session.create_claim()
    kill_port(prism.E2E_TEMPORAL_PORT)
    time.sleep(2)
    session.submit(claim)
    time.sleep(4)
    assert session.status(claim) == "SUBMITTED", "claim should wait safely"
    stages = session.get(f"/api/v1/claims/{claim}/processing")
    assert stages.status_code == 200, "status screen must work during an outage"
    pending = int(database("SELECT count(*) FROM core.outbox_events WHERE aggregate_id = :claim AND published_at IS NULL", claim))
    assert pending == 1, "the event must wait in the outbox"
    start_temporal()
    print(f"  finished {session.wait_for(claim, 'IN_REVIEW'):.0f}s after restart")
    check_nothing_lost(claim)


def temporal_killed_mid_workflow(session: Session) -> None:
    claim = session.create_claim()
    session.submit(claim)
    session.wait_for_processing(claim)
    kill_port(prism.E2E_TEMPORAL_PORT)
    time.sleep(3)
    start_temporal()
    print(f"  finished {session.wait_for(claim, 'IN_REVIEW'):.0f}s after restart")
    check_nothing_lost(claim)


def everything_restarted_during_a_wait(session: Session) -> None:
    claim = session.create_claim(DOCUMENTS[:1])
    session.submit(claim)
    session.wait_for(claim, "AWAITING_INFORMATION")
    for port in (prism.E2E_CORE_PORT, prism.E2E_AI_PORT, prism.E2E_TEMPORAL_PORT):
        kill_port(port)
    time.sleep(2)
    start_temporal()
    start_ai()
    start_core()
    session.reconnect()
    assert session.status(claim) == "AWAITING_INFORMATION"
    session.upload(claim, *DOCUMENTS[1])
    session.upload(claim, *DOCUMENTS[2])
    print(f"  finished {session.wait_for(claim, 'IN_REVIEW'):.0f}s after new documents")
    check_nothing_lost(claim)


SCENARIOS: tuple[Callable[[Session], None], ...] = (
    core_killed_mid_activity,
    ai_killed_mid_activity,
    temporal_down_at_submission,
    temporal_killed_mid_workflow,
    everything_restarted_during_a_wait,
)


def main() -> None:
    session = Session()
    failures = 0
    for scenario in SCENARIOS:
        print(scenario.__name__.replace("_", " "), flush=True)
        try:
            scenario(session)
            print("  PASSED", flush=True)
        except (AssertionError, TimeoutError) as failure:
            failures += 1
            print(f"  FAILED: {failure}", flush=True)
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
