"""Prepares the local system for a demonstration, through the public API, as the synthetic staff would.

    python -m uv run --project services/ai python scripts/prism.py demo-prepare

Needs the full stack running against the real model (run-temporal, run-ai, run-core, run-web) and a
seeded database. It is safe to run again: it skips what is already done.

1. Policies: attaches a wording to every version that has none, registers the Platinum policy, confirms
   every critical value as one policy manager, and publishes as the other (a publisher may not be the
   uploader).
2. Claims: creates the claims in test-data/claims/demo-corpus/ in two waves, so that claims the second
   wave is compared with are processed first, and waits for processing.
3. Review: works each claim as its plan says, as the role the claim's queue needs: decisions, a
   revision, an escalation, investigation findings, information requests, a task left in progress.
4. Report: prints, for each claim, the rules it raised beside the rules it was built to raise, so a
   claim that did not come out as intended is visible before the demonstration. Also written to
   .data/demo-prepare.json.

Everything is synthetic. Model calls cost a little money: roughly USD 0.50 to 1.00 for the whole set.
"""

from __future__ import annotations

import json
import re
import sys
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

import httpx

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "test-data" / "claims" / "demo-corpus"
POLICY_FILES = ROOT / "test-data" / "policies"
RESULTS = ROOT / ".data" / "demo-prepare.json"
SETTLED = {"IN_REVIEW", "ESCALATED", "AWAITING_INFORMATION", "DECIDED"}
PROCESSING_TIMEOUT_SECONDS = 25 * 60
POLICY_TIMEOUT_SECONDS = 15 * 60

# Wordings for versions registered without one. Each must state the registered sum insured.
WORDINGS = {
    "SYN-IND-2026-0002": "individual-silver.pdf",
    "SYN-RSK-2026-0003": "family-health-plus.pdf",
}
PLATINUM = {
    "policyNumber": "SYN-FHP-2026-0003",
    "productName": "Family Health Platinum (Synthetic)",
    "policyholderName": "Synthetic Policyholder Three",
    "version": {"effectiveFrom": "2026-04-01", "effectiveTo": "2027-03-31", "sumInsured": "750000.00"},
    "wording": "family-health-platinum.pdf",
}


def say(message: str) -> None:
    print(message, flush=True)


class Session:
    """One signed-in member of staff. Core expects the CSRF token on every write."""

    def __init__(self, base_url: str, email: str, password: str) -> None:
        self.email = email
        self.http = httpx.Client(base_url=base_url, timeout=60)
        self.http.get("/api/v1/auth/csrf")
        response = self.post("/api/v1/auth/sign-in", json={"email": email, "password": password})
        if response.status_code != 200:
            sys.exit(f"Could not sign in as {email} ({response.status_code}). Has the database been seeded?")
        self.http.get("/api/v1/auth/csrf")

    def post(self, path: str, **options: Any) -> httpx.Response:
        headers = {"X-XSRF-TOKEN": self.http.cookies.get("XSRF-TOKEN", ""), **options.pop("headers", {})}
        return self.http.post(path, headers=headers, **options)

    def get(self, path: str, **options: Any) -> Any:
        response = self.http.get(path, **options)
        response.raise_for_status()
        return response.json()

    def write(self, path: str, **options: Any) -> Any:
        response = self.post(path, **options)
        if response.status_code >= 400:
            raise RuntimeError(f"{self.email} POST {path}: {response.status_code} {response.text[:300]}")
        return response.json() if response.content else None


def sessions(base_url: str) -> dict[str, Session]:
    sheet = (ROOT / ".data" / "synthetic-users.md").read_text(encoding="utf-8")
    found = dict(re.findall(r"\| \w+ \| (\S+@synthetic\.prism\.test) \| `([^`]+)`", sheet))
    needed = ["policy.manager", "policy.approver", "claims.processor", "reviewer", "senior.reviewer", "investigator"]
    result = {}
    for name in needed:
        email = f"{name}@synthetic.prism.test"
        if email not in found:
            sys.exit(f"{email} is not in .data/synthetic-users.md. Run db-reset first.")
        result[name] = Session(base_url, email, found[email])
    return result


def wait(what: str, done: Callable[[], bool], timeout: float, interval: float = 5) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if done():
            return
        time.sleep(interval)
    sys.exit(f"Timed out waiting for {what}. Check that run-temporal, run-ai, and run-core are running.")


