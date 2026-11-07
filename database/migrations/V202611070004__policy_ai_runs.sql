-- Model usage is recorded for policy ingestion as it is for claims, so that every paid call is on record.
ALTER TABLE core.ai_runs
    ALTER COLUMN claim_id DROP NOT NULL,
    ADD COLUMN ingestion_id uuid REFERENCES core.policy_ingestions (id),
    ADD CONSTRAINT ai_runs_one_subject CHECK ((claim_id IS NULL) <> (ingestion_id IS NULL));

CREATE INDEX ai_runs_ingestion ON core.ai_runs (ingestion_id);
