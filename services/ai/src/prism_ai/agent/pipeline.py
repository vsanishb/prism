"""The Policy Agent ([flow](docs/system-flows.md#policy-agent), D-079).

A model plans the issues a claim raises. Code adds any that every claim needs. Each issue then runs
a bounded loop: search the version's published wording, and ask a model for a finding or for more
searches. Code validates every finding. Nothing here decides a claim, and no model computes money,
dates, or durations: it names the confirmed values that apply, and core computes.
"""

import asyncio
import calendar
from dataclasses import dataclass, field
from datetime import date
from functools import partial
from uuid import UUID

import structlog

from prism_ai.agent.schemas import (
    ANALYSIS_SCHEMA,
    PIPELINE_VERSION,
    PLAN_SCHEMA,
    AnalysisOutput,
    AnalysisRequest,
    AnalysisResponse,
    ClaimFact,
    Finding,
    FindingStatus,
    IssueKind,
    PlannedIssue,
    PlanOutput,
    PolicyValue,
    Problem,
)
from prism_ai.agent.validate import Evidence, validated
from prism_ai.gateway.client import Message, ModelGateway, StructuredRequest
from prism_ai.gateway.errors import ProcessingError
from prism_ai.gateway.routes import Task
from prism_ai.gateway.usage import ModelRun
from prism_ai.prompts import Prompt, load_prompt
from prism_ai.retrieval.pipeline import RetrievalPipeline, once_more
from prism_ai.retrieval.schemas import MAX_QUERY_CHARS, SearchRequest

logger = structlog.get_logger()

MAX_ISSUES = 8
MAX_ROUNDS = 3
MAX_QUERIES = 3
RESULTS_PER_QUERY = 5
# Plan plus three rounds for eight issues, with room to spare. Reaching it ends the remaining
# issues as insufficient evidence, rather than running up cost.
MAX_MODEL_CALLS = 30
PARALLEL_ISSUES = 3
REQUIRED_KINDS = (IssueKind.COVERAGE, IssueKind.WAITING_PERIOD, IssueKind.EXCLUSION, IssueKind.COST_SHARING)


def data(text: str) -> str:
    """Document text cannot close or open a block: angle brackets inside it are neutralized."""
    return text.replace("<", "\u2039").replace(">", "\u203a")


def add_months(start: date, months: int) -> date:
    """As a calendar does it: 31 January plus one month is the last day of February."""
    year, month = divmod(start.month - 1 + months, 12)
    year, month = start.year + year, month + 1
    return date(year, month, min(start.day, calendar.monthrange(year, month)[1]))


def cover_duration(start: date, admission_text: str) -> str:
    """Whole months and remaining days from cover start to admission, as stated to the model."""
    try:
        admission = date.fromisoformat(admission_text)
    except ValueError:
        return "unknown"
    if admission < start:
        return "admission is before cover began"
    months = (admission.year - start.year) * 12 + admission.month - start.month
    if admission.day < start.day:
        months -= 1
    anchor = add_months(start, months)
    if anchor > admission:
        months -= 1
        anchor = add_months(start, months)
    days = (admission - anchor).days
    total = (admission - start).days
    return f"{months} months and {days} days ({total} days in all)"


def usable_queries(queries: list[str], searched: list[str]) -> list[str]:
    """Searches worth running: not blank, not a single character, and not already run."""
    usable: list[str] = []
    for query in queries:
        cleaned = " ".join(query.split())[:MAX_QUERY_CHARS]
        if len(cleaned) >= 2 and cleaned not in searched and cleaned not in usable:
            usable.append(cleaned)
    return usable[:MAX_QUERIES]


def treatment(facts: dict[str, ClaimFact]) -> str | None:
    for name in ("procedure", "diagnosis"):
        fact = facts.get(name)
        if fact and fact.state == "PRESENT" and fact.value:
            return fact.value[:120]
    return None


def with_required_issues(planned: list[PlannedIssue], facts: dict[str, ClaimFact]) -> list[PlannedIssue]:
    """Every claim is checked for cover, waiting periods, exclusions, and cost sharing, whatever the plan."""
    issues = [issue for issue in planned if issue.question.strip()][:MAX_ISSUES]
    present = {issue.kind for issue in issues}
    subject = treatment(facts)
    defaults = {
        IssueKind.COVERAGE: (
            "Is the treatment covered by a benefit in the wording?",
            [f"cover for {subject}" if subject else "in-patient hospitalization cover"],
        ),
        IssueKind.WAITING_PERIOD: (
            "Does a waiting period apply to this treatment?",
            [f"waiting period {subject}" if subject else "waiting period"],
        ),
        IssueKind.EXCLUSION: (
            "Does an exclusion apply to this treatment?",
            [f"exclusion {subject}" if subject else "exclusions"],
        ),
        IssueKind.COST_SHARING: ("Does a co-payment or deductible apply?", ["co-payment", "deductible"]),
    }
    for kind in REQUIRED_KINDS:
        if kind not in present:
            question, queries = defaults[kind]
            issues.append(PlannedIssue(kind=kind, question=question, queries=queries))
    return issues[: MAX_ISSUES + len(REQUIRED_KINDS)]


