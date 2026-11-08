# System Flows

How the major processes run. These describe the target design. For components, see [architecture.md](architecture.md).

## Authentication

```mermaid
sequenceDiagram
    participant B as Browser
    participant C as Core
    participant D as Postgres
    B->>C: POST /api/v1/auth/login (email, password, CSRF token)
    C->>D: Check throttling and load user
    C->>C: Verify Argon2id hash
    C->>D: Rotate session, write audit event
    C-->>B: HttpOnly session cookie
    B->>C: Later request with cookie
    C->>C: Resolve session, check permission
```

Failures return one generic message and are audited. Accounts are created by invitation only.

## Claim processing

```mermaid
flowchart TD
    A[Claim submitted] --> B[Outbox starts workflow]
    B --> C[Extract each document in parallel]
    C --> D[Consolidate facts and validate]
    D --> E{Required documents present?}
    E -- No --> F[Wait for documents or timeout]
    F -- Documents added --> C
    F -- Timeout --> K
    E -- Yes --> G[Policy Agent with hybrid retrieval]
    G --> H[Fraud and risk assessment]
    H --> K[Create review task]
    K --> L{Reviewer action}
    L -- Request information --> F
    L -- Escalate --> M[Senior review or investigation]
    M --> L
    L -- Record outcome --> N[Authorized outcome, workflow completes]
```

Every claim reaches human review, whatever its risk band. A technical failure that exhausts retries moves the stage to `FAILED`, with a retry action for authorized users. It never becomes a claim decision.

### Claim states

| State | Meaning |
|---|---|
| `DRAFT` | Being prepared; no workflow yet |
| `SUBMITTED` | Accepted and queued |
| `PROCESSING` | Automated stages running |
| `AWAITING_INFORMATION` | Waiting for documents or a response |
| `IN_REVIEW` | Assigned to or queued for a human |
| `ESCALATED` | With a senior reviewer or investigator |
| `DECIDED` | Authorized outcome recorded (terminal) |
| `WITHDRAWN` | Closed without a decision (terminal) |

Each stage reports `NOT_STARTED`, `PROCESSING`, `COMPLETED`, `REQUIRES_REVIEW`, or `FAILED`.

## Document extraction

```mermaid
sequenceDiagram
    participant W as Claim workflow
    participant C as Core activity
    participant A as AI service
    participant O as OpenRouter
    W->>C: extractDocument(documentId)
    C->>A: POST /internal/v1/documents/extract (bytes, type hint)
    A->>A: Render pages and read text layer, in a killable process
    A->>O: Classify and extract to schema
    A->>A: Validate schema, ground values in text layer
    A-->>C: Fields with state, confidence, source refs, run metadata
    C->>C: Persist extraction and AI run
```

Invalid model output is retried with a stronger route. If it still fails, the document is flagged rather than guessed.

## Policy ingestion

```mermaid
flowchart TD
    A[Upload policy version] --> B[Workflow starts]
    B --> C[Extract page batches in parallel]
    C --> D[Assemble structure across pages]
    D --> E[Validate values and completeness]
    E --> F[Build provisions and relations]
    F --> G[Chunk and embed as draft]
    G --> H{Policy manager review}
    H -- Corrections --> E
    H -- Approve --> I[Published: usable as evidence]
    H -- Reject --> J[Rejected]
```

Only `PUBLISHED` versions can be retrieved for a claim. Coverage amounts, limits, waiting periods, and exclusions require explicit human confirmation before publication.

## RAG retrieval

```mermaid
flowchart TD
    Q[Query from de-identified claim facts] --> F[Metadata filter: exact policy version]
    F --> S[Semantic search: pgvector]
    F --> L[Lexical search: full text]
    F --> N[Exact lookup: named clauses, defined terms by trigram]
    S --> R[Reciprocal rank fusion]
    L --> R
    N --> R
    R --> X[Rerank against claim context]
    X --> E[Expand related provisions: exceptions, definitions, limits]
    E --> V[Evidence set with source references]
```

The metadata filter runs first, so evidence from another policy or version cannot appear, and core checks every result belongs to the version it asked about ([D-075](decisions.md)). A failed embedding or rerank is tried once more, then skipped and reported as `degraded` ([D-076](decisions.md)). The index is built when a version is published, by `PolicyIndexingWorkflow`. Expansion matters: an exclusion is incomplete without its exception, and a term without its definition can mislead.

## Policy Agent

```mermaid
flowchart TD
    A[Claim facts and policy version] --> B[Plan issues to examine]
    B --> C[Retrieve evidence per issue]
    C --> D{Evidence sufficient? Max rounds not reached?}
    D -- No --> C
    D -- Yes or limit reached --> E[Structured analysis per issue]
    E --> F[Validate citations against retrieved evidence]
    F --> G[Deterministic limit and amount calculations]
    G --> H[Assessment with facts, evidence, reasoning, unknowns]
```

An unsupported citation invalidates that finding, which then becomes an unresolved question. The agent never issues a decision, and its analysis never holds a claim from review ([D-080](decisions.md)). Core checks citations against the published version, and computes amounts and dates from confirmed values. As built: [Policy Agent](modules/policy-agent.md).

## Fraud and risk

```mermaid
flowchart TD
    A[Claim facts] --> B[Consistency rules]
    A --> C[Financial rules]
    P[Prior-claim matches from core] --> D[Duplicate rules]
    A --> E[Document anomaly rules]
    A --> M[Missing information rules]
    B & C & D & E & M --> F[Indicators with evidence]
    F --> G[Explainable score and band]
    G --> H[Review routing]
```

Core finds cross-claim matches, because AI has no access to claim history, and passes them in. A high band adds scrutiny and routes to an investigator. It never rejects a claim.

## Human review

```mermaid
sequenceDiagram
    participant R as Reviewer
    participant C as Core
    participant D as Postgres
    participant T as Temporal
    R->>C: Open task, inspect evidence
    R->>C: Submit outcome with rationale
    C->>C: Check permission and separation of duties
    C->>D: One transaction: outcome, audit event, outbox event
    C-->>R: Accepted
    D-->>T: Outbox relay signals workflow
    T->>T: Workflow completes
```

The database is the record of the decision. The signal is deduplicated by decision ID, so it can be retried safely.

## Audit

```mermaid
flowchart LR
    A[Sensitive action] --> B[Business transaction]
    B --> C[Audit event in same transaction]
    C --> D[Per-stream hash chain]
    D --> E[Append-only table]
    E --> F[Auditor viewer and chain verification]
```

An action and its audit record commit or roll back together.

## Evidence chain

```mermaid
flowchart TD
    A[Source document and page] --> B[Claim fact with provenance]
    B --> C[Policy retrieval]
    B --> D[Risk rules]
    C --> E[Cited provision and source page]
    D --> F[Risk indicators]
    E --> G[Policy assessment]
    F --> G2[Risk assessment]
    G --> H[Human review]
    G2 --> H
    H --> I[Authorized outcome]
```

From any outcome, a reviewer or auditor can trace back to the exact source pages and AI runs.
