# Roadmap

Each milestone delivers a substantial, testable slice through the frontend. Complete them in order. A box is checked only when the work is implemented and tested. Detail lives in [plans/](plans/README.md); real state lives in [progress.md](progress.md).

## Overview

| # | Milestone | Status | Depends on |
|---|---|---|---|
| M0 | Project foundation | Complete | None |
| M1 | Secure platform | Complete, except first CI run | M0 |
| M2 | Claims and documents | Complete | M1 |
| M3 | Document intelligence and workflows | Complete | M2 |
| M4 | Policy intelligence | Complete | M3 |
| M5 | Hybrid retrieval | Complete | M4 |
| M6 | Policy Agent | Complete | M3, M5 |
| M7 | Fraud and risk intelligence | **Next** | M3 |
| M8 | Human review and outcomes | Planned | M6, M7 |
| M9 | Production readiness | Planned | M8 |

Every milestone also requires: tests at the levels in [testing.md](testing.md), a visual review of every new screen at desktop and phone width, synthetic test data, a [frontend test guide](test-guides/README.md), updated docs, and meaningful commits.

---

## M0: Project foundation

**Objective:** a durable plan and documentation foundation.

- [x] Verify the environment and version compatibility
- [x] Write `CLAUDE.md` and the repository structure
- [x] Write the documentation guide, project context, architecture, and flows
- [x] Write security, testing, roadmap, progress, and decision records
- [x] Write module plans

**Completion:** docs are consistent and committed. No application code is expected.

---

## M1: Secure platform

**Objective:** a real, secure application shell that users sign in to, with audit and service trust working from the start.
**Plans:** [auth](plans/authentication-authorization.md), [audit](plans/audit-logging.md), [frontend](plans/frontend-experience.md), [deployment](plans/deployment-infrastructure.md)
**Needs from user:** nothing for local use. A Git remote when ready.

- [x] Confirm Boot 4.1.2 + Temporal 1.40 gate ([D-039](decisions.md))
- [x] Scaffold core, AI, and web projects with lint, format, and test tooling
- [x] Set up Flyway, schemas, and least-privilege roles
- [x] Set up local dependencies: a project-owned PostgreSQL cluster replaces Docker Compose ([D-046](decisions.md))
- [ ] Run CI. The workflow is written, but has never run, because there is no Git remote
- [x] Implement users, invitations, Argon2id, sessions, CSRF, and throttling
- [x] Implement permissions and RBAC
- [x] Implement append-only audit with hash chains
- [x] Implement service JWT authentication between core and AI
- [x] Build the design system foundation and application shell
- [x] Build sign-in, invitation, user administration, audit viewer, and system status screens
- [x] Provide synthetic users and a [test guide](test-guides/m1-secure-platform.md)

**Frontend test flow:** accept an invitation → sign in → see role-appropriate navigation → an admin manages users → an auditor views events and verifies the chain → system status shows a live authenticated core-to-AI check.

**Testing:** full authentication and RBAC allow and deny suite; audit immutability; service JWT rejection cases; ArchUnit rules.

**Completion:** a fresh clone runs by following the README, and all M1 security tests pass.

---

## M2: Claims and documents

**Objective:** manage policies and claims with secure document intake.
**Plan:** [claims](plans/claims-management.md)

- [x] Implement policy and policy version records
- [x] Implement the claim lifecycle and state rules
- [x] Implement document upload, validation, and storage
- [x] Implement document viewing with authorization and audit
- [x] Generate seed data for synthetic policies and claims
- [x] Build claims list, creation flow, claim detail, upload, and document viewer
- [x] Build the policy register and claim activity timeline
- [x] Provide a [test guide](test-guides/m2-claims-documents.md)

**Frontend test flow:** create a claim → associate a policy → upload documents, including a rejected invalid file → view pages in the viewer → see the audit timeline → submit.

**Testing:** state transitions; upload attacks; object-level access; pagination and filtering.

**Completion:** a claim can be created, supported with documents, submitted, and traced.

---

## M3: Document intelligence and workflows

**Objective:** a durable workflow turns claim documents into structured, source-linked claim data.
**Plans:** [workflows](plans/temporal-workflows.md), [document intelligence](plans/document-intelligence.md)
**Needs from user:** OpenRouter API key, with the account set to ZDR only.

- [x] Add the native Temporal server to local tooling and the worker to core
- [x] Implement the outbox relay and stage events
- [x] Implement `ClaimProcessingWorkflow` through consolidation
- [x] Build the model gateway with routing, ZDR enforcement, and usage records
- [x] Implement classification, vision extraction, and grounding
- [x] Implement consolidation, validation, and contradiction detection between stated values. Semantic contradictions are M7 ([D-028](decisions.md))
- [x] Implement the wait-for-documents path
- [x] Build the live processing view and stage retry
- [x] Build the extraction review screen with source highlighting
- [x] Create the extraction evaluation set
- [x] Add malformed-PDF robustness tests, with time and memory bounds

**Frontend test flow:** submit a claim → watch stages progress → inspect each fact beside its source → see missing, uncertain, and conflicting values → add a missing document and watch processing resume.

**Testing:** workflow failure, retry, and replay; gateway failure paths; extraction evaluation targets.

**Completion:** extraction meets evaluation targets, and restarting services mid-process loses nothing.

---

## M4: Policy intelligence

