-- Written in the same transaction as the change it announces, then relayed to Temporal (D-021).
-- Carries IDs only, so no claim content reaches workflow history (D-011).
CREATE TABLE core.outbox_events (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    event_type    text NOT NULL CHECK (event_type IN (
                      'CLAIM_SUBMITTED', 'CLAIM_DOCUMENTS_CHANGED', 'CLAIM_RETRY_REQUESTED', 'CLAIM_WITHDRAWN')),
    aggregate_id  uuid NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz,
    attempts      integer NOT NULL DEFAULT 0,
    last_failure  text
);

CREATE INDEX outbox_events_unpublished ON core.outbox_events (id) WHERE published_at IS NULL;

-- Screens read status here, so they keep working during a Temporal outage (D-038).
CREATE TABLE core.stage_events (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    claim_id     uuid NOT NULL REFERENCES core.claims (id),
    stage        text NOT NULL CHECK (stage IN ('DOCUMENT_EXTRACTION', 'CONSOLIDATION', 'VALIDATION')),
    document_id  uuid REFERENCES core.documents (id),
    status       text NOT NULL CHECK (status IN (
                     'NOT_STARTED', 'PROCESSING', 'COMPLETED', 'REQUIRES_REVIEW', 'FAILED')),
    detail_code  text,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    CHECK ((stage = 'DOCUMENT_EXTRACTION') = (document_id IS NOT NULL))
);

CREATE INDEX stage_events_claim ON core.stage_events (claim_id, id DESC);

REVOKE UPDATE, DELETE ON core.stage_events FROM prism_core_app;
