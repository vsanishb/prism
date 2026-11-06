CREATE TABLE core.audit_events (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    stream         text NOT NULL,
    sequence       bigint NOT NULL CHECK (sequence > 0),
    occurred_at    timestamptz NOT NULL,
    actor_id       uuid,
    actor_roles    text[] NOT NULL DEFAULT '{}',
    action         text NOT NULL,
    target_type    text,
    target_id      text,
    outcome        text NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    correlation_id text,
    details        jsonb NOT NULL DEFAULT '{}',
    previous_hash  bytea NOT NULL,
    hash           bytea NOT NULL,
    UNIQUE (stream, sequence)
);

CREATE INDEX audit_events_occurred_at ON core.audit_events (occurred_at DESC, id DESC);
CREATE INDEX audit_events_actor ON core.audit_events (actor_id, occurred_at DESC);
CREATE INDEX audit_events_action ON core.audit_events (action, occurred_at DESC);
CREATE INDEX audit_events_target ON core.audit_events (target_type, target_id);

-- Grants are the primary control. The trigger also stops the owner role, so that
-- a mistaken migration or maintenance script cannot rewrite history unnoticed.
CREATE FUNCTION core.reject_audit_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only' USING ERRCODE = 'integrity_constraint_violation';
END $$;

CREATE TRIGGER audit_events_block_row_change
    BEFORE UPDATE OR DELETE ON core.audit_events
    FOR EACH ROW EXECUTE FUNCTION core.reject_audit_change();

CREATE TRIGGER audit_events_block_truncate
    BEFORE TRUNCATE ON core.audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION core.reject_audit_change();

REVOKE UPDATE, DELETE, TRUNCATE ON core.audit_events FROM prism_core_app;
GRANT SELECT ON core.audit_events TO prism_audit_reader;
