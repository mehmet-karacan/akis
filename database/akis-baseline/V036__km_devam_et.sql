SET search_path TO akis, public;

-- "Devam Et" (RESUME) for staged mappings: a new attempt adopts the sealed work table of
-- the failed attempt and skips the KM steps that already completed there.
ALTER TABLE km_step_journal DROP CONSTRAINT IF EXISTS km_step_journal_state_check;
ALTER TABLE km_step_journal ADD CONSTRAINT ck_km_step_journal_state
  CHECK(state IN('PENDING','RUNNING','SUCCEEDED','SKIPPED','FAILED','UNKNOWN'));

-- The physical work table is unique per database/owner/name, so adoption moves the
-- registry row to the resuming run instead of inserting a second one; the origin is kept for audit.
ALTER TABLE km_work_object
  ADD COLUMN devralinan_calistirma_id BIGINT REFERENCES calistirma(id),
  ADD COLUMN devralinma_zamani TIMESTAMPTZ;