# --- Policies ------------------------------------------------------------------------------------------------


def all_versions(manager: Session) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    pairs = []
    for policy in manager.get("/api/v1/policies", params={"limit": 100})["items"]:
        detail = manager.get(f"/api/v1/policies/{policy['id']}")
        pairs += [(detail["policy"], version["version"]) for version in detail["versions"]]
    return pairs


def upload_wording(manager: Session, policy_id: str, version_id: str, name: str) -> None:
    path = POLICY_FILES / name
    manager.write(f"/api/v1/policies/{policy_id}/versions/{version_id}/wording",
                  files={"file": (name, path.read_bytes(), "application/pdf")})


def prepare_policies(people: dict[str, Session]) -> None:
    manager, approver = people["policy.manager"], people["policy.approver"]
    numbers = {policy["policyNumber"] for policy, _ in all_versions(manager)}
    if PLATINUM["policyNumber"] not in numbers:
        say(f"Registering {PLATINUM['policyNumber']}")
        created = manager.write("/api/v1/policies", json={key: PLATINUM[key] for key in
                                                           ("policyNumber", "productName", "policyholderName")})
        version = manager.write(f"/api/v1/policies/{created['id']}/versions", json=PLATINUM["version"])
        upload_wording(manager, created["id"], version["id"], str(PLATINUM["wording"]))

    for policy, version in all_versions(manager):
        if version["knowledgeStatus"] == "NO_WORDING" and policy["policyNumber"] in WORDINGS:
            say(f"Attaching a wording to {policy['policyNumber']} version {version['versionNumber']}")
            upload_wording(manager, policy["id"], version["id"], WORDINGS[policy["policyNumber"]])

    def settled() -> bool:
        return all(version["knowledgeStatus"] != "PROCESSING" for _, version in all_versions(manager))

    say("Waiting for policy wordings to be read")
    wait("policy wordings", settled, POLICY_TIMEOUT_SECONDS, interval=10)

    for policy, version in all_versions(manager):
        label = f"{policy['policyNumber']} v{version['versionNumber']}"
        base = f"/api/v1/policies/{policy['id']}/versions/{version['id']}/knowledge"
        if version["knowledgeStatus"] != "IN_REVIEW":
            say(f"  {label}: {version['knowledgeStatus']}")
            continue
        view = manager.get(base)
        confirmed = corrected = 0
        for provision in view["provisions"]:
            for value in provision["values"]:
                if not value["critical"] or value["review"] is not None:
                    continue
                if value["state"] == "PRESENT" or value["value"] is not None:
                    body = {"decision": "CONFIRMED", "note": "Checked against the wording."}
                    confirmed += 1
                else:
                    reading = corrected_reading(value)
                    if reading is None:
                        continue
                    body = {"decision": "CORRECTED", "correctedValue": reading,
                            "note": f"The wording states \"{value['rawValue']}\"."}
                    corrected += 1
                manager.write(f"{base}/values/{value['id']}/review", json=body)
        blockers = approver.get(base)["blockers"]
        if blockers:
            say(f"  {label}: not published. Blockers: {', '.join(blocker['code'] for blocker in blockers)}")
            continue
        approver.write(f"{base}/publication")
        say(f"  {label}: published ({confirmed} critical values confirmed, {corrected} corrected)")


NUMBER_WORDS = {"one": 1, "two": 2, "three": 3, "four": 4, "six": 6, "twelve": 12}
MONTHS = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
          "november", "december"]


def corrected_reading(value: dict[str, Any]) -> str | None:
    """What a policy manager enters for a value the reader could not put in a number, read from its own
    text. None when the text does not settle it: that value stays a blocker for a person."""
    raw = str(value["rawValue"] or "").strip().lower()
    if value["kind"] == "AMOUNT" and raw in ("nil", "none", "not applicable"):
        return "0"
    if value["kind"] != "DURATION":
        return None
    period = re.fullmatch(r"\d{1,2} (\w+) (\d{4}) to \d{1,2} (\w+) (\d{4})", raw)
    if period and period[1] in MONTHS and period[3] in MONTHS:
        start = int(period[2]) * 12 + MONTHS.index(period[1])
        return str(int(period[4]) * 12 + MONTHS.index(period[3]) - start + 1)
    digits = re.search(r"\d{1,4}", raw)
    if digits:
        return digits[0]
    words = [NUMBER_WORDS[word] for word in raw.split() if word in NUMBER_WORDS]
    return str(words[0]) if words else None


