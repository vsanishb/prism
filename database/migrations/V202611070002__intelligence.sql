-- AI outputs are immutable derived records (D-037): new results add rows, and never change old ones.

CREATE TABLE core.ai_runs (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id       uuid NOT NULL REFERENCES core.claims (id),
    document_id    uuid NOT NULL REFERENCES core.documents (id),
    task           text NOT NULL,
    model          text NOT NULL,
    provider       text,
    prompt_version text NOT NULL,
    attempt        integer NOT NULL CHECK (attempt > 0),
    outcome        text NOT NULL CHECK (outcome IN ('SUCCEEDED', 'INVALID_OUTPUT', 'UNAVAILABLE', 'REJECTED')),
    input_tokens   integer,
    output_tokens  integer,
    cost_usd       numeric(12, 6),
    latency_ms     integer NOT NULL,
    recorded_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ai_runs_claim ON core.ai_runs (claim_id);

CREATE TABLE core.document_extractions (
    id                        uuid PRIMARY KEY DEFAULT uuidv7(),
    document_id               uuid NOT NULL REFERENCES core.documents (id),
    claim_id                  uuid NOT NULL REFERENCES core.claims (id),
    pipeline_version          text NOT NULL,
    declared_type             text NOT NULL,
    classified_type           text NOT NULL,
    classification_confidence text NOT NULL CHECK (classification_confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    result                    jsonb NOT NULL,
    created_at                timestamptz NOT NULL DEFAULT now(),
    -- Makes extraction idempotent: a retried activity cannot store a second result.
    UNIQUE (document_id, pipeline_version)
);

CREATE INDEX document_extractions_claim ON core.document_extractions (claim_id);

-- One consolidation per processing cycle. The newest is current, and older ones stay as history.
CREATE TABLE core.claim_consolidations (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id   uuid NOT NULL REFERENCES core.claims (id),
    sequence   integer NOT NULL CHECK (sequence > 0),
    complete   boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (claim_id, sequence)
);

CREATE TABLE core.claim_facts (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    consolidation_id uuid NOT NULL REFERENCES core.claim_consolidations (id),
    field_name       text NOT NULL,
    kind             text NOT NULL CHECK (kind IN ('TEXT', 'DATE', 'AMOUNT')),
    critical         boolean NOT NULL,
    -- NULL for a claim-level fact. Set for a value that belongs to one document, such as a bill total.
    document_id      uuid REFERENCES core.documents (id),
    state            text NOT NULL CHECK (state IN ('PRESENT', 'MISSING', 'UNCERTAIN', 'CONFLICTING')),
    value            text,
    amount           numeric(14, 2),
    UNIQUE NULLS NOT DISTINCT (consolidation_id, field_name, document_id),
    -- Only a single agreed value may be stated. No value is silently chosen.
    CHECK (state = 'PRESENT' OR value IS NULL),
    CHECK (state <> 'PRESENT' OR value IS NOT NULL),
    CHECK (kind <> 'AMOUNT' OR state <> 'PRESENT' OR amount IS NOT NULL)
);

CREATE INDEX claim_facts_consolidation ON core.claim_facts (consolidation_id);

CREATE TABLE core.claim_fact_sources (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    fact_id     uuid NOT NULL REFERENCES core.claim_facts (id),
    document_id uuid NOT NULL REFERENCES core.documents (id),
    state       text NOT NULL CHECK (state IN ('PRESENT', 'UNCERTAIN')),
    confidence  text NOT NULL CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    raw_value   text,
    value       text,
    page        integer,
    quote       text,
    -- x0, y0, x1, y1 as fractions of the page, from the top left. Null on scans.
    region      numeric(7, 5)[] CHECK (region IS NULL OR cardinality(region) = 4),
    grounding   text NOT NULL CHECK (grounding IN ('TEXT_MATCH', 'PAGE_ONLY', 'NONE')),
    reason      text,
    -- The rule that a value without a source cannot be PRESENT, held by the database as well.
    CHECK (state <> 'PRESENT' OR (page IS NOT NULL AND quote IS NOT NULL AND grounding <> 'NONE'))
);

CREATE INDEX claim_fact_sources_fact ON core.claim_fact_sources (fact_id);

CREATE TABLE core.claim_line_items (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    consolidation_id uuid NOT NULL REFERENCES core.claim_consolidations (id),
    document_id      uuid NOT NULL REFERENCES core.documents (id),
    position         integer NOT NULL,
    description      text NOT NULL,
    quantity         text,
    unit_price       text,
    amount           numeric(14, 2),
    raw_amount       text NOT NULL,
    state            text NOT NULL CHECK (state IN ('PRESENT', 'UNCERTAIN')),
    page             integer NOT NULL,
    quote            text NOT NULL,
    region           numeric(7, 5)[] CHECK (region IS NULL OR cardinality(region) = 4),
    grounding        text NOT NULL CHECK (grounding IN ('TEXT_MATCH', 'PAGE_ONLY', 'NONE')),
    UNIQUE (consolidation_id, document_id, position),
    CHECK (state <> 'PRESENT' OR amount IS NOT NULL)
);

CREATE TABLE core.validation_findings (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    consolidation_id uuid NOT NULL REFERENCES core.claim_consolidations (id),
    code             text NOT NULL,
    severity         text NOT NULL CHECK (severity IN ('ERROR', 'WARNING', 'INFO')),
    field_name       text,
    document_id      uuid REFERENCES core.documents (id),
    expected_value   text,
    actual_value     text
);

CREATE INDEX validation_findings_consolidation ON core.validation_findings (consolidation_id);

REVOKE UPDATE, DELETE ON
    core.ai_runs, core.document_extractions, core.claim_consolidations, core.claim_facts,
    core.claim_fact_sources, core.claim_line_items, core.validation_findings
FROM prism_core_app;
