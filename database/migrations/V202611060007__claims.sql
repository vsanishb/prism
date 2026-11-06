CREATE SEQUENCE core.claim_number_seq;

CREATE TABLE core.claims (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_number      text NOT NULL UNIQUE,
    status            text NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
                          'DRAFT', 'SUBMITTED', 'PROCESSING', 'AWAITING_INFORMATION',
                          'IN_REVIEW', 'ESCALATED', 'DECIDED', 'WITHDRAWN')),
    claim_type        text NOT NULL CHECK (claim_type IN ('REIMBURSEMENT', 'CASHLESS')),
    policy_id         uuid NOT NULL REFERENCES core.policies (id),
    -- Fixed at submission from the treatment date, so that later policy changes cannot alter it.
    policy_version_id uuid REFERENCES core.policy_versions (id),
    claimant_name     text NOT NULL CHECK (length(trim(claimant_name)) > 0),
    patient_name      text NOT NULL CHECK (length(trim(patient_name)) > 0),
    hospital_name     text NOT NULL CHECK (length(trim(hospital_name)) > 0),
    admission_date    date NOT NULL,
    discharge_date    date NOT NULL,
    claimed_amount    numeric(14, 2) NOT NULL CHECK (claimed_amount > 0),
    currency          char(3) NOT NULL,
    withdrawal_reason text CHECK (withdrawal_reason IN (
                          'CLAIMANT_REQUEST', 'DUPLICATE_ENTRY', 'ENTERED_IN_ERROR')),
    idempotency_key   uuid NOT NULL,
    created_by        uuid NOT NULL REFERENCES core.users (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    submitted_by      uuid REFERENCES core.users (id),
    submitted_at      timestamptz,
    version           bigint NOT NULL DEFAULT 0,
    UNIQUE (created_by, idempotency_key),
    CHECK (discharge_date >= admission_date),
    CHECK (status = 'DRAFT' OR status = 'WITHDRAWN' OR policy_version_id IS NOT NULL),
    CHECK ((status = 'WITHDRAWN') = (withdrawal_reason IS NOT NULL))
);

CREATE INDEX claims_status ON core.claims (status, id DESC);
CREATE INDEX claims_policy ON core.claims (policy_id);