# --- Claims --------------------------------------------------------------------------------------------------


@dataclass
class Loaded:
    entry: dict[str, Any]
    claim_id: str
    number: str
    created: bool
    actions: list[str] = field(default_factory=list)


def existing_claim(processor: Session, entry: dict[str, Any]) -> dict[str, Any] | None:
    found = processor.get("/api/v1/claims", params={"search": entry["patient"], "limit": 100})["items"]
    for claim in found:
        if (claim["admissionDate"] == entry["admissionDate"] and claim["status"] not in ("WITHDRAWN", "DRAFT")
                and claim["hospitalName"] == entry["hospital"]):
            return dict(claim)
    return None


def create_claim(processor: Session, entry: dict[str, Any], policies: dict[str, str]) -> Loaded:
    found = existing_claim(processor, entry)
    if found and Decimal(str(found["claimedAmount"])) == Decimal(entry["claimedAmount"]):
        return Loaded(entry, found["id"], found["claimNumber"], created=False)
    if found:
        # Loaded from an earlier version of the data set. Withdrawn claims are never compared against.
        processor.write(f"/api/v1/claims/{found['id']}/withdrawal", json={"reason": "ENTERED_IN_ERROR"})
        say(f"  {found['claimNumber']} withdrawn: its amount was changed in the data set")
    body = {
        "type": "REIMBURSEMENT",
        "policyId": policies[entry["policyNumber"]],
        "claimantName": entry["claimant"],
        "patientName": entry["patient"],
        "hospitalName": entry["hospital"],
        "admissionDate": entry["admissionDate"],
        "dischargeDate": entry["dischargeDate"],
        "claimedAmount": entry["claimedAmount"],
    }
    claim = processor.write("/api/v1/claims", json=body, headers={"Idempotency-Key": str(uuid.uuid4())})
    claim_id = claim["summary"]["id"]
    for document in entry["documents"]:
        path = CORPUS / document["file"]
        processor.write(f"/api/v1/claims/{claim_id}/documents", params={"documentType": document["type"]},
                        files={"file": (path.name, path.read_bytes(), "application/pdf")})
    processor.write(f"/api/v1/claims/{claim_id}/submission")
    return Loaded(entry, claim_id, claim["summary"]["claimNumber"], created=True)


def status(session: Session, claim_id: str) -> str:
    return str(session.get(f"/api/v1/claims/{claim_id}")["summary"]["status"])


def load_wave(processor: Session, entries: list[dict[str, Any]], policies: dict[str, str]) -> list[Loaded]:
    loaded = [create_claim(processor, entry, policies) for entry in entries]
    for item in loaded:
        say(f"  {item.number}  {item.entry['title']}{'' if item.created else ' (already loaded)'}")
    started = time.monotonic()

    retried: dict[str, list[float]] = {}

    def retry_failed(item: Loaded) -> None:
        """A stage that failed (a model outage, say) waits for someone to press Retry. This presses it, and
        presses again only if nothing has happened for two minutes since: the flag stays set while the
        retried cycle runs."""
        processing = processor.get(f"/api/v1/claims/{item.claim_id}/processing")
        if not processing.get("retryAvailable"):
            return
        attempts = retried.setdefault(item.claim_id, [])
        if attempts:
            latest = max(datetime.fromisoformat(stage["occurredAt"].replace("Z", "+00:00")).timestamp()
                         for stage in processing["stages"])
            if latest > attempts[-1] or time.time() - attempts[-1] < 120:
                return
            if len(attempts) >= 3:
                sys.exit(f"{item.number} keeps failing. Open it in Prism to see why (is the model account in credit?).")
        attempts.append(time.time())
        if processor.post(f"/api/v1/claims/{item.claim_id}/processing/retry").status_code < 400:
            say(f"  {item.number}: a stage had failed, retried")

    def finished() -> bool:
        waiting = [item for item in loaded if status(processor, item.claim_id) not in SETTLED]
        for item in waiting:
            retry_failed(item)
        if waiting:
            say(f"  ... {len(waiting)} still processing ({int(time.monotonic() - started)}s)")
        return not waiting

    wait("claim processing", finished, PROCESSING_TIMEOUT_SECONDS, interval=15)
    return loaded


