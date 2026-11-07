-- Policy knowledge lives in core, beside the review and audit that govern it (D-071). The AI service
-- stays stateless: it reads page batches, and core assembles, validates, and stores the result.

ALTER TABLE core.policy_versions
    ADD COLUMN knowledge_status text NOT NULL DEFAULT 'NO_WORDING' CHECK (knowledge_status IN (
        'NO_WORDING', 'PROCESSING', 'IN_REVIEW', 'PUBLISHED', 'REJECTED')),
    ADD COLUMN published_by uuid REFERENCES core.users (id),
    ADD COLUMN published_at timestamptz,
    ADD COLUMN rejection_reason text,
    ADD CHECK ((knowledge_status = 'PUBLISHED') = (published_at IS NOT NULL)),
    ADD CHECK ((knowledge_status = 'REJECTED') = (rejection_reason IS NOT NULL));

ALTER TABLE core.outbox_events DROP CONSTRAINT outbox_events_event_type_check;
ALTER TABLE core.outbox_events ADD CONSTRAINT outbox_events_event_type_check CHECK (event_type IN (
    'CLAIM_SUBMITTED', 'CLAIM_DOCUMENTS_CHANGED', 'CLAIM_RETRY_REQUESTED', 'CLAIM_WITHDRAWN',
    'POLICY_WORDING_ATTACHED', 'POLICY_INGESTION_RETRY_REQUESTED'));

-- Wordings uploaded before this milestone are ingested when the core service next starts.
UPDATE core.policy_versions v SET knowledge_status = 'PROCESSING'
WHERE EXISTS (SELECT 1 FROM core.documents d
              WHERE d.owner_type = 'POLICY_VERSION' AND d.owner_id = v.id AND d.status = 'ACTIVE');
INSERT INTO core.outbox_events (event_type, aggregate_id)
SELECT 'POLICY_WORDING_ATTACHED', d.id FROM core.documents d
WHERE d.owner_type = 'POLICY_VERSION' AND d.status = 'ACTIVE';

CREATE TABLE core.policy_ingestions (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    version_id   uuid NOT NULL REFERENCES core.policy_versions (id),
    document_id  uuid NOT NULL UNIQUE REFERENCES core.documents (id),
    status       text NOT NULL DEFAULT 'PROCESSING' CHECK (status IN ('PROCESSING', 'IN_REVIEW')),
    page_count   integer NOT NULL CHECK (page_count > 0),
    created_at   timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

-- Pages 0 to 0 stand for assembly, the step after every batch has been read.
CREATE TABLE core.policy_batch_events (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    ingestion_id uuid NOT NULL REFERENCES core.policy_ingestions (id),
    first_page   integer NOT NULL CHECK (first_page >= 0),
    last_page    integer NOT NULL,
    status       text NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    detail_code  text,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (last_page >= first_page)
);

CREATE INDEX policy_batch_events_ingestion ON core.policy_batch_events (ingestion_id, id DESC);

-- Raw model output per batch, kept so that any assembled provision can be traced to what was read.
CREATE TABLE core.policy_page_extractions (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    ingestion_id     uuid NOT NULL REFERENCES core.policy_ingestions (id),
    first_page       integer NOT NULL,
    last_page        integer NOT NULL,
    pipeline_version text NOT NULL,
    result           jsonb NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ingestion_id, first_page)
);

CREATE TABLE core.policy_provisions (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    ingestion_id  uuid NOT NULL REFERENCES core.policy_ingestions (id),
    position      integer NOT NULL,
    provision_key text,
    type          text NOT NULL CHECK (type IN (
                      'SCHEDULE', 'DEFINITION', 'BENEFIT', 'WAITING_PERIOD', 'EXCLUSION', 'PROCEDURE', 'CLAUSE')),
    title         text NOT NULL,
    text          text NOT NULL,
    page_start    integer NOT NULL,
    page_end      integer NOT NULL,
    quote         text NOT NULL,
    region        numeric(7, 5)[] CHECK (region IS NULL OR cardinality(region) = 4),
    grounding     text NOT NULL CHECK (grounding IN ('TEXT_MATCH', 'PAGE_ONLY', 'NONE')),
    text_verified boolean NOT NULL,
    parent_key    text,
    exceptions    text[] NOT NULL,
    UNIQUE (ingestion_id, position),
    CHECK (page_end >= page_start)
);

