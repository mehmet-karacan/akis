SET search_path TO akis, public;

-- "Devam Et": a resumed attempt of the same job publishes with the same publish key (that is what keeps the
-- target ledger idempotent across attempts), so the intent uniqueness must be per attempt, not per key.
-- One intent per run is still enforced by uq_pilot_yayin_niyeti_run.
ALTER TABLE pilot_yayin_niyeti DROP CONSTRAINT IF EXISTS uq_pilot_yayin_niyeti_operasyon;
ALTER TABLE pilot_yayin_niyeti ADD CONSTRAINT uq_pilot_yayin_niyeti_operasyon UNIQUE(hedef_kaynagi_id, yayin_anahtari_ozeti, deneme_no);