# --- Review --------------------------------------------------------------------------------------------------

QUEUE_ACTORS = {"REVIEW": "reviewer", "SENIOR_REVIEW": "senior.reviewer", "INVESTIGATION": "investigator"}


def workspace(session: Session, claim_id: str) -> dict[str, Any]:
    return dict(session.get(f"/api/v1/claims/{claim_id}/review"))


def open_task(people: dict[str, Session], item: Loaded) -> tuple[Session, dict[str, Any]] | None:
    """The claim's live task, taken by the role its queue needs. None when there is no task."""
    for _ in range(12):
        task = workspace(people["reviewer"], item.claim_id)["task"]
        if task:
            break
        time.sleep(2.5)
    else:
        return None
    actor = people[QUEUE_ACTORS[task["queue"]]]
    if task["status"] != "CLAIMED":
        actor.write(f"/api/v1/review/tasks/{task['id']}/claim")
    return actor, task


def amount_for(spec: str, claimed: Decimal) -> Decimal:
    if spec.startswith("="):
        return Decimal(spec[1:])
    return claimed + Decimal(spec)


def run_plan(people: dict[str, Session], item: Loaded) -> None:
    review = workspace(people["reviewer"], item.claim_id)
    if review["outcomes"] or review["findings"] or review["requests"] or (review["task"] or {}).get("status") == "CLAIMED":
        item.actions.append("already worked")
        return
    claimed = Decimal(item.entry["claimedAmount"])
    for step in item.entry["plan"]:
        kind, _, rest = step.partition(":")
        if kind == "wait":
            item.actions.append("left waiting")
            return
        if kind == "revise":
            decision, delta, agreement, rationale = rest.split(":", 3)
            people["senior.reviewer"].write(f"/api/v1/claims/{item.claim_id}/review/outcome/revision", json={
                "decision": decision, "approvedAmount": str(amount_for(delta, claimed)),
                "rationale": rationale, "aiAgreement": agreement})
            item.actions.append(f"revised to {decision.lower()}")
            continue
        opened = open_task(people, item)
        if opened is None:
            item.actions.append("no task open")
            return
        actor, task = opened
        queue = task["queue"]
        path = f"/api/v1/claims/{item.claim_id}/review"
        if kind == "finding":
            if queue != "INVESTIGATION":
                continue
            finding, rationale = rest.split(":", 1)
            actor.write(f"{path}/investigation-findings", json={"taskId": task["id"], "finding": finding, "rationale": rationale})
            item.actions.append(f"investigated: {finding.lower()}")
            continue
        if queue == "INVESTIGATION":
            item.actions.append("taken by the investigator")
            return
        if kind == "take":
            item.actions.append(f"taken by {actor.email.split('@')[0]}")
            return
        if kind == "request":
            document_type, message = rest.split(":", 1)
            actor.write(f"{path}/information-requests", json={"taskId": task["id"], "documentTypes": [document_type],
                                                                 "message": message})
            item.actions.append("information requested")
            return
        if kind == "escalate":
            target, note = rest.split(":", 1)
            if queue == target:
                item.actions.append(f"already in {queue.lower()}")
                return
            actor.write(f"{path}/escalation", json={"taskId": task["id"], "target": target, "note": note})
            item.actions.append(f"escalated to {target.lower()}")
            return
        if kind == "decide":
            decision, _, remainder = rest.partition(":")
            if decision == "PARTIALLY_APPROVED":
                delta, agreement, rationale = remainder.split(":", 2)
                approved: Decimal | None = amount_for(delta, claimed)
            else:
                agreement, rationale = remainder.split(":", 1)
                approved = claimed if decision == "APPROVED" else None
            actor.write(f"{path}/outcome", json={
                "taskId": task["id"], "decision": decision,
                "approvedAmount": None if approved is None else str(approved),
                "rationale": rationale, "aiAgreement": agreement})
            item.actions.append(f"{decision.lower().replace('_', ' ')} by {actor.email.split('@')[0]}")
            continue
        raise ValueError(f"Unknown plan step {step}")


# --- Report --------------------------------------------------------------------------------------------------


