CREATE TABLE core.users (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    -- Normalized to lowercase by the application and enforced here, so that uniqueness
    -- and lookups do not depend on how a driver binds parameters against citext.
    email          text NOT NULL UNIQUE CHECK (email = lower(email)),
    full_name       text NOT NULL CHECK (length(trim(full_name)) > 0),
    password_hash   text NOT NULL,
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DEACTIVATED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    last_sign_in_at timestamptz,
    version         bigint NOT NULL DEFAULT 0
);

CREATE TABLE core.user_roles (
    user_id    uuid NOT NULL REFERENCES core.users (id) ON DELETE CASCADE,
    role       text NOT NULL CHECK (role IN (
                   'ADMIN', 'POLICY_MANAGER', 'CLAIMS_PROCESSOR', 'REVIEWER',
                   'SENIOR_REVIEWER', 'FRAUD_INVESTIGATOR', 'AUDITOR')),
    PRIMARY KEY (user_id, role)
);

-- One table for invitation and password-reset links: both are single-use, expiring,
-- hash-only secrets with the same lifecycle.
CREATE TABLE core.account_tokens (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    purpose     text NOT NULL CHECK (purpose IN ('INVITATION', 'PASSWORD_RESET')),
    token_hash  bytea NOT NULL UNIQUE,
    email       text NOT NULL CHECK (email = lower(email)),
    full_name   text,
    roles       text[] NOT NULL DEFAULT '{}',
    user_id     uuid REFERENCES core.users (id) ON DELETE CASCADE,
    created_by  uuid REFERENCES core.users (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at  timestamptz,
    CHECK (purpose <> 'PASSWORD_RESET' OR user_id IS NOT NULL)
);

CREATE UNIQUE INDEX account_tokens_one_open_per_email
    ON core.account_tokens (purpose, email)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

-- Keys are HMACs of the account or client address, so this table holds no personal data.
CREATE TABLE core.login_throttle (
    throttle_key  text PRIMARY KEY,
    failures      integer NOT NULL DEFAULT 0,
    last_failure  timestamptz NOT NULL DEFAULT now(),
    blocked_until timestamptz
);

CREATE INDEX login_throttle_last_failure ON core.login_throttle (last_failure);