**Objective:** policy PDFs become validated, structured, human-approved knowledge.
**Plan:** [policy intelligence](plans/policy-intelligence.md)

- [x] Implement `PolicyIngestionWorkflow`
- [x] Implement layout-aware page extraction, including tables
- [x] Assemble structure across pages
- [x] Implement provisions and relationships
- [x] Implement validation and integrity checks
- [x] Implement review, correction, approval, and publication
- [x] Build ingestion progress, structure explorer, and side-by-side validation screens
- [x] Create the policy evaluation set

**Frontend test flow:** upload a policy → watch ingestion → browse the structure → compare a benefit table with its source → correct a flagged value → publish.

**Testing:** table and cross-page fixtures; validation rules; evaluation targets; immutability after publishing.

**Completion:** a synthetic policy publishes with critical values verified against the source.

---

## M5: Hybrid retrieval

**Objective:** find the right provisions, with measured quality.
**Plan:** [RAG retrieval](plans/rag-retrieval.md)

- [x] Implement structure-aware chunking
- [x] Implement embeddings and storage
- [x] Implement semantic and lexical retrievers
- [x] Implement metadata filtering
- [x] Implement fusion, reranking, and relationship expansion
- [x] Build the retrieval evaluation harness and golden set
- [x] Build the retrieval explorer

**Frontend test flow:** select a policy version → run a query → compare semantic, lexical, and final rankings → open a result at its source page → confirm related exceptions and definitions appear.

**Testing:** isolation between policy versions; exact clause and term lookup; recall targets; reranker outage.

**Completion:** retrieval meets targets, with D-023, D-024, and embedding choices confirmed.

---

## M6: Policy Agent

**Objective:** evidence-backed analysis of a claim against its policy.
**Plan:** [Policy Agent](plans/policy-agent.md)

- [x] Implement issue planning and the bounded retrieval loop
- [x] Implement structured analysis
- [x] Implement citation validation
- [x] Implement deterministic benefit and limit calculations
- [x] Add the analysis stage to the claim workflow
- [x] Build the assessment view with cited provisions and open questions
- [x] Create the agent evaluation set

**Frontend test flow:** process a claim → read issue-by-issue analysis → open any citation at its policy source → see facts, evidence, reasoning, and unknowns separated.

**Testing:** citation enforcement; missing evidence; conflicting provisions; waiting-period and sublimit scenarios; evaluation targets.

**Completion:** all citations are valid, and insufficient evidence yields unknowns rather than guesses.

---

## M7: Fraud and risk intelligence

**Objective:** explainable screening that never accuses.
**Plan:** [fraud and risk](plans/fraud-risk.md)

- [ ] Implement the rule engine and versioned ruleset
- [ ] Implement consistency and financial rules
- [ ] Implement duplicate and similarity detection across claims
- [ ] Implement document anomaly rules
- [ ] Implement scoring, bands, and explanations
- [ ] Add the risk stage to the workflow
- [ ] Build the risk panel with indicators linked to evidence

**Frontend test flow:** process seeded anomalous claims → see each indicator, its evidence, and contribution to the score → compare with a clean claim.

**Testing:** every rule, with positive and negative cases; seeded anomaly set; language checks confirming no accusation of fraud.

**Completion:** seeded anomalies are detected, and every indicator shows its evidence.

---

## M8: Human review and outcomes

**Objective:** complete the loop with human authority and full traceability.
**Plan:** [human review](plans/human-review.md)

- [ ] Implement review tasks, assignment, and routing
- [ ] Build the unified review workspace
- [ ] Implement requests for information
- [ ] Implement escalation and investigation
- [ ] Implement authorized outcomes and separation of duties
- [ ] Complete the workflow with signals
- [ ] Build the evidence chain view and reviewer dashboard
- [ ] Add fact correction with versioning

**Frontend test flow:** take a task → inspect evidence → request a document and resume → escalate a high-risk claim → record an outcome → trace it back to source pages.

**Testing:** end-to-end claim scenarios; authority limits; concurrent decisions; duplicate signals.

**Completion:** a claim runs from intake to authorized outcome with an unbroken evidence chain.

---

## M9: Production readiness

**Objective:** secure, observable, deployable.
**Plan:** [deployment](plans/deployment-infrastructure.md)

- [ ] Add MFA for privileged roles
- [ ] Add malware scanning
- [ ] Fuzz PDF and image parsing, in core (PDFBox, ImageIO) and AI (PDFium, Pillow)
- [ ] Move core's parsing of uploads out of process, as the AI service already does
- [ ] Implement S3-compatible storage with encryption at rest
- [ ] Write deployment configuration. The packaging approach needs the user's decision, as Docker is not allowed without approval ([D-056](decisions.md))
- [ ] Add OpenTelemetry, dashboards, and alerts
- [ ] Run performance tests and tune
- [ ] Complete threat model review and security testing, including an independent review of document parsing
- [ ] Test backup and restore
- [ ] Add an operations screen for AI usage, cost, and workflow health

**Frontend test flow:** run the full journey in a deployed environment; sign in with MFA; check operational health.

**Completion:** a staging deployment passes the full end-to-end suite and the security review. Real data still requires the [privacy gate](security.md#privacy-gate).

---

## Deferred

Not planned for the milestones above: claimant portal, external system integrations, medical coding systems, analytics, multi-tenancy, multilingual documents.
