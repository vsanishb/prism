CREATE TABLE core.documents (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    -- Owners are referenced logically: this module does not depend on the modules that own them.
    owner_type        text NOT NULL CHECK (owner_type IN ('CLAIM', 'POLICY_VERSION')),
    owner_id          uuid NOT NULL,
    document_type     text NOT NULL CHECK (document_type IN (
                          'CLAIM_FORM', 'HOSPITAL_BILL', 'ITEMIZED_INVOICE', 'DISCHARGE_SUMMARY',
                          'PRESCRIPTION', 'DIAGNOSTIC_REPORT', 'POLICY_WORDING', 'OTHER')),
    status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'REMOVED')),
    original_filename text NOT NULL,
    media_type        text NOT NULL CHECK (media_type IN (
                          'application/pdf', 'image/png', 'image/jpeg', 'image/tiff')),
    size_bytes        bigint NOT NULL CHECK (size_bytes > 0),
    page_count        integer NOT NULL CHECK (page_count > 0),
    sha256            bytea NOT NULL CHECK (length(sha256) = 32),
    storage_key       text NOT NULL UNIQUE,
    supersedes_id     uuid REFERENCES core.documents (id),
    uploaded_by       uuid NOT NULL REFERENCES core.users (id),
    uploaded_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX documents_owner ON core.documents (owner_type, owner_id, uploaded_at);
-- Supports duplicate detection across claims in M7.
CREATE INDEX documents_sha256 ON core.documents (sha256);

-- The same file twice on one owner is a mistake. Across owners it may be a risk signal, and M7
-- must be able to see it, so it is allowed there.
CREATE UNIQUE INDEX documents_one_active_copy_per_owner
    ON core.documents (owner_type, owner_id, sha256)
    WHERE status = 'ACTIVE';