@dataclass
class RunState:
    """Shared by the issues of one analysis, which run concurrently on one event loop."""

    runs: list[ModelRun] = field(default_factory=list)
    calls: int = 0
    degraded_searches: int = 0
    evidence: dict[UUID, Evidence] = field(default_factory=dict)

    evidence_count: int = 0

    def next_evidence_id(self) -> str:
        """IDs come from a counter, never from the size of a map other issues are also filling."""
        self.evidence_count += 1
        return f"E{self.evidence_count}"

    def take_call(self) -> bool:
        if self.calls >= MAX_MODEL_CALLS:
            return False
        self.calls += 1
        return True


class PolicyAgent:
    def __init__(self, gateway: ModelGateway, retrieval: RetrievalPipeline) -> None:
        self._gateway = gateway
        self._retrieval = retrieval
        self._plan_prompt = load_prompt("agent_plan", 1)
        self._analysis_prompt = load_prompt("agent_analysis", 2)

    async def run(self, request: AnalysisRequest) -> AnalysisResponse:
        state = RunState()
        try:
            return await self._run(request, state)
        except ProcessingError as error:
            error.runs = state.runs
            raise

    async def _run(self, request: AnalysisRequest, state: RunState) -> AnalysisResponse:
        facts = {fact.name: fact for fact in request.facts}
        facts_block = self._facts_block(request, facts)
        state.calls += 1
        planning = StructuredRequest(
            task=Task.AGENT_PLAN,
            prompt_version=self._plan_prompt.version,
            schema_name="claim_issue_plan",
            schema=PLAN_SCHEMA,
            messages=self._messages(self._plan_prompt, facts_block),
            parse=PlanOutput.model_validate,
        )
        plan = await once_more(partial(self._gateway.complete, planning, state.runs))
        issues = with_required_issues(plan.issues, facts)
        values = {f"V{index}": value for index, value in enumerate(request.policy_values, start=1)}
        limit = asyncio.Semaphore(PARALLEL_ISSUES)

        async def examine(position: int, issue: PlannedIssue) -> Finding:
            async with limit:
                return await self._issue(request, position, issue, facts, facts_block, values, state)

        findings = await asyncio.gather(
            *(examine(position, issue) for position, issue in enumerate(issues, start=1))
        )
        logger.info("claim_analyzed", claim_id=str(request.claim_id), issues=len(findings), calls=state.calls)
        return AnalysisResponse(
            claim_id=request.claim_id,
            version_id=request.version_id,
            pipeline_version=PIPELINE_VERSION,
            plan_prompt_version=self._plan_prompt.version,
            analysis_prompt_version=self._analysis_prompt.version,
            findings=list(findings),
            degraded_searches=state.degraded_searches,
            runs=state.runs,
        )

    async def _issue(
        self,
        request: AnalysisRequest,
        position: int,
        issue: PlannedIssue,
        facts: dict[str, ClaimFact],
        facts_block: str,
        values: dict[str, PolicyValue],
        state: RunState,
    ) -> Finding:
        queries = usable_queries(issue.queries, [])
        searched: list[str] = []
        local: dict[str, Evidence] = {}
        for round_number in range(1, MAX_ROUNDS + 1):
            for query in queries:
                if query not in searched:
                    searched.append(query)
                    for item in await self._search(request.version_id, query, state):
                        local[item.evidence_id] = item
            if not state.take_call():
                return self._unanswered(position, issue, Problem.BUDGET_EXHAUSTED, round_number - 1, searched)
            final_round = round_number == MAX_ROUNDS
            structured = StructuredRequest(
                task=Task.AGENT_ANALYSIS,
                prompt_version=self._analysis_prompt.version,
                schema_name="issue_finding",
                schema=ANALYSIS_SCHEMA,
                messages=self._messages(
                    self._analysis_prompt,
                    self._analysis_input(issue, facts_block, list(local.values()), values, final_round),
                ),
                parse=AnalysisOutput.model_validate,
            )
            try:
                output = await once_more(partial(self._gateway.complete, structured, state.runs))
            except ProcessingError as error:
                # One issue that cannot be analyzed is shown as such; the others still stand.
                logger.warning("issue_not_analyzed", kind=issue.kind, reason=error.code)
                return self._unanswered(position, issue, Problem.ANALYSIS_FAILED, round_number, searched)
            new_queries = usable_queries(output.follow_up_queries, searched)
            if output.action == "SEARCH_MORE" and not final_round and new_queries:
                queries = new_queries
                continue
            # Citations are checked against everything retrieved in this run, as the plan specifies.
            run_evidence = {item.evidence_id: item for item in state.evidence.values()}
            return validated(
                position=position,
                kind=issue.kind,
                question=issue.question,
                output=output,
                final_round=final_round,
                evidence=run_evidence,
                facts=facts,
                values=values,
                rounds=round_number,
                queries=searched,
            )
        raise AssertionError("unreachable: the last round always returns")  # pragma: no cover

    async def _search(self, version_id: UUID, query: str, state: RunState) -> list[Evidence]:
        """Results and their related provisions, each with its full text, so that a quote from any
        part of a long provision can be checked."""
        result = await self._retrieval.search(
            SearchRequest(version_id=version_id, query=query, limit=RESULTS_PER_QUERY)
        )
        state.runs.extend(result.runs)
        if result.degraded:
            state.degraded_searches += 1
        wanted = list(
            dict.fromkeys(
                [item.provision_id for item in result.results]
                + [item.provision_id for item in result.related]
            )
        )
        missing = [provision_id for provision_id in wanted if provision_id not in state.evidence]
        for row in await self._retrieval.provisions(version_id, missing):
            # Another issue may have registered it while this one awaited: keep the first.
            if row.provision_id not in state.evidence:
                state.evidence[row.provision_id] = Evidence(
                    state.next_evidence_id(), row.provision_id, row.key, row.title, row.type, row.text
                )
        return [state.evidence[provision_id] for provision_id in wanted if provision_id in state.evidence]

    @staticmethod
    def _unanswered(
        position: int, issue: PlannedIssue, problem: Problem, rounds: int, queries: list[str]
    ) -> Finding:
        return Finding(
            position=position,
            kind=issue.kind,
            question=issue.question.strip(),
            status=FindingStatus.INSUFFICIENT_EVIDENCE,
            proposed_status=None,
            facts_used=[],
            citations=[],
            reasoning="",
            unresolved=[
                "This issue could not be analyzed."
                if problem == Problem.ANALYSIS_FAILED
                else "This issue was not examined within the analysis allowance."
            ],
            applied_values=[],
            problems=[problem],
            rejected_citations=0,
            rounds=rounds,
            queries=queries,
        )

    @staticmethod
    def _messages(prompt: Prompt, content: str) -> list[Message]:
        return [{"role": "system", "content": prompt.text}, {"role": "user", "content": content}]

    @staticmethod
    def _facts_block(request: AnalysisRequest, facts: dict[str, ClaimFact]) -> str:
        lines = [
            f"claim_type: {request.claim_type}",
            f"cover_start [PRESENT]: {request.cover_start.isoformat()}",
        ]
        admission = facts.get("admission_date")
        if admission and admission.state == "PRESENT" and admission.value:
            # Computed here, so that the model compares a stated duration and never does date arithmetic.
            lines.append(
                f"cover_before_admission [PRESENT]: {cover_duration(request.cover_start, admission.value)}"
            )
        for name, fact in sorted(facts.items()):
            shown = data(fact.value) if fact.state == "PRESENT" and fact.value else ""
            lines.append(f"{name} [{fact.state}]: {shown}".rstrip())
        for item in request.line_items[:60]:
            amount = f"{request.currency} {item.amount}" if item.amount is not None else "amount uncertain"
            lines.append(f"bill line: {data(item.description)} = {amount}")
        return "<claim_facts>\n" + "\n".join(lines) + "\n</claim_facts>"

    @staticmethod
    def _analysis_input(
        issue: PlannedIssue,
        facts_block: str,
        evidence: list[Evidence],
        values: dict[str, PolicyValue],
        final_round: bool,
    ) -> str:
        provision_ids = {item.provision_id for item in evidence}
        evidence_lines = [
            f"[{item.evidence_id}] {item.key or ''} {data(item.title)} ({item.type})\n{data(item.text)}"
            for item in evidence
        ]
        by_provision = {item.provision_id: item.evidence_id for item in evidence}
        value_lines = [
            f"[{value_id}] in {by_provision[value.provision_id]}: {data(value.name)} = {data(value.value)}"
            f"{' ' + value.unit if value.unit else ''}"
            f"{' ' + data(value.qualifier) if value.qualifier else ''} ({value.kind})"
            for value_id, value in values.items()
            if value.provision_id in provision_ids
        ]
        instruction = (
            "This is the last round: return a FINDING."
            if final_round
            else "Return a FINDING, or SEARCH_MORE if another search of the wording is needed."
        )
        return "\n\n".join(
            [
                f"<issue>\n{issue.kind}: {data(issue.question)}\n</issue>",
                facts_block,
                "<policy_evidence>\n"
                + ("\n\n".join(evidence_lines) or "(nothing found)")
                + "\n</policy_evidence>",
                "<policy_values>\n"
                + ("\n".join(value_lines) or "(none for these provisions)")
                + "\n</policy_values>",
                instruction,
            ]
        )
