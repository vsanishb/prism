-- Human review and authorized outcomes (M8). People decide: every claim reaches a review task, and
-- only an authorized person records an outcome. See docs/plans/human-review.md.

ALTER TABLE core.outbox_events DROP CONSTRAINT outbox_events_event_type_check;
ALTER TABLE core.outbox_events ADD CONSTRAINT outbox_events_event_type_check CHECK (event_type IN (
    'CLAIM_SUBMITTED', 'CLAIM_DOCUMENTS_CHANGED', 'CLAIM_RETRY_REQUESTED', 'CLAIM_WITHDRAWN',
    'CLAIM_REANALYSIS_REQUESTED', 'CLAIM_DECIDED',
    'POLICY_WORDING_ATTACHED', 'POLICY_INGESTION_RETRY_REQUESTED',
    'POLICY_VERSION_PUBLISHED', 'POLICY_INDEX_RETRY_REQUESTED'));

-- Work items. Operational state, so status changes in place; every change is audited.
CREATE TABLE core.review_tasks (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id     uuid NOT NULL REFERENCES core.claims (id),
    queue        text NOT NULL CHECK (queue IN ('REVIEW', 'SENIOR_REVIEW', 'INVESTIGATION')),
    reason       text NOT NULL CHECK (reason IN (
                     'STANDARD', 'ABOVE_AUTHORITY_LIMIT', 'HIGH_RISK', 'ESCALATED', 'INVESTIGATED',
                     'INFORMATION_RECEIVED', 'REQUEST_CANCELLED', 'REANALYZED')),
    status       text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLAIMED', 'COMPLETED', 'CANCELLED')),
    assignee_id  uuid REFERENCES core.users (id),
    completion   text CHECK (completion IN (
                     'OUTCOME_RECORDED', 'ESCALATED', 'INFORMATION_REQUESTED', 'INVESTIGATION_RECORDED',
                     'REANALYSIS_REQUESTED', 'REPROCESSING')),
    escalation_note text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    claimed_at   timestamptz,
    completed_at timestamptz,
    version      bigint NOT NULL DEFAULT 0,
    CHECK (status <> 'CLAIMED' OR assignee_id IS NOT NULL),
    CHECK (status <> 'OPEN' OR assignee_id IS NULL),
    CHECK ((status IN ('COMPLETED', 'CANCELLED')) = (completed_at IS NOT NULL)),
    -- A finished task says how it ended; a cancelled one ended because the claim went back to processing.
    CHECK ((status = 'COMPLETED') = (completion IS NOT NULL AND completion <> 'REPROCESSING')),
    CHECK ((status = 'CANCELLED') = (completion = 'REPROCESSING'))
);

-- One live task per claim: two people can never work the same claim at once.
CREATE UNIQUE INDEX review_tasks_one_live_per_claim ON core.review_tasks (claim_id) WHERE status IN ('OPEN', 'CLAIMED');
CREATE INDEX review_tasks_queue ON core.review_tasks (queue, created_at) WHERE status = 'OPEN';
CREATE INDEX review_tasks_assignee ON core.review_tasks (assignee_id) WHERE status = 'CLAIMED';

CREATE TABLE core.review_notes (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id   uuid NOT NULL REFERENCES core.claims (id),
    task_id    uuid REFERENCES core.review_tasks (id),
    author_id  uuid NOT NULL REFERENCES core.users (id),
    body       text NOT NULL CHECK (length(trim(body)) BETWEEN 1 AND 4000),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX review_notes_claim ON core.review_notes (claim_id, id);

CREATE TABLE core.information_requests (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id       uuid NOT NULL REFERENCES core.claims (id),
    task_id        uuid NOT NULL REFERENCES core.review_tasks (id),
    requested_by   uuid NOT NULL REFERENCES core.users (id),
    document_types text[] NOT NULL CHECK (cardinality(document_types) > 0),
    message        text NOT NULL CHECK (length(trim(message)) BETWEEN 1 AND 2000),
    status         text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'FULFILLED', 'CANCELLED')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    closed_at      timestamptz,
    closed_by      uuid REFERENCES core.users (id),
    CHECK ((status = 'OPEN') = (closed_at IS NULL))
);

