-- One-time, data-preserving upgrade for targets that installed the ledger in akis_yayin_defteri.
-- Run as the owner of the legacy schema before the updated grant script.
DO $$
DECLARE
    nesne RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'akis_yayin_defteri') THEN
        CREATE SCHEMA IF NOT EXISTS akis;
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'akis') THEN
        ALTER SCHEMA akis_yayin_defteri RENAME TO akis;
    ELSE
        FOR nesne IN
            SELECT c.relkind, c.relname
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'akis_yayin_defteri'
               AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')
             ORDER BY CASE WHEN c.relkind = 'S' THEN 0 ELSE 1 END, c.relname
        LOOP
            IF to_regclass(format('akis.%I', nesne.relname)) IS NOT NULL THEN
                RAISE EXCEPTION 'akis.% already exists; ledger migration stopped without overwriting it', nesne.relname;
            END IF;
            EXECUTE CASE nesne.relkind
                WHEN 'S' THEN format('ALTER SEQUENCE akis_yayin_defteri.%I SET SCHEMA akis', nesne.relname)
                WHEN 'v' THEN format('ALTER VIEW akis_yayin_defteri.%I SET SCHEMA akis', nesne.relname)
                WHEN 'm' THEN format('ALTER MATERIALIZED VIEW akis_yayin_defteri.%I SET SCHEMA akis', nesne.relname)
                WHEN 'f' THEN format('ALTER FOREIGN TABLE akis_yayin_defteri.%I SET SCHEMA akis', nesne.relname)
                ELSE format('ALTER TABLE akis_yayin_defteri.%I SET SCHEMA akis', nesne.relname)
            END;
        END LOOP;

        FOR nesne IN
            SELECT p.oid::regprocedure::text AS eski_kimlik
              FROM pg_proc p
              JOIN pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = 'akis_yayin_defteri'
             ORDER BY p.oid
        LOOP
            EXECUTE format('ALTER FUNCTION %s SET SCHEMA akis', nesne.eski_kimlik);
        END LOOP;

        IF EXISTS (
            SELECT 1
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'akis_yayin_defteri'
        ) OR EXISTS (
            SELECT 1
              FROM pg_proc p
              JOIN pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = 'akis_yayin_defteri'
        ) THEN
            RAISE EXCEPTION 'Legacy schema still contains objects; refusing to drop it';
        END IF;
        DROP SCHEMA akis_yayin_defteri;
    END IF;

    FOR nesne IN
        SELECT p.proname, pg_get_function_identity_arguments(p.oid) AS argumanlar
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'akis'
           AND p.proname IN (
               'kanit_degistirilemez', 'ozet_dogrula', 'kod_dogrula', 'sahip_dogrula',
               'cit_kilitle_ve_dogrula', 'cit_al', 'cit_oku', 'parti_imzasi',
               'parti_girdi_dogrula', 'parti_hazirla', 'parti_kaydet', 'parti_dogrula', 'yayin_imzasi',
               'yayin_girdi_dogrula',
               'yayin_hazirla', 'yayin_kaydet', 'yayin_dogrula')
    LOOP
        EXECUTE format(
            'ALTER FUNCTION akis.%I(%s) SET search_path = akis, pg_catalog',
            nesne.proname,
            nesne.argumanlar);
    END LOOP;
END $$;
