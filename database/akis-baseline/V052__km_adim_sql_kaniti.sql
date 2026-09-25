SET search_path TO akis, public;

ALTER TABLE km_step_journal
    ADD COLUMN calistirilan_sql JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE km_step_journal
    ADD CONSTRAINT ck_km_step_journal_calistirilan_sql
    CHECK (jsonb_typeof(calistirilan_sql) = 'array');

COMMENT ON COLUMN km_step_journal.calistirilan_sql IS
    'Çalıştırma anındaki sabitlenmiş plandan üretilen, adımla birlikte tarihsel olarak saklanan SQL kanıtları.';
