SET search_path TO akis, public;

ALTER TABLE klasor
    DROP CONSTRAINT IF EXISTS ck_klasor_amac;

ALTER TABLE klasor
    DROP COLUMN IF EXISTS amac;
