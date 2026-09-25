-- Runtime role privileges for the AKIS worker on a PostgreSQL target database. Run as the ledger owner.
-- Replace :akis_runtime with the role AKIS connects as (psql: \set akis_runtime akis_app).
-- The runtime executes the ledger through its functions only; it never gets DML on the ledger tables.
DO $$
DECLARE ledger_function RECORD;
BEGIN
    FOR ledger_function IN
        SELECT p.oid::regprocedure::text AS identity
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'akis'
           AND p.proname IN (
               'kanit_degistirilemez', 'ozet_dogrula', 'kod_dogrula', 'sahip_dogrula',
               'cit_kilitle_ve_dogrula', 'cit_al', 'cit_oku', 'parti_imzasi',
               'parti_girdi_dogrula', 'parti_hazirla', 'parti_kaydet', 'parti_dogrula',
               'yayin_imzasi', 'yayin_girdi_dogrula', 'yayin_hazirla', 'yayin_kaydet',
               'yayin_dogrula')
    LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', ledger_function.identity);
    END LOOP;
END $$;

GRANT USAGE ON SCHEMA akis TO :akis_runtime;
GRANT SELECT ON akis.kurulum_kimligi TO :akis_runtime;
GRANT EXECUTE ON FUNCTION akis.cit_al(TEXT, BIGINT, UUID, UUID, INTEGER, TEXT, TEXT) TO :akis_runtime;
GRANT EXECUTE ON FUNCTION akis.cit_oku(TEXT) TO :akis_runtime;
GRANT EXECUTE ON FUNCTION akis.yayin_hazirla(TEXT, UUID, TEXT, TEXT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT, BIGINT, TEXT, TEXT) TO :akis_runtime;
GRANT EXECUTE ON FUNCTION akis.yayin_kaydet(TEXT, TEXT, UUID, TEXT, TEXT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT, BIGINT, TEXT, TEXT) TO :akis_runtime;
GRANT EXECUTE ON FUNCTION akis.yayin_dogrula(TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT, BIGINT, BIGINT, BIGINT, TEXT, TEXT) TO :akis_runtime;
GRANT EXECUTE ON FUNCTION akis.parti_hazirla(TEXT, UUID, TEXT, TEXT, TEXT, BIGINT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) TO :akis_runtime;
GRANT EXECUTE ON FUNCTION akis.parti_kaydet(TEXT, TEXT, UUID, TEXT, TEXT, TEXT, BIGINT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) TO :akis_runtime;
GRANT EXECUTE ON FUNCTION akis.parti_dogrula(TEXT, UUID, TEXT, TEXT, TEXT, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) TO :akis_runtime;
REVOKE ALL ON akis.hedef_citi, akis.yukleme_defteri, akis.yayin_defteri FROM :akis_runtime;
-- Ledger functions run with the definer's rights so the runtime needs no table DML.
ALTER FUNCTION akis.cit_al(TEXT, BIGINT, UUID, UUID, INTEGER, TEXT, TEXT) SECURITY DEFINER;
ALTER FUNCTION akis.cit_oku(TEXT) SECURITY DEFINER;
ALTER FUNCTION akis.yayin_hazirla(TEXT, UUID, TEXT, TEXT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT, BIGINT, TEXT, TEXT) SECURITY DEFINER;
ALTER FUNCTION akis.yayin_kaydet(TEXT, TEXT, UUID, TEXT, TEXT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT, BIGINT, TEXT, TEXT) SECURITY DEFINER;
ALTER FUNCTION akis.yayin_dogrula(TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT, BIGINT, BIGINT, BIGINT, TEXT, TEXT) SECURITY DEFINER;
ALTER FUNCTION akis.parti_hazirla(TEXT, UUID, TEXT, TEXT, TEXT, BIGINT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) SECURITY DEFINER;
ALTER FUNCTION akis.parti_kaydet(TEXT, TEXT, UUID, TEXT, TEXT, TEXT, BIGINT, UUID, INTEGER, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) SECURITY DEFINER;
ALTER FUNCTION akis.parti_dogrula(TEXT, UUID, TEXT, TEXT, TEXT, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) SECURITY DEFINER;
-- Target data privileges are per table and decided by the DBA (Faz A: SELECT, INSERT, TRUNCATE on published targets;
-- USAGE, CREATE on the work schema). No CREATE/ALTER/DROP on target tables in production.
