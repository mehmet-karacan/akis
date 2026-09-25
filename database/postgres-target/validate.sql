-- Read-only validation of a PostgreSQL target ledger installation. Every row must report ok = true.
SELECT 'schema' AS kontrol, EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'akis') AS ok
UNION ALL SELECT 'legacy schema removed', NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'akis_yayin_defteri')
UNION ALL SELECT 'publication schema removed', NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'yayin')
UNION ALL SELECT 'installation identity', EXISTS (SELECT 1 FROM akis.kurulum_kimligi WHERE bilesen_kodu = 'AKIS_LEDGER')
UNION ALL SELECT 'tables', (SELECT count(*) = 4 FROM pg_tables WHERE schemaname = 'akis'
      AND tablename IN ('kurulum_kimligi', 'hedef_citi', 'yukleme_defteri', 'yayin_defteri'))
UNION ALL SELECT 'functions', (SELECT count(*) >= 8 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'akis' AND p.proname IN ('cit_al', 'cit_oku', 'yayin_hazirla', 'yayin_kaydet', 'yayin_dogrula', 'parti_hazirla', 'parti_kaydet', 'parti_dogrula'))
UNION ALL SELECT 'append-only triggers', (SELECT count(*) = 3 FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'akis' AND t.tgname LIKE 'trg_%_sabit');
