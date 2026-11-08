-- The Policy Agent's analysis of a claim against its policy version (M6). It assists review and
-- decides nothing: statuses describe evidence, never outcomes. Insert-only, like all derived data.

ALTER TABLE core.stage_events DROP CONSTRAINT stage_events_stage_check;
ALTER TABLE core.stage_events ADD CONSTRAINT stage_events_stage_check CHECK (stage IN (
    'DOCUMENT_EXTRACTION', 'CONSOLIDATION', 'VALIDATION', 'POLICY_ANALYSIS'));

-- One per consolidation: new documents give new facts, and so a new analysis. A version that cannot
-- be searched gives a failed stage, retried once the version is published, and no row here.
CREATE TABLE core.policy_assessments (
    id                      uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id                uuid NOT NULL REFERENCES core.claims (id),
    consolidation_id        uuid NOT NULL UNIQUE REFERENCES core.claim_consolidations (id),
    version_id              uuid NOT NULL REFERENCES core.policy_versions (id),
    pipeline_version        text NOT NULL,
    plan_prompt_version     text NOT NULL,
    analysis_prompt_version text NOT NULL,
    -- Waiting periods count from here: the start of continuous cover in the policy register.
    cover_start             date NOT NULL,
    degraded_searches       integer NOT NULL CHECK (degraded_searches >= 0),
    created_at              timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX policy_assessments_claim ON core.policy_assessments (claim_id, id DESC);

CREATE TABLE core.assessment_findings (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    assessment_id   uuid NOT NULL REFERENCES core.policy_assessments (id),
    position        integer NOT NULL CHECK (position > 0),
    kind            text NOT NULL CHECK (kind IN (
                        'COVERAGE', 'LIMIT', 'WAITING_PERIOD', 'EXCLUSION', 'COST_SHARING', 'CONDITION')),
    question        text NOT NULL,
    status          text NOT NULL CHECK (status IN (
                        'SUPPORTED', 'LIMITED', 'POTENTIALLY_EXCLUDED', 'INSUFFICIENT_EVIDENCE',
                        'NEEDS_HUMAN_INTERPRETATION')),
    -- What the model proposed, when validation changed it. Kept so a reviewer can see the change.
    proposed_status text,
    reasoning       text NOT NULL,
    unresolved      text[] NOT NULL,
    problems        text[] NOT NULL,
    rounds          integer NOT NULL CHECK (rounds >= 0),
    queries         text[] NOT NULL,
    UNIQUE (assessment_id, position)
);

CREATE TABLE core.assessment_citations (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    finding_id   uuid NOT NULL REFERENCES core.assessment_findings (id),
    provision_id uuid NOT NULL REFERENCES core.policy_provisions (id),
    quote        text NOT NULL CHECK (length(quote) > 0)
);

CREATE INDEX assessment_citations_finding ON core.assessment_citations (finding_id);

CREATE TABLE core.assessment_facts (
    finding_id uuid NOT NULL REFERENCES core.assessment_findings (id),
    fact_id    uuid NOT NULL REFERENCES core.claim_facts (id),
    PRIMARY KEY (finding_id, fact_id)
);

-- Computed by core from a value a person confirmed, never by a model (D-029). Inputs and steps are
-- kept, so that a reviewer can check the arithmetic. A result may be absent when an input is unknown.
CREATE TABLE core.assessment_calculations (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    finding_id    uuid NOT NULL REFERENCES core.assessment_findings (id),
    value_id      uuid NOT NULL REFERENCES core.policy_values (id),
    role          text NOT NULL CHECK (role IN (
                      'WAITING_PERIOD', 'AMOUNT_LIMIT', 'DAILY_LIMIT', 'PERCENT_OF_SUM_INSURED', 'COPAYMENT',
                      'DEDUCTIBLE')),
    inputs        jsonb NOT NULL,
    steps         text[] NOT NULL,
    result_amount numeric(14, 2),
    result_date   date,
    outcome       text NOT NULL
);

CREATE INDEX assessment_calculations_finding ON core.assessment_calculations (finding_id);

REVOKE UPDATE, DELETE ON
    core.policy_assessments, core.assessment_findings, core.assessment_citations, core.assessment_facts,
    core.assessment_calculations
FROM prism_core_app;

-- Analysis runs belong to a claim but to no single document.
ALTER TABLE core.ai_runs
    DROP CONSTRAINT ai_runs_document_known,
    ADD CONSTRAINT ai_runs_document_known CHECK (ingestion_id IS NULL OR document_id IS NOT NULL);
