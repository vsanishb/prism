-- Risk screening (M7). Signals for human review: never a determination of fraud, and never an
-- outcome. Insert-only, like all derived data (D-037). See docs/plans/fraud-risk.md.

ALTER TABLE core.stage_events DROP CONSTRAINT stage_events_stage_check;
ALTER TABLE core.stage_events ADD CONSTRAINT stage_events_stage_check CHECK (stage IN (
    'DOCUMENT_EXTRACTION', 'CONSOLIDATION', 'VALIDATION', 'POLICY_ANALYSIS', 'RISK_SCREENING'));

-- One per consolidation: new documents give new facts, and so a new screening. Every version the
-- result was computed with is kept, so that it can be reproduced and explained later.
CREATE TABLE core.risk_assessments (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id          uuid NOT NULL REFERENCES core.claims (id),
    consolidation_id  uuid NOT NULL UNIQUE REFERENCES core.claim_consolidations (id),
    ruleset_version   text NOT NULL,
    scoring_version   text NOT NULL,
    feature_version   text NOT NULL,
    model_version     text NOT NULL,
    model_fingerprint text NOT NULL,
    baseline_size     integer NOT NULL CHECK (baseline_size > 0),
    score             integer NOT NULL CHECK (score BETWEEN 0 AND 100),
    band              text NOT NULL CHECK (band IN ('LOW', 'MEDIUM', 'HIGH')),
    -- Routing for review only (M8 builds the queue). No band changes a claim's status or outcome.
    route             text NOT NULL CHECK (route IN ('STANDARD_REVIEW', 'SENIOR_REVIEW')),
    -- What the rules alone would give, so that a reviewer sees what the detectors added.
    rules_only_score  integer NOT NULL CHECK (rules_only_score BETWEEN 0 AND score),
    rules_only_band   text NOT NULL CHECK (rules_only_band IN ('LOW', 'MEDIUM', 'HIGH')),
    rules_evaluated   text[] NOT NULL,
    -- Ordered pairs of feature values and their place in the baseline, as the detectors saw them.
    features          jsonb NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK ((band = 'HIGH') = (route = 'SENIOR_REVIEW'))
);

CREATE INDEX risk_assessments_claim ON core.risk_assessments (claim_id, id DESC);

-- How the score was built: each category's points before and after its cap.
CREATE TABLE core.risk_category_scores (
    assessment_id uuid NOT NULL REFERENCES core.risk_assessments (id),
    category      text NOT NULL CHECK (category IN (
                      'CONSISTENCY', 'FINANCIAL', 'DUPLICATE', 'DOCUMENT', 'MISSING_INFORMATION', 'STATISTICAL')),
    points        integer NOT NULL CHECK (points >= 0),
    cap           integer NOT NULL CHECK (cap >= 0),
    contribution  integer NOT NULL,
    PRIMARY KEY (assessment_id, category),
    CHECK (contribution = LEAST(points, cap))
);

-- Found by deterministic rules. The kind keeps the vocabulary apart: an inconsistency, a risk
-- indicator, or a fraud indicator, which may warrant investigation and is not a finding of fraud.
CREATE TABLE core.risk_indicators (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    assessment_id uuid NOT NULL REFERENCES core.risk_assessments (id),
    position      integer NOT NULL CHECK (position > 0),
    rule_id       text NOT NULL,
    rule_version  integer NOT NULL CHECK (rule_version > 0),
    category      text NOT NULL CHECK (category IN (
                      'CONSISTENCY', 'FINANCIAL', 'DUPLICATE', 'DOCUMENT', 'MISSING_INFORMATION')),
    kind          text NOT NULL CHECK (kind IN ('DATA_INCONSISTENCY', 'RISK_INDICATOR', 'FRAUD_INDICATOR')),
    severity      text NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    points        integer NOT NULL CHECK (points > 0),
    title         text NOT NULL,
    explanation   text NOT NULL,
    UNIQUE (assessment_id, position)
);

-- What each indicator rests on. Another claim is referenced by ID only: none of its content is copied.
CREATE TABLE core.risk_evidence (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    indicator_id     uuid NOT NULL REFERENCES core.risk_indicators (id),
    position         integer NOT NULL CHECK (position > 0),
    label            text NOT NULL,
    detail           text,
    document_id      uuid REFERENCES core.documents (id),
    page             integer CHECK (page IS NULL OR page > 0),
    fact_id          uuid REFERENCES core.claim_facts (id),
    line_item_id     uuid REFERENCES core.claim_line_items (id),
    matched_claim_id uuid REFERENCES core.claims (id),
    UNIQUE (indicator_id, position),
    CHECK (page IS NULL OR document_id IS NOT NULL)
);

CREATE INDEX risk_evidence_indicator ON core.risk_evidence (indicator_id);

-- Statistical anomalies, one row per detector. A score measures how unusual, never how likely fraud is.
CREATE TABLE core.risk_anomaly_results (
    assessment_id uuid NOT NULL REFERENCES core.risk_assessments (id),
    detector      text NOT NULL CHECK (detector IN ('ISOLATION_FOREST', 'LOCAL_OUTLIER_FACTOR')),
    score         numeric(12, 6) NOT NULL,
    threshold     numeric(12, 6) NOT NULL,
    percentile    numeric(5, 2) NOT NULL CHECK (percentile BETWEEN 0 AND 100),
    flagged       boolean NOT NULL,
    points        integer NOT NULL CHECK (points >= 0),
    PRIMARY KEY (assessment_id, detector),
    CHECK (flagged OR points = 0)
);

-- The optional AI summary (D-086). Written after the screening, in its own table, so that it can
-- never change a score, band, or route.
CREATE TABLE core.risk_summaries (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    assessment_id  uuid NOT NULL UNIQUE REFERENCES core.risk_assessments (id),
    status         text NOT NULL CHECK (status IN ('AVAILABLE', 'UNAVAILABLE', 'NOT_NEEDED')),
    text           text,
    -- What each signal ID the model saw stood for, in order: S1 is the first entry.
    signal_refs    text[] NOT NULL,
    cited          text[] NOT NULL,
    problem        text,
    prompt_version text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'AVAILABLE') = (text IS NOT NULL))
);

REVOKE UPDATE, DELETE ON
    core.risk_assessments, core.risk_category_scores, core.risk_indicators, core.risk_evidence,
    core.risk_anomaly_results, core.risk_summaries
FROM prism_core_app;

-- Seeded demonstration claims (D-088). Local environment only. Maps each scenario to its claims.
CREATE TABLE core.demonstration_claims (
    scenario text NOT NULL CHECK (scenario IN ('SEVERE', 'CLEAN', 'DISGUISED')),
    role     text NOT NULL CHECK (role IN ('EARLIER', 'SCENARIO')),
    position integer NOT NULL CHECK (position > 0),
    claim_id uuid NOT NULL UNIQUE REFERENCES core.claims (id),
    PRIMARY KEY (scenario, role, position)
);

REVOKE UPDATE, DELETE ON core.demonstration_claims FROM prism_core_app;
