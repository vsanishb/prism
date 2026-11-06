-- Needed to combine equality on a UUID with a date-range overlap in one exclusion constraint.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE core.policies (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    policy_number     text NOT NULL UNIQUE CHECK (length(trim(policy_number)) > 0),
    product_name      text NOT NULL CHECK (length(trim(product_name)) > 0),
    policyholder_name text NOT NULL CHECK (length(trim(policyholder_name)) > 0),
    created_by        uuid NOT NULL REFERENCES core.users (id),
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE core.policy_versions (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    policy_id      uuid NOT NULL REFERENCES core.policies (id),
    version_number integer NOT NULL CHECK (version_number > 0),
    effective_from date NOT NULL,
    effective_to   date NOT NULL,
    sum_insured    numeric(14, 2) NOT NULL CHECK (sum_insured > 0),
    currency       char(3) NOT NULL,
    created_by     uuid NOT NULL REFERENCES core.users (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (policy_id, version_number),
    CHECK (effective_to >= effective_from),
    -- A treatment date must resolve to exactly one version, so periods may never overlap.
    CONSTRAINT policy_versions_no_overlap EXCLUDE USING gist (
        policy_id WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&
    )
);
