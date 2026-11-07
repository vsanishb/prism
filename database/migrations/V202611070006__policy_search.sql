-- Core's record of each published version's search index. The index itself is in the knowledge
-- schema, owned by the AI service. This tracks whether it is ready, so that search is refused until it is.
CREATE TABLE core.policy_search_indexes (
    version_id      uuid PRIMARY KEY REFERENCES core.policy_versions (id),
    status          text NOT NULL CHECK (status IN ('INDEXING', 'READY', 'FAILED')),
    chunk_count     integer CHECK (chunk_count >= 0),
    embedding_model text,
    failure_code    text,
    requested_at    timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    CHECK ((status = 'READY') = (completed_at IS NOT NULL AND chunk_count IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);

REVOKE DELETE ON core.policy_search_indexes FROM prism_core_app;

ALTER TABLE core.outbox_events DROP CONSTRAINT outbox_events_event_type_check;
ALTER TABLE core.outbox_events ADD CONSTRAINT outbox_events_event_type_check CHECK (event_type IN (
    'CLAIM_SUBMITTED', 'CLAIM_DOCUMENTS_CHANGED', 'CLAIM_RETRY_REQUESTED', 'CLAIM_WITHDRAWN',
    'POLICY_WORDING_ATTACHED', 'POLICY_INGESTION_RETRY_REQUESTED',
    'POLICY_VERSION_PUBLISHED', 'POLICY_INDEX_RETRY_REQUESTED'));

-- Indexing and searching a policy version are paid model calls too, so they are recorded with it.
ALTER TABLE core.ai_runs
    DROP CONSTRAINT ai_runs_one_subject,
    ALTER COLUMN document_id DROP NOT NULL,
    ADD COLUMN version_id uuid REFERENCES core.policy_versions (id),
    ADD CONSTRAINT ai_runs_one_subject CHECK (num_nonnulls(claim_id, ingestion_id, version_id) = 1),
    ADD CONSTRAINT ai_runs_document_known CHECK (version_id IS NOT NULL OR document_id IS NOT NULL);

CREATE INDEX ai_runs_version ON core.ai_runs (version_id);

-- Versions published before this milestone are indexed when the core service next starts.
INSERT INTO core.outbox_events (event_type, aggregate_id)
SELECT 'POLICY_VERSION_PUBLISHED', id FROM core.policy_versions WHERE knowledge_status = 'PUBLISHED';