def screening(session: Session, claim_id: str) -> dict[str, Any] | None:
    response = session.http.get(f"/api/v1/claims/{claim_id}/risk")
    if response.status_code != 200:
        return None
    screening: dict[str, Any] | None = response.json().get("screening")
    return screening


def processing_seconds(session: Session, claim_id: str, history: list[dict[str, Any]]) -> float | None:
    """From submission to the first review task: extraction, consolidation, policy analysis, and screening."""
    submitted = session.get(f"/api/v1/claims/{claim_id}").get("submittedAt")
    if not submitted or not history:
        return None
    def parse(value: str) -> datetime:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))

    first = min(parse(task["createdAt"]) for task in history)
    return (first - parse(submitted)).total_seconds()


def report(people: dict[str, Session], loaded: list[Loaded]) -> dict[str, Any]:
    reader = people["reviewer"]
    rows = []
    for item in loaded:
        risk = screening(reader, item.claim_id)
        raised = sorted({indicator["ruleId"] for indicator in risk["indicators"]}) if risk else []
        flagged = [detector["detector"] for detector in risk["detectors"] if detector["flagged"]] if risk else []
        expected = sorted(item.entry["expectedRules"])
        history = workspace(reader, item.claim_id)["history"]
        first = min(history, key=lambda task: task["createdAt"]) if history else None
        rows.append({
            "key": item.entry["key"],
            "claimNumber": item.number,
            "title": item.entry["title"],
            "status": status(reader, item.claim_id),
            "band": risk["band"] if risk else None,
            "score": risk["score"] if risk else None,
            "rulesOnlyBand": risk["rulesOnlyBand"] if risk else None,
            "expectedRules": expected,
            "raisedRules": raised,
            "missed": sorted(set(expected) - set(raised)),
            "unexpected": sorted(set(raised) - set(expected)),
            "detectorsFlagged": flagged,
            "summary": (risk.get("summary") or {}).get("status") if risk else None,
            "claimedAmount": item.entry["claimedAmount"],
            "firstQueue": first["queue"] if first else None,
            "firstReason": first["reason"] if first else None,
            "processingSeconds": processing_seconds(reader, item.claim_id, history),
            "actions": item.actions,
        })
    return {"ranAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "claims": rows}


def print_report(result: dict[str, Any]) -> None:
    say("")
    say(f"{'Claim':<14}{'Band':<8}{'Score':>6}  {'Status':<21}{'Raised':<34}Notes")
    for row in result["claims"]:
        notes = []
        if row["missed"]:
            notes.append("missed " + ",".join(row["missed"]))
        if row["unexpected"]:
            notes.append("also " + ",".join(row["unexpected"]))
        notes += row["actions"]
        say(f"{row['claimNumber']:<14}{row['band'] or '-':<8}{row['score'] if row['score'] is not None else '-':>6}  "
            f"{row['status']:<21}{','.join(row['raisedRules']) or 'none':<34}{'; '.join(notes)}")


def report_only(base_url: str) -> None:
    """Rewrites the evaluation from claims already loaded, without changing anything."""
    manifest = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
    people = sessions(base_url)
    loaded = []
    for entry in manifest["claims"]:
        found = existing_claim(people["claims.processor"], entry)
        if found:
            loaded.append(Loaded(entry, found["id"], found["claimNumber"], created=False, actions=["loaded earlier"]))
    result = report(people, loaded)
    RESULTS.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print_report(result)


def main(base_url: str) -> None:
    manifest = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
    people = sessions(base_url)
    say("1. Policies")
    prepare_policies(people)

    processor = people["claims.processor"]
    policies = {policy["policyNumber"]: policy["id"] for policy in processor.get("/api/v1/policies", params={"limit": 100})["items"]}
    loaded: list[Loaded] = []
    for wave in (1, 2):
        say(f"{wave + 1}. Claims, wave {wave}")
        loaded += load_wave(processor, [entry for entry in manifest["claims"] if entry["wave"] == wave], policies)

    say("4. Review")
    for item in loaded:
        run_plan(people, item)

    result = report(people, loaded)
    RESULTS.parent.mkdir(parents=True, exist_ok=True)
    RESULTS.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print_report(result)
    say(f"\nWritten to {RESULTS.relative_to(ROOT)}. The demonstrations in demo/ are ready to upload.")
