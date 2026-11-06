-- Runtime roles are provisioned outside migrations because they carry passwords.
-- Failing here is deliberate: running with missing roles would silently skip least privilege.
DO $$
DECLARE
    required_role text;
BEGIN
    FOREACH required_role IN ARRAY ARRAY['prism_core_app', 'prism_ai_app', 'prism_audit_reader'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = required_role) THEN
            RAISE EXCEPTION 'Required role % does not exist. Run scripts/setup_env.py first.', required_role;
        END IF;
    END LOOP;
END $$;

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS knowledge;

REVOKE ALL ON SCHEMA core FROM PUBLIC;
REVOKE ALL ON SCHEMA knowledge FROM PUBLIC;

GRANT USAGE ON SCHEMA core TO prism_core_app, prism_audit_reader;
GRANT USAGE ON SCHEMA knowledge TO prism_ai_app;

-- Default privileges apply to tables created later by the role running migrations.
ALTER DEFAULT PRIVILEGES IN SCHEMA core
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO prism_core_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA core
    GRANT USAGE, SELECT ON SEQUENCES TO prism_core_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA knowledge
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO prism_ai_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA knowledge
    GRANT USAGE, SELECT ON SEQUENCES TO prism_ai_app;
