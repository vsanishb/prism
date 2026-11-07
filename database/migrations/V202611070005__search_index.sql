-- The search index for published policy versions (M5). Owned by the AI service, which builds it from
-- what core sends at publication (D-071). Provision IDs are core's, linked logically with no foreign
-- key, so that the service boundary stays intact.
-- In public, which every role can use: Flyway's default schema is core, which the AI role cannot read.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- One index per published version. A rebuild replaces it in a single transaction.
CREATE TABLE knowledge.search_indexes (
    version_id       uuid PRIMARY KEY,
    policy_id        uuid NOT NULL,
    embedding_model  text NOT NULL,
    dimensions       integer NOT NULL CHECK (dimensions = 1536),
    pipeline_version text NOT NULL,
    chunk_count      integer NOT NULL CHECK (chunk_count >= 0),
    indexed_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE knowledge.indexed_provisions (
    id           uuid PRIMARY KEY,
    version_id   uuid NOT NULL REFERENCES knowledge.search_indexes (version_id) ON DELETE CASCADE,
    provision_key text,
    type         text NOT NULL,
    title        text NOT NULL,
    text         text NOT NULL,
    page_start   integer NOT NULL,
    page_end     integer NOT NULL,
    section_path text[] NOT NULL
);

-- Defined terms are matched with pg_trgm word similarity, over one version's few provisions, so they
-- need no trigram index.
CREATE INDEX indexed_provisions_version ON knowledge.indexed_provisions (version_id, provision_key);

CREATE TABLE knowledge.indexed_relations (
    version_id        uuid NOT NULL REFERENCES knowledge.search_indexes (version_id) ON DELETE CASCADE,
    from_provision_id uuid NOT NULL REFERENCES knowledge.indexed_provisions (id) ON DELETE CASCADE,
    to_provision_id   uuid NOT NULL REFERENCES knowledge.indexed_provisions (id) ON DELETE CASCADE,
    relation          text NOT NULL CHECK (relation IN ('QUALIFIES', 'LIMITS', 'EXCEPTS', 'DEFINES', 'REFERENCES')),
    PRIMARY KEY (from_provision_id, to_provision_id, relation)
);

CREATE INDEX indexed_relations_to ON knowledge.indexed_relations (to_provision_id);

-- The stored body is the provision's text, verbatim. The heading carries its context: section path,
-- clause, and title. Both feed full-text search, under two configurations: `english` stems words,
-- and `simple` keeps clause numbers and codes as written (D-022).
CREATE TABLE knowledge.chunks (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    version_id      uuid NOT NULL REFERENCES knowledge.search_indexes (version_id) ON DELETE CASCADE,
    provision_id    uuid NOT NULL REFERENCES knowledge.indexed_provisions (id) ON DELETE CASCADE,
    part            integer NOT NULL CHECK (part > 0),
    parts           integer NOT NULL CHECK (parts >= part),
    heading         text NOT NULL,
    body            text NOT NULL,
    embedding       public.vector(1536) NOT NULL,
    embedding_model text NOT NULL,
    search_vector   tsvector GENERATED ALWAYS AS (
                        setweight(to_tsvector('english'::regconfig, heading), 'A')
                        || setweight(to_tsvector('simple'::regconfig, heading), 'B')
                        || setweight(to_tsvector('english'::regconfig, body), 'C')
                        || setweight(to_tsvector('simple'::regconfig, body), 'D')) STORED,
    UNIQUE (provision_id, part)
);

-- No vector index: every query is confined to one version, so an exact scan is fast and loses no
-- recall (D-024). The version filter itself is indexed.
CREATE INDEX chunks_version ON knowledge.chunks (version_id);
CREATE INDEX chunks_search_vector ON knowledge.chunks USING gin (search_vector);
