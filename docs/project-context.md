# Project Context

The persistent reference for what Prism is. For current state, see [progress.md](progress.md).

## What Prism is

Prism is an AI-assisted Health Insurance Claims Intelligence Platform. It turns fragmented claim documents and complex policies into a structured, traceable, evidence-backed claim assessment for human reviewers.

It is a **decision-support system**. It does not make autonomous insurance decisions.

## The problem

A reviewer must reconcile bills, discharge summaries, prescriptions, reports, and claim forms against a long policy whose meaning depends on interacting clauses. A benefit may be qualified by a condition, capped by a sublimit, shaped by a definition elsewhere, or removed by an exclusion that has its own exceptions. This is slow, error-prone, and hard to audit.

## Capabilities

| Capability | What it does |
|---|---|
| Claim management | Claim lifecycle, policy association, document intake |
| Document intelligence | Vision-based extraction with provenance and explicit uncertainty |
| Policy intelligence | Turns policies into validated, structure-aware knowledge |
| Hybrid retrieval | Semantic, lexical, and metadata search with reranking |
| Policy Agent | Evidence-based analysis of a claim against its policy |
| Fraud and risk screening | Explainable rule indicators and statistical anomalies (Isolation Forest, LOF), routing review |
| Workflow orchestration | Durable, long-running processing with Temporal |
| Human review | Full evidence for reviewers, who hold final authority |
| Audit and traceability | An evidence chain from source document to outcome |

## Core architecture

```text
Next.js web  ──▶  Spring Boot core  ──▶  FastAPI AI service  ──▶  OpenRouter
                     │       │                  │
                 Temporal  Document store    PostgreSQL (knowledge)
                     │
                 PostgreSQL (core)
```

Details: [architecture.md](architecture.md).

## Technology

Java and Spring Boot; Python and FastAPI, with scikit-learn for anomaly detection; Next.js; local PostgreSQL 18 with pgvector; Temporal; OpenRouter. Pinned versions: [decisions.md](decisions.md#version-baseline).

## Product principles

1. **Source documents remain authoritative.** AI output is derived data.
2. **Evidence before conclusions.** Policy analysis rests on retrieved provisions.
3. **Uncertainty is visible.** Known, missing, uncertain, and contradictory stay distinct.
4. **Fraud signals are not fraud determinations.** Confirmed fraud requires an authorized investigation.
5. **Humans decide.** AI assessment, risk analysis, and the human decision remain separate records.
6. **Everything important is traceable.**
7. **Workflows are durable.** Failures and long waits are normal, handled states.

## Constraints

- No AI-driven final decisions or automatic rejections.
- Secrets come from the environment and are never hardcoded.
- Security is designed in from the first milestone.
- Every milestone ships a polished, testable frontend flow.
- Development uses synthetic data only.
- The interface is strictly light mode, minimal, and enterprise-grade.

## Terminology

| Term | Meaning |
|---|---|
| Claim | A request for benefits under a policy, with its documents |
| Policy version | An immutable, specific revision of a policy document |
| Provision | A structured policy unit: benefit, clause, exclusion, waiting period, definition, or procedure |
| Chunk | A searchable representation of a provision, with embedding and metadata |
| Provenance | The link from a value to its source document, page, and region |
| Field state | `PRESENT`, `MISSING`, `UNCERTAIN`, or `CONFLICTING` |
| Policy assessment | The Policy Agent's evidence-backed analysis. Not a decision |
| Risk indicator | An explainable signal that a claim needs attention |
| Fraud indicator | A signal that may warrant investigation. Not a finding of fraud |
| Data inconsistency | Documents, or documents and entered details, disagree |
| Statistical anomaly | Unusual compared with a baseline of claims. Never a measure of fraud |
| Risk band | `LOW`, `MEDIUM`, or `HIGH`: sets how closely a claim is reviewed, never its outcome |
| Confirmed fraud | Recorded only by an investigator, after investigation |
| Authorized outcome | The final decision recorded by an authorized human |
| Evidence chain | The links from source document to extraction, evidence, assessment, review, and outcome |
| Sublimit | A cap on a specific benefit within the overall sum insured |
