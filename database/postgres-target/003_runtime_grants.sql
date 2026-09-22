-- Runtime role privileges for the AKIS worker on a PostgreSQL target database. Run as the ledger owner.
-- Replace :akis_runtime with the role AKIS connects as (psql: \set akis_runtime akis_app).
-- The runtime executes the ledger through its functions only; it never gets DML on the ledger tables.
GRANT USAGE ON SCHEMA akis_yayin_defteri TO :akis_runtime;
GRANT SELECT ON akis_yayin_defteri.kurulum_kimligi TO :akis_runtime;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA akis_yayin_defteri TO :akis_runtime;
REVOKE ALL ON akis_yayin_defteri.hedef_citi, akis_yayin_defteri.yukleme_defteri, akis_yayin_defteri.yayin_defteri FROM :akis_runtime;
-- Ledger functions run with the definer's rights so the runtime needs no table DML.
ALTER FUNCTION akis_yayin_defteri.cit_al(TEXT, BIGINT, UUID, UUID, INTEGER, TEXT, TEXT) SECURITY DEFINER;
ALTER FUNCTION akis_yayin_defteri.cit_oku(TEXT) SECURITY DEFINER;
ALTER FUNCTION akis_yayin_defteri.yayin_hazirla(TEXT, UUID, TEXT, TEXT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT, BIGINT, TEXT, TEXT) SECURITY DEFINER;
ALTER FUNCTION akis_yayin_defteri.yayin_kaydet(TEXT, TEXT, UUID, TEXT, TEXT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT, BIGINT, TEXT, TEXT) SECURITY DEFINER;
ALTER FUNCTION akis_yayin_defteri.yayin_dogrula(TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT, BIGINT, BIGINT, BIGINT, TEXT, TEXT) SECURITY DEFINER;
ALTER FUNCTION akis_yayin_defteri.parti_hazirla(TEXT, UUID, TEXT, TEXT, TEXT, BIGINT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) SECURITY DEFINER;
ALTER FUNCTION akis_yayin_defteri.parti_kaydet(TEXT, TEXT, UUID, TEXT, TEXT, TEXT, BIGINT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) SECURITY DEFINER;
ALTER FUNCTION akis_yayin_defteri.parti_dogrula(TEXT, UUID, TEXT, TEXT, TEXT, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) SECURITY DEFINER;
-- Target data privileges are per table and decided by the DBA (Faz A: SELECT, INSERT, TRUNCATE on published targets;
-- USAGE, CREATE on the work schema). No CREATE/ALTER/DROP on target tables in production.
