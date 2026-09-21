SET search_path TO akis, public;

-- Work tables are now named after the target (AKIS_C$_<HEDEF_TABLO>[_n]) instead of a hash, so the
-- registered name follows Oracle's 128-byte identifier limit (the runtime truncates for 30-byte hosts).
ALTER TABLE km_work_object ALTER COLUMN object_name TYPE VARCHAR(128);