CREATE TABLE core.policy_values (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    provision_id uuid NOT NULL REFERENCES core.policy_provisions (id),
    position     integer NOT NULL,
    name         text NOT NULL,
    kind         text NOT NULL CHECK (kind IN ('AMOUNT', 'PERCENTAGE', 'DURATION', 'TEXT')),
    raw_value    text NOT NULL,
    value        text,
    -- The value as a number, for amounts, percentages, and durations. Money is never a float.
    number       numeric(16, 2),
    unit         text,
    qualifier    text,
    state        text NOT NULL CHECK (state IN ('PRESENT', 'UNCERTAIN')),
    page         integer,
    quote        text,
    region       numeric(7, 5)[] CHECK (region IS NULL OR cardinality(region) = 4),
    grounding    text NOT NULL CHECK (grounding IN ('TEXT_MATCH', 'PAGE_ONLY', 'NONE')),
    problem      text,
    critical     boolean NOT NULL,
    UNIQUE (provision_id, position),
    CHECK (state <> 'PRESENT' OR (value IS NOT NULL AND quote IS NOT NULL AND grounding = 'TEXT_MATCH')),
    CHECK (kind = 'TEXT' OR state <> 'PRESENT' OR number IS NOT NULL)
);

CREATE TABLE core.policy_relations (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    ingestion_id      uuid NOT NULL REFERENCES core.policy_ingestions (id),
    from_provision_id uuid NOT NULL REFERENCES core.policy_provisions (id),
    target_key        text NOT NULL,
    to_provision_id   uuid REFERENCES core.policy_provisions (id),
    relation          text NOT NULL CHECK (relation IN ('QUALIFIES', 'LIMITS', 'EXCEPTS', 'DEFINES', 'REFERENCES')),
    -- MODEL: proposed by the model. RULE: found by code, such as a defined term used in a clause.
    origin            text NOT NULL CHECK (origin IN ('MODEL', 'RULE')),
    stated_in_text    boolean NOT NULL
);

CREATE INDEX policy_relations_ingestion ON core.policy_relations (ingestion_id);

CREATE TABLE core.policy_findings (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    ingestion_id   uuid NOT NULL REFERENCES core.policy_ingestions (id),
    code           text NOT NULL,
    severity       text NOT NULL CHECK (severity IN ('ERROR', 'WARNING', 'INFO')),
    provision_id   uuid REFERENCES core.policy_provisions (id),
    value_id       uuid REFERENCES core.policy_values (id),
    expected_value text,
    actual_value   text
);

CREATE INDEX policy_findings_ingestion ON core.policy_findings (ingestion_id);

-- Human confirmation of critical values (D-030). Append-only: a later review supersedes an earlier one.
CREATE TABLE core.policy_value_reviews (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    value_id        uuid NOT NULL REFERENCES core.policy_values (id),
    decision        text NOT NULL CHECK (decision IN ('CONFIRMED', 'CORRECTED')),
    corrected_value text,
    note            text,
    reviewed_by     uuid NOT NULL REFERENCES core.users (id),
    reviewed_at     timestamptz NOT NULL DEFAULT now(),
    CHECK ((decision = 'CORRECTED') = (corrected_value IS NOT NULL)),
    CHECK (decision = 'CONFIRMED' OR length(trim(coalesce(note, ''))) > 0)
);

CREATE INDEX policy_value_reviews_value ON core.policy_value_reviews (value_id, id DESC);

-- A published or rejected version is immutable. The service refuses first; this holds even if it did not.
CREATE FUNCTION core.refuse_review_of_closed_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM core.policy_values v
        JOIN core.policy_provisions p ON p.id = v.provision_id
        JOIN core.policy_ingestions i ON i.id = p.ingestion_id
        JOIN core.policy_versions pv ON pv.id = i.version_id
        WHERE v.id = NEW.value_id AND pv.knowledge_status IN ('PUBLISHED', 'REJECTED')
    ) THEN
        RAISE EXCEPTION 'Policy version is closed to review' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER policy_value_reviews_open_versions_only
    BEFORE INSERT ON core.policy_value_reviews
    FOR EACH ROW EXECUTE FUNCTION core.refuse_review_of_closed_version();

REVOKE UPDATE, DELETE ON
    core.policy_batch_events, core.policy_page_extractions, core.policy_provisions, core.policy_values,
    core.policy_relations, core.policy_findings, core.policy_value_reviews
FROM prism_core_app;
REVOKE DELETE ON core.policy_ingestions FROM prism_core_app;