CREATE UNIQUE INDEX information_requests_one_open ON core.information_requests (claim_id) WHERE status = 'OPEN';

-- Recorded only by an investigator. Confirmed fraud exists only here, as a person's finding.
CREATE TABLE core.investigation_findings (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id        uuid NOT NULL REFERENCES core.claims (id),
    task_id         uuid NOT NULL UNIQUE REFERENCES core.review_tasks (id),
    investigator_id uuid NOT NULL REFERENCES core.users (id),
    finding         text NOT NULL CHECK (finding IN ('NO_CONCERN', 'INCONCLUSIVE', 'CONFIRMED_FRAUD')),
    rationale       text NOT NULL CHECK (length(trim(rationale)) BETWEEN 10 AND 4000),
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- The decision record. Immutable: a revision is a new row that supersedes the old one.
CREATE TABLE core.authorized_outcomes (
    id                   uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id             uuid NOT NULL REFERENCES core.claims (id),
    task_id              uuid REFERENCES core.review_tasks (id),
    decision             text NOT NULL CHECK (decision IN ('APPROVED', 'PARTIALLY_APPROVED', 'DENIED')),
    approved_amount      numeric(14, 2),
    currency             char(3) NOT NULL,
    rationale            text NOT NULL CHECK (length(trim(rationale)) BETWEEN 10 AND 4000),
    -- How the reviewer saw the AI analysis. Disagreement is normal, and recorded for evaluation.
    ai_agreement         text NOT NULL CHECK (ai_agreement IN ('AGREED', 'PARTLY_AGREED', 'DISAGREED', 'NOT_USED')),
    -- What the decision was made with, for the evidence chain.
    consolidation_id     uuid REFERENCES core.claim_consolidations (id),
    policy_assessment_id uuid REFERENCES core.policy_assessments (id),
    risk_assessment_id   uuid REFERENCES core.risk_assessments (id),
    decided_by           uuid NOT NULL REFERENCES core.users (id),
    decided_at           timestamptz NOT NULL DEFAULT now(),
    supersedes_id        uuid UNIQUE REFERENCES core.authorized_outcomes (id),
    CHECK ((decision = 'DENIED') = (approved_amount IS NULL)),
    CHECK (approved_amount IS NULL OR approved_amount > 0),
    -- The first outcome comes from a task; a revision comes from a senior reviewer's action.
    CHECK ((supersedes_id IS NULL) = (task_id IS NOT NULL))
);

CREATE INDEX authorized_outcomes_claim ON core.authorized_outcomes (claim_id, id);
-- A claim has one first outcome; later ones must supersede.
CREATE UNIQUE INDEX authorized_outcomes_one_first ON core.authorized_outcomes (claim_id) WHERE supersedes_id IS NULL;

-- A reviewer's correction of an extracted fact. Versions, never overwrites: the extraction stays as it was.
CREATE TABLE core.fact_corrections (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id      uuid NOT NULL REFERENCES core.claims (id),
    field_name    text NOT NULL,
    document_id   uuid REFERENCES core.documents (id),
    kind          text NOT NULL CHECK (kind IN ('TEXT', 'DATE', 'AMOUNT')),
    value         text NOT NULL CHECK (length(trim(value)) > 0),
    amount        numeric(14, 2),
    reason        text NOT NULL CHECK (length(trim(reason)) BETWEEN 5 AND 2000),
    based_on_fact uuid NOT NULL REFERENCES core.claim_facts (id),
    corrected_by  uuid NOT NULL REFERENCES core.users (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK ((kind = 'AMOUNT') = (amount IS NOT NULL))
);

CREATE INDEX fact_corrections_claim ON core.fact_corrections (claim_id, id);

-- A consolidated fact that a reviewer's correction set. Its sources still show what documents said.
ALTER TABLE core.claim_facts ADD COLUMN correction_id uuid REFERENCES core.fact_corrections (id);

REVOKE UPDATE, DELETE ON
    core.review_notes, core.investigation_findings, core.authorized_outcomes, core.fact_corrections
FROM prism_core_app;
REVOKE DELETE ON core.review_tasks, core.information_requests FROM prism_core_app;
