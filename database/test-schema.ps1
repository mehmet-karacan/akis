[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"
$migrationDirectory = Join-Path $PSScriptRoot "migrations"
$migration = Join-Path $migrationDirectory "V001__metadata_baseline.sql"
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$maven = Join-Path $projectRoot "mvnw.cmd"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env."
}
if (-not (Test-Path -LiteralPath $migration)) {
    throw "Missing metadata baseline migration."
}

$settings = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $settings[$name] = $value
    }

$container = (& $docker compose --env-file $envFile ps -q metadata-db).Trim()
if ([string]::IsNullOrWhiteSpace($container)) {
    throw "metadata-db container is not running. Run scripts\dev-up.ps1 first."
}

$databaseUser = $settings["POSTGRES_USER"]
$testDatabase = "akis_schema_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$cleanDatabase = $testDatabase + "_clean"

try {
    & $docker exec $container createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create temporary schema test database."
    }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings["POSTGRES_PASSWORD"]
    $env:SPRING_FLYWAY_ENABLED = "true"
    $env:SPRING_FLYWAY_LOCATIONS = "filesystem:" + $migrationDirectory.Replace('\', '/')
    $env:SPRING_MAIN_WEB_APPLICATION_TYPE = "none"

    $env:SPRING_FLYWAY_TARGET = "003"
    & $maven -q -pl backend spring-boot:run "-Dspring-boot.run.arguments=--spring.main.banner-mode=off"
    if ($LASTEXITCODE -ne 0) {
        throw "Flyway V003 upgrade fixture migration failed."
    }

    $preUpgradeSql = @'
INSERT INTO entegrasyon.proje(kod, ad) VALUES ('LEGACY', 'Legacy proje');
INSERT INTO entegrasyon.klasor(proje_id, kod, ad)
SELECT id, 'ROOT', 'Kök' FROM entegrasyon.proje WHERE kod = 'LEGACY';
INSERT INTO entegrasyon.tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
SELECT p.id, k.id, 'PROJE', 'MAPPING', 'LEGACY_MAP', 'Legacy Mapping'
  FROM entegrasyon.proje p
  JOIN entegrasyon.klasor k ON k.proje_id = p.id
 WHERE p.kod = 'LEGACY';
INSERT INTO entegrasyon.tanim_surumu(tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
SELECT id, 1, 1, encode(sha256(convert_to(kod, 'UTF8')), 'hex'), '{}'::jsonb
  FROM entegrasyon.tanim WHERE kod = 'LEGACY_MAP';
INSERT INTO entegrasyon.ortam(proje_id, kod, risk_kodu, ad)
SELECT id, 'LEGACY_TEST', 'DUSUK', 'Legacy test'
  FROM entegrasyon.proje WHERE kod = 'LEGACY';
INSERT INTO entegrasyon.dogrulama(tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
  FROM entegrasyon.tanim_surumu ts
  JOIN entegrasyon.tanim t ON t.id = ts.tanim_id AND t.kod = 'LEGACY_MAP'
  JOIN entegrasyon.ortam o ON o.proje_id = t.proje_id AND o.kod = 'LEGACY_TEST';
INSERT INTO entegrasyon.senaryo(
    tanim_surumu_id, dogrulama_id, surum_no, plan_surumu, plan_ozeti, plan)
SELECT d.tanim_surumu_id, d.id, 1, 1,
       encode(sha256(convert_to('legacy-plan', 'UTF8')), 'hex'), '{}'::jsonb
  FROM entegrasyon.dogrulama d;
INSERT INTO entegrasyon.yayin(
    proje_id, senaryo_id, ortam_id, yayin_no, durum_kodu,
    bagimlilik_ozeti, fiziksel_manifesto, yayin_zamani)
SELECT p.id, s.id, o.id, 1, 'AKTIF',
       encode(sha256(convert_to('legacy-dependencies', 'UTF8')), 'hex'),
       jsonb_build_object('releaseHash', encode(sha256(convert_to('legacy-release', 'UTF8')), 'hex')),
       current_timestamp
  FROM entegrasyon.proje p
  JOIN entegrasyon.ortam o ON o.proje_id = p.id AND o.kod = 'LEGACY_TEST'
  JOIN entegrasyon.tanim t ON t.proje_id = p.id AND t.kod = 'LEGACY_MAP'
  JOIN entegrasyon.tanim_surumu ts ON ts.tanim_id = t.id
  JOIN entegrasyon.senaryo s ON s.tanim_surumu_id = ts.id
 WHERE p.kod = 'LEGACY';
INSERT INTO entegrasyon.worker_profili(kod, capability, ad)
VALUES ('LEGACY_WORKER', '{}'::jsonb, 'Legacy worker');
INSERT INTO entegrasyon.is_talebi(proje_id, yayin_id, istek_ozeti, is_turu, parametre)
SELECT proje_id, id, digest, 'RUN', '{}'::jsonb
  FROM entegrasyon.yayin
 CROSS JOIN (VALUES
   (encode(sha256(convert_to('legacy-request-active', 'UTF8')), 'hex')),
   (encode(sha256(convert_to('legacy-request-unknown', 'UTF8')), 'hex'))
 ) AS request(digest);
INSERT INTO entegrasyon.calistirma(
    proje_id, is_talebi_id, deneme_no, plan_ozeti, baslatma_turu)
SELECT proje_id, id, 1, encode(sha256(convert_to('legacy-plan', 'UTF8')), 'hex'), 'ILK'
  FROM entegrasyon.is_talebi;
INSERT INTO entegrasyon.calistirma_durumu(
    proje_id, calistirma_id, worker_profili_id, isleyici_referansi,
    durum_kodu, son_olay_no, kiralama_bitis_zamani, yasam_sinyali_zamani,
    baslama_zamani, bitis_zamani)
SELECT c.proje_id, c.id,
       CASE WHEN i.istek_ozeti = encode(sha256(convert_to('legacy-request-active', 'UTF8')), 'hex')
            THEN w.id ELSE NULL END,
       CASE WHEN i.istek_ozeti = encode(sha256(convert_to('legacy-request-active', 'UTF8')), 'hex')
            THEN 'legacy-worker-1' ELSE NULL END,
       CASE WHEN i.istek_ozeti = encode(sha256(convert_to('legacy-request-active', 'UTF8')), 'hex')
            THEN 'SAHIPLENILDI' ELSE 'SONUCU_BILINMIYOR' END,
       1,
       CASE WHEN i.istek_ozeti = encode(sha256(convert_to('legacy-request-active', 'UTF8')), 'hex')
            THEN current_timestamp + interval '1 minute' ELSE NULL END,
       CASE WHEN i.istek_ozeti = encode(sha256(convert_to('legacy-request-active', 'UTF8')), 'hex')
            THEN current_timestamp ELSE NULL END,
       current_timestamp,
       CASE WHEN i.istek_ozeti = encode(sha256(convert_to('legacy-request-unknown', 'UTF8')), 'hex')
            THEN current_timestamp ELSE NULL END
  FROM entegrasyon.calistirma c
  JOIN entegrasyon.is_talebi i ON i.id = c.is_talebi_id
 CROSS JOIN entegrasyon.worker_profili w
 WHERE w.kod = 'LEGACY_WORKER';
INSERT INTO entegrasyon.calistirma_olayi(
    proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
SELECT proje_id, id, 1, 'LEGACY_STATE', current_timestamp, '{}'::jsonb
  FROM entegrasyon.calistirma;
'@
    $preUpgradeSql |
        & $docker exec -i $container psql -v ON_ERROR_STOP=1 -1 -U $databaseUser -d $testDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create V003 upgrade fixture."
    }

    Remove-Item Env:SPRING_FLYWAY_TARGET -ErrorAction SilentlyContinue
    for ($migrationRun = 1; $migrationRun -le 2; $migrationRun++) {
        & $maven -q -pl backend spring-boot:run "-Dspring-boot.run.arguments=--spring.main.banner-mode=off"
        if ($LASTEXITCODE -ne 0) {
            throw "Flyway migration run $migrationRun failed."
        }
    }

    $assertionSql = @'
DO $$
DECLARE
    actual_table_count INTEGER;
    actual_definition_types INTEGER;
BEGIN
    SELECT count(*) INTO actual_table_count
      FROM information_schema.tables
     WHERE table_schema = 'entegrasyon'
       AND table_type = 'BASE TABLE';

    IF actual_table_count <> 58 THEN
        RAISE EXCEPTION 'Expected 58 metadata tables, found %', actual_table_count;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM information_schema.tables t
         WHERE t.table_schema = 'entegrasyon'
           AND t.table_type = 'BASE TABLE'
           AND NOT EXISTS (
               SELECT 1 FROM information_schema.columns c
                WHERE c.table_schema = t.table_schema
                  AND c.table_name = t.table_name
                  AND c.column_name = 'id')
    ) THEN
        RAISE EXCEPTION 'A metadata table is missing the id column';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM information_schema.tables t
         WHERE t.table_schema = 'entegrasyon'
           AND t.table_type = 'BASE TABLE'
           AND NOT EXISTS (
               SELECT 1 FROM information_schema.columns c
                WHERE c.table_schema = t.table_schema
                  AND c.table_name = t.table_name
                  AND c.column_name = 'uuid')
    ) THEN
        RAISE EXCEPTION 'A metadata table is missing the uuid column';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM information_schema.tables t
         WHERE t.table_schema = 'entegrasyon'
           AND t.table_type = 'BASE TABLE'
           AND NOT EXISTS (
               SELECT 1 FROM information_schema.columns c
                WHERE c.table_schema = t.table_schema
                  AND c.table_name = t.table_name
                  AND c.column_name = 'olusturulma_zamani')
    ) THEN
        RAISE EXCEPTION 'A metadata table is missing the creation audit column';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'entegrasyon'
           AND column_name ~ '(parola|sifre|password|token|secret_degeri)'
    ) THEN
        RAISE EXCEPTION 'A forbidden cleartext secret column exists';
    END IF;

    IF (SELECT count(*) FROM public.flyway_schema_history WHERE success) <> 5 THEN
        RAISE EXCEPTION 'Flyway replay was not a no-op';
    END IF;

    IF (
        SELECT count(*) FROM entegrasyon.proje_rolu pr
        JOIN entegrasyon.proje p ON p.id = pr.proje_id
        WHERE p.kod = 'LEGACY'
    ) <> 4 THEN
        RAISE EXCEPTION 'Existing project role backfill is incomplete';
    END IF;

    IF (
        SELECT count(*) FROM entegrasyon.calistirma_durumu
         WHERE durum_kodu IN ('HAZIRLANIYOR', 'SONUC_BELIRSIZ')
    ) <> 2 OR EXISTS (
        SELECT 1 FROM entegrasyon.calistirma_durumu
         WHERE durum_kodu IN ('SAHIPLENILDI', 'SONUCU_BILINMIYOR')
    ) THEN
        RAISE EXCEPTION 'Legacy run states were not upgraded canonically';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM entegrasyon.calistirma c
          JOIN entegrasyon.is_talebi it
            ON it.proje_id = c.proje_id AND it.id = c.is_talebi_id
          JOIN entegrasyon.yayin y
            ON y.proje_id = it.proje_id AND y.id = it.yayin_id
          JOIN entegrasyon.senaryo s ON s.id = y.senaryo_id
         WHERE c.yayin_ozeti IS DISTINCT FROM y.release_hash
            OR c.plan_ozeti IS DISTINCT FROM s.plan_ozeti
    ) THEN
        RAISE EXCEPTION 'Legacy run release and plan hashes were not separated correctly';
    END IF;

    IF (SELECT count(*) FROM entegrasyon.yetki) <> 24 THEN
        RAISE EXCEPTION 'Security permission catalog is incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'fk_is_talebi_zamanlama'
           AND conrelid = 'entegrasyon.is_talebi'::regclass
           AND conkey = ARRAY[
               (SELECT attnum FROM pg_attribute WHERE attrelid = 'entegrasyon.is_talebi'::regclass AND attname = 'proje_id'),
               (SELECT attnum FROM pg_attribute WHERE attrelid = 'entegrasyon.is_talebi'::regclass AND attname = 'zamanlama_id')
           ]::smallint[]
    ) THEN
        RAISE EXCEPTION 'Project-scoped schedule foreign key is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'ck_calistirma_durumu_kod'
           AND position(quote_literal('HAZIRLANIYOR') in pg_get_constraintdef(oid)) > 0
           AND position(quote_literal('SONUC_BELIRSIZ') in pg_get_constraintdef(oid)) > 0
           AND position(quote_literal('SAHIPLENILDI') in pg_get_constraintdef(oid)) = 0
           AND position(quote_literal('SONUCU_BILINMIYOR') in pg_get_constraintdef(oid)) = 0
    ) THEN
        RAISE EXCEPTION 'Canonical run state catalog is incomplete';
    END IF;

    IF (
        SELECT count(*)
          FROM pg_indexes
         WHERE schemaname = 'entegrasyon'
           AND indexname IN (
               'ix_is_talebi_proje_olusturma', 'ix_is_talebi_claim_sirasi',
               'ix_calistirma_proje_is', 'ix_calistirma_durumu_proje_durum',
               'ix_calistirma_adimi_run_sira', 'ix_calistirma_gorevi_adim_sira')
    ) <> 6 THEN
        RAISE EXCEPTION 'Run query indexes are incomplete';
    END IF;

    IF position('SKIP LOCKED' in upper(pg_get_functiondef(
        'entegrasyon.calistirma_sahiplen(uuid,text,integer)'::regprocedure))) = 0
       OR position('CLOCK_TIMESTAMP' in upper(pg_get_functiondef(
        'entegrasyon.calistirma_sahiplen(uuid,text,integer)'::regprocedure))) = 0 THEN
        RAISE EXCEPTION 'Run claim must use SKIP LOCKED and database time';
    END IF;

    IF position('CLOCK_TIMESTAMP' in upper(pg_get_functiondef(
        'entegrasyon.calistirma_yasam_sinyali(uuid,text,bigint,integer)'::regprocedure))) = 0 THEN
        RAISE EXCEPTION 'Heartbeat must use database time';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'entegrasyon'
           AND table_name = 'yayin'
           AND column_name = 'release_hash'
           AND is_generated = 'ALWAYS'
    ) THEN
        RAISE EXCEPTION 'Publication release hash guard is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_yayin_release_hash'
           AND conrelid = 'entegrasyon.yayin'::regclass
           AND position('IS NOT NULL' in pg_get_constraintdef(oid)) > 0
    ) THEN
        RAISE EXCEPTION 'Publication release hash must be non-null';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
         WHERE tgname = 'tr_denetim_olayi_immutable_delete'
           AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'Append-only delete guards are missing';
    END IF;

    SELECT count(*) INTO actual_definition_types
      FROM (
        VALUES
          ('MAPPING'), ('REUSABLE_MAPPING'), ('PACKAGE'), ('PROCEDURE'),
          ('VARIABLE'), ('SEQUENCE'), ('USER_FUNCTION'),
          ('KNOWLEDGE_MODULE'), ('LOAD_PLAN')
      ) AS required_type(code)
     WHERE position(quote_literal(code) in pg_get_constraintdef(
          (SELECT oid FROM pg_constraint WHERE conname = 'ck_tanim_tur'))) > 0;

    IF actual_definition_types <> 9 THEN
        RAISE EXCEPTION 'Definition type catalog is incomplete';
    END IF;
END $$;

INSERT INTO entegrasyon.proje(kod, ad) VALUES ('P1', 'Proje 1');
INSERT INTO entegrasyon.proje(kod, ad) VALUES ('P2', 'Proje 2');
INSERT INTO entegrasyon.klasor(proje_id, kod, ad)
SELECT id, 'ROOT', 'Kök' FROM entegrasyon.proje WHERE kod = 'P1';
INSERT INTO entegrasyon.klasor(proje_id, kod, ad)
SELECT id, 'ROOT', 'Kök' FROM entegrasyon.proje WHERE kod = 'P2';

INSERT INTO entegrasyon.tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
SELECT p.id, k.id, 'PROJE', t.tur, t.kod, t.ad
  FROM entegrasyon.proje p
  JOIN entegrasyon.klasor k ON k.proje_id = p.id
 CROSS JOIN (VALUES
   ('MAPPING', 'MAP_1', 'Mapping'),
   ('PACKAGE', 'PKG_1', 'Paket'),
   ('PROCEDURE', 'PRC_1', 'Prosedür'),
   ('REUSABLE_MAPPING', 'RM_1', 'Yeniden kullanılabilir Mapping')
 ) AS t(tur, kod, ad)
 WHERE p.kod = 'P1';

INSERT INTO entegrasyon.tanim(proje_id, kapsam_kodu, tur_kodu, kod, ad)
SELECT p.id, 'PROJE', t.tur, t.kod, t.ad
  FROM entegrasyon.proje p
 CROSS JOIN (VALUES
   ('VARIABLE', 'VAR_1', 'Değişken'),
   ('SEQUENCE', 'SEQ_1', 'Sequence'),
   ('USER_FUNCTION', 'UF_1', 'Kullanıcı fonksiyonu'),
   ('KNOWLEDGE_MODULE', 'KM_1', 'Knowledge Module'),
   ('LOAD_PLAN', 'LP_1', 'Load Plan')
 ) AS t(tur, kod, ad)
 WHERE p.kod = 'P1';

DO $$
BEGIN
    IF (
        SELECT count(*) FROM entegrasyon.tanim t
        JOIN entegrasyon.proje p ON p.id = t.proje_id
        WHERE p.kod = 'P1'
    ) <> 9 THEN
        RAISE EXCEPTION 'Could not persist every required definition type';
    END IF;

    IF (SELECT count(*) FROM entegrasyon.proje_rolu) <> 12 THEN
        RAISE EXCEPTION 'Default project roles were not provisioned';
    END IF;

    IF (
        SELECT count(*)
          FROM entegrasyon.proje_rolu pr
          JOIN entegrasyon.proje_rolu_yetkisi pry ON pry.proje_rolu_id = pr.id
          JOIN entegrasyon.yetki y ON y.id = pry.yetki_id
         WHERE pr.kod = 'CALISTIRICI'
           AND y.kod IN ('RUN_READ', 'RUN_START', 'RUN_CANCEL')
    ) <> 9 THEN
        RAISE EXCEPTION 'Runner role permissions were not provisioned for every project';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM entegrasyon.proje_rolu_yetkisi pry
          JOIN entegrasyon.yetki y ON y.id = pry.yetki_id
         WHERE y.kod = 'PRODUCTION_RUN'
    ) THEN
        RAISE EXCEPTION 'Production run permission must not be granted to a default role';
    END IF;

    BEGIN
        INSERT INTO entegrasyon.tanim(kapsam_kodu, tur_kodu, kod, ad)
        VALUES ('GLOBAL', 'PACKAGE', 'INVALID_GLOBAL_PACKAGE', 'Geçersiz');
        RAISE EXCEPTION 'Global Package ownership constraint did not reject invalid row';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO entegrasyon.tanim(proje_id, klasor_id, kapsam_kodu, tur_kodu, kod, ad)
        SELECT p1.id, k2.id, 'PROJE', 'PACKAGE', 'CROSS_PROJECT', 'Geçersiz'
          FROM entegrasyon.proje p1
          JOIN entegrasyon.proje p2 ON p2.kod = 'P2'
          JOIN entegrasyon.klasor k2 ON k2.proje_id = p2.id
         WHERE p1.kod = 'P1';
        RAISE EXCEPTION 'Cross-project folder reference was not rejected';
    EXCEPTION WHEN foreign_key_violation THEN
        NULL;
    END;
END $$;

INSERT INTO entegrasyon.denetim_olayi(
    korelasyon_kodu, aktor_turu, eylem_kodu, sonuc_kodu, olay_zamani, ayrinti)
VALUES ('schema-test', 'SISTEM', 'TEST', 'BASARILI', current_timestamp, '{}'::jsonb);

DO $$
BEGIN
    BEGIN
        DELETE FROM entegrasyon.denetim_olayi WHERE korelasyon_kodu = 'schema-test';
        RAISE EXCEPTION 'Append-only delete trigger did not reject delete';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Append-only delete trigger did not reject delete' THEN
            RAISE;
        END IF;
    END;
END $$;

INSERT INTO entegrasyon.tanim_surumu(tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
SELECT id, 1, 1, encode(sha256(convert_to(kod, 'UTF8')), 'hex'), '{}'::jsonb
  FROM entegrasyon.tanim t
 WHERE NOT EXISTS (
       SELECT 1 FROM entegrasyon.tanim_surumu ts WHERE ts.tanim_id = t.id);

INSERT INTO entegrasyon.ortam(proje_id, kod, risk_kodu, ad)
SELECT id, 'TEST', 'DUSUK', 'Test' FROM entegrasyon.proje WHERE kod = 'P1';

INSERT INTO entegrasyon.dogrulama(tanim_surumu_id, ortam_id, icerik_ozeti, sonuc_kodu, sonuc)
SELECT ts.id, o.id, ts.icerik_ozeti, 'GECTI', '{}'::jsonb
  FROM entegrasyon.tanim_surumu ts
  JOIN entegrasyon.tanim t ON t.id = ts.tanim_id AND t.kod = 'MAP_1'
  JOIN entegrasyon.proje p ON p.id = t.proje_id AND p.kod = 'P1'
  JOIN entegrasyon.ortam o ON o.proje_id = p.id AND o.kod = 'TEST';

INSERT INTO entegrasyon.senaryo(
    tanim_surumu_id, dogrulama_id, surum_no, plan_surumu, plan_ozeti, plan)
SELECT d.tanim_surumu_id, d.id, 1, 1,
       encode(sha256(convert_to('schema-test-plan', 'UTF8')), 'hex'), '{}'::jsonb
  FROM entegrasyon.dogrulama d
  JOIN entegrasyon.tanim_surumu ts ON ts.id = d.tanim_surumu_id
  JOIN entegrasyon.tanim t ON t.id = ts.tanim_id AND t.kod = 'MAP_1';

INSERT INTO entegrasyon.yayin(
    proje_id, senaryo_id, ortam_id, yayin_no, durum_kodu,
    bagimlilik_ozeti, fiziksel_manifesto, yayin_zamani)
SELECT p.id, s.id, o.id, 1, 'AKTIF',
       encode(sha256(convert_to('schema-test-dependencies', 'UTF8')), 'hex'),
       jsonb_build_object('releaseHash', encode(sha256(convert_to('schema-test-release', 'UTF8')), 'hex')),
       current_timestamp
  FROM entegrasyon.proje p
  JOIN entegrasyon.ortam o ON o.proje_id = p.id AND o.kod = 'TEST'
  JOIN entegrasyon.tanim t ON t.proje_id = p.id AND t.kod = 'MAP_1'
  JOIN entegrasyon.tanim_surumu ts ON ts.tanim_id = t.id
  JOIN entegrasyon.senaryo s ON s.tanim_surumu_id = ts.id
 WHERE p.kod = 'P1';

INSERT INTO entegrasyon.is_talebi(
    proje_id, yayin_id, istek_ozeti, is_turu, parametre)
SELECT y.proje_id, y.id, encode(sha256(convert_to('schema-test-request', 'UTF8')), 'hex'),
       'RUN', '{}'::jsonb
  FROM entegrasyon.yayin y
  JOIN entegrasyon.proje p ON p.id = y.proje_id AND p.kod = 'P1';

INSERT INTO entegrasyon.calistirma(
    proje_id, is_talebi_id, deneme_no, yayin_ozeti, plan_ozeti, baslatma_turu)
SELECT i.proje_id, i.id, 1, y.release_hash, s.plan_ozeti, 'ILK'
  FROM entegrasyon.is_talebi i
  JOIN entegrasyon.yayin y ON y.proje_id = i.proje_id AND y.id = i.yayin_id
  JOIN entegrasyon.senaryo s ON s.id = y.senaryo_id
  JOIN entegrasyon.proje p ON p.id = i.proje_id AND p.kod = 'P1';

INSERT INTO entegrasyon.calistirma_durumu(
    proje_id, calistirma_id, durum_kodu, son_olay_no)
SELECT c.proje_id, c.id, 'BEKLIYOR', 1
  FROM entegrasyon.calistirma c
  JOIN entegrasyon.proje p ON p.id = c.proje_id AND p.kod = 'P1';

INSERT INTO entegrasyon.calistirma_olayi(
    proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
SELECT c.proje_id, c.id, 1, 'RUN_QUEUED', current_timestamp, '{}'::jsonb
  FROM entegrasyon.calistirma c
  JOIN entegrasyon.proje p ON p.id = c.proje_id AND p.kod = 'P1'
 WHERE NOT EXISTS (
       SELECT 1 FROM entegrasyon.calistirma_olayi co
        WHERE co.calistirma_id = c.id AND co.olay_no = 1);

INSERT INTO entegrasyon.calistirma_adimi(
    proje_id, calistirma_id, adim_kodu, tur_kodu, sira_no, ad)
SELECT c.proje_id, c.id, 'STEP_' || c.id, 'PREFLIGHT', 1, 'Legacy step ' || c.id
  FROM entegrasyon.calistirma c
  JOIN entegrasyon.proje p ON p.id = c.proje_id AND p.kod = 'LEGACY';

INSERT INTO entegrasyon.calistirma_gorevi(
    proje_id, calistirma_adimi_id, gorev_kodu, tur_kodu, sira_no, ad)
SELECT ca.proje_id, ca.id, 'TASK_1', 'CONTROL', 1, 'Legacy task'
  FROM entegrasyon.calistirma_adimi ca
 WHERE ca.calistirma_id = (
       SELECT min(c.id) FROM entegrasyon.calistirma c
       JOIN entegrasyon.proje p ON p.id = c.proje_id AND p.kod = 'LEGACY');

DO $$
BEGIN
    BEGIN
        INSERT INTO entegrasyon.calistirma_olayi(
            proje_id, calistirma_id, calistirma_adimi_id,
            olay_no, tur_kodu, olay_zamani, veri)
        SELECT c.proje_id, c.id, ca.id, 2, 'INVALID_STEP', current_timestamp, '{}'::jsonb
          FROM entegrasyon.calistirma c
          JOIN entegrasyon.proje p ON p.id = c.proje_id AND p.kod = 'LEGACY'
         CROSS JOIN entegrasyon.calistirma_adimi ca
         WHERE c.id = (SELECT max(id) FROM entegrasyon.calistirma WHERE proje_id = p.id)
           AND ca.calistirma_id = (SELECT min(id) FROM entegrasyon.calistirma WHERE proje_id = p.id);
        RAISE EXCEPTION 'Cross-run event step was accepted';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Cross-run event step was accepted' THEN
            RAISE;
        END IF;
    END;

    BEGIN
        INSERT INTO entegrasyon.calistirma_olayi(
            proje_id, calistirma_id, calistirma_adimi_id, calistirma_gorevi_id,
            olay_no, tur_kodu, olay_zamani, veri)
        SELECT c.proje_id, c.id, ca.id, cg.id, 2,
               'INVALID_TASK', current_timestamp, '{}'::jsonb
          FROM entegrasyon.calistirma c
          JOIN entegrasyon.proje p ON p.id = c.proje_id AND p.kod = 'LEGACY'
          JOIN entegrasyon.calistirma_adimi ca ON ca.calistirma_id = c.id
         CROSS JOIN entegrasyon.calistirma_gorevi cg
         WHERE c.id = (SELECT max(id) FROM entegrasyon.calistirma WHERE proje_id = p.id);
        RAISE EXCEPTION 'Cross-step event task was accepted';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Cross-step event task was accepted' THEN
            RAISE;
        END IF;
    END;

    BEGIN
        UPDATE entegrasyon.tanim_surumu SET aciklama = 'değiştirilemez';
        RAISE EXCEPTION 'Immutable update trigger did not reject update';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Immutable update trigger did not reject update' THEN
            RAISE;
        END IF;
    END;

    BEGIN
        UPDATE entegrasyon.is_talebi SET oncelik = 60;
        RAISE EXCEPTION 'Immutable job request trigger did not reject update';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Immutable job request trigger did not reject update' THEN
            RAISE;
        END IF;
    END;

    BEGIN
        UPDATE entegrasyon.calistirma_durumu
           SET durum_kodu = 'BASARILI', bitis_zamani = current_timestamp,
               versiyon_no = versiyon_no + 1
         WHERE proje_id = (SELECT id FROM entegrasyon.proje WHERE kod = 'P1');
        RAISE EXCEPTION 'Invalid run state transition was accepted';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Invalid run state transition was accepted' THEN
            RAISE;
        END IF;
    END;

    UPDATE entegrasyon.calistirma_durumu
       SET durum_kodu = 'IPTAL', bitis_zamani = current_timestamp,
           son_olay_no = 2, guncellenme_zamani = current_timestamp,
           versiyon_no = versiyon_no + 1
     WHERE proje_id = (SELECT id FROM entegrasyon.proje WHERE kod = 'P1');

    INSERT INTO entegrasyon.calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    SELECT proje_id, calistirma_id, 2, 'RUN_CANCELLED', current_timestamp, '{}'::jsonb
      FROM entegrasyon.calistirma_durumu
     WHERE proje_id = (SELECT id FROM entegrasyon.proje WHERE kod = 'P1');

    BEGIN
        UPDATE entegrasyon.calistirma_durumu
           SET versiyon_no = versiyon_no + 1
         WHERE proje_id = (SELECT id FROM entegrasyon.proje WHERE kod = 'P1');
        RAISE EXCEPTION 'Terminal run state was mutable';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Terminal run state was mutable' THEN
            RAISE;
        END IF;
    END;
END $$;
'@

    $assertionSql |
        & $docker exec -i $container psql -v ON_ERROR_STOP=1 -1 -U $databaseUser -d $testDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Metadata schema assertions failed."
    }

    $leaseSql = @'
SET search_path TO entegrasyon, public;

INSERT INTO worker_profili(kod, capability, ad)
VALUES ('CLAIM_WORKER', '{}'::jsonb, 'Claim test worker');

INSERT INTO is_talebi(
    proje_id, yayin_id, istek_ozeti, is_turu, oncelik, parametre)
SELECT p.id, NULL,
       encode(sha256(convert_to('non-run-request', 'UTF8')), 'hex'),
       'TEST', 100, '{}'::jsonb
  FROM proje p WHERE p.kod = 'P1';

INSERT INTO calistirma(
    proje_id, is_talebi_id, deneme_no, yayin_ozeti, plan_ozeti, baslatma_turu)
SELECT proje_id, id, 1, NULL,
       encode(sha256(convert_to('non-run-plan', 'UTF8')), 'hex'), 'ILK'
  FROM is_talebi
 WHERE istek_ozeti = encode(sha256(convert_to('non-run-request', 'UTF8')), 'hex');

INSERT INTO is_talebi(
    proje_id, yayin_id, istek_ozeti, is_turu, oncelik, parametre)
SELECT y.proje_id, y.id, request.digest, 'RUN', request.priority, '{}'::jsonb
  FROM yayin y
  JOIN proje p ON p.id = y.proje_id AND p.kod = 'P1'
 CROSS JOIN (VALUES
    (encode(sha256(convert_to('claim-run-a', 'UTF8')), 'hex'), 80),
    (encode(sha256(convert_to('claim-run-b', 'UTF8')), 'hex'), 70)
 ) AS request(digest, priority);

DO $$
BEGIN
    BEGIN
        INSERT INTO calistirma(
            proje_id, is_talebi_id, deneme_no, yayin_ozeti, plan_ozeti, baslatma_turu)
        SELECT it.proje_id, it.id, 1, NULL, s.plan_ozeti, 'ILK'
          FROM is_talebi it
          JOIN yayin y ON y.proje_id = it.proje_id AND y.id = it.yayin_id
          JOIN senaryo s ON s.id = y.senaryo_id
         WHERE it.istek_ozeti = encode(sha256(convert_to('claim-run-a', 'UTF8')), 'hex');
        RAISE EXCEPTION 'RUN without release hash was accepted';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'RUN without release hash was accepted' THEN
            RAISE;
        END IF;
    END;
END $$;

INSERT INTO calistirma(
    proje_id, is_talebi_id, deneme_no, yayin_ozeti, plan_ozeti, baslatma_turu)
SELECT it.proje_id, it.id, 1, y.release_hash, s.plan_ozeti, 'ILK'
  FROM is_talebi it
  JOIN yayin y ON y.proje_id = it.proje_id AND y.id = it.yayin_id
  JOIN senaryo s ON s.id = y.senaryo_id
 WHERE it.istek_ozeti IN (
    encode(sha256(convert_to('claim-run-a', 'UTF8')), 'hex'),
    encode(sha256(convert_to('claim-run-b', 'UTF8')), 'hex'));

INSERT INTO calistirma_durumu(proje_id, calistirma_id, durum_kodu, son_olay_no)
SELECT c.proje_id, c.id, 'BEKLIYOR', 1
  FROM calistirma c
  JOIN is_talebi it ON it.id = c.is_talebi_id
 WHERE it.istek_ozeti IN (
    encode(sha256(convert_to('non-run-request', 'UTF8')), 'hex'),
    encode(sha256(convert_to('claim-run-a', 'UTF8')), 'hex'),
    encode(sha256(convert_to('claim-run-b', 'UTF8')), 'hex'));

INSERT INTO calistirma_olayi(
    proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
SELECT c.proje_id, c.id, 1, 'RUN_QUEUED', clock_timestamp(), '{}'::jsonb
  FROM calistirma c
  JOIN is_talebi it ON it.id = c.is_talebi_id
 WHERE it.istek_ozeti IN (
    encode(sha256(convert_to('non-run-request', 'UTF8')), 'hex'),
    encode(sha256(convert_to('claim-run-a', 'UTF8')), 'hex'),
    encode(sha256(convert_to('claim-run-b', 'UTF8')), 'hex'));

CREATE TEMP TABLE first_claim AS
SELECT * FROM calistirma_sahiplen(
    (SELECT uuid FROM worker_profili WHERE kod = 'CLAIM_WORKER'),
    'schema-worker-a', 60);

DO $$
DECLARE
    v_run_uuid UUID;
    v_generation BIGINT;
    v_target_hash TEXT := encode(sha256(convert_to('oracle-target-identity', 'UTF8')), 'hex');
BEGIN
    SELECT calistirma_uuid, nesil_no INTO v_run_uuid, v_generation FROM first_claim;
    IF v_run_uuid IS NULL OR v_generation <> 1 THEN
        RAISE EXCEPTION 'RUN claim did not allocate generation one';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM calistirma c
          JOIN is_talebi it ON it.id = c.is_talebi_id
         WHERE c.uuid = v_run_uuid
           AND it.istek_ozeti = encode(sha256(convert_to('claim-run-a', 'UTF8')), 'hex')
    ) THEN
        RAISE EXCEPTION 'Claim ignored RUN priority ordering';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM calistirma_durumu cd
          JOIN calistirma c ON c.id = cd.calistirma_id
          JOIN is_talebi it ON it.id = c.is_talebi_id
         WHERE it.is_turu = 'TEST' AND cd.durum_kodu = 'BEKLIYOR'
    ) THEN
        RAISE EXCEPTION 'Non-RUN request was claimed by the execution worker';
    END IF;

    PERFORM * FROM hedef_kaynagi_sahiplen(
        v_run_uuid, 'schema-worker-a', v_generation, v_target_hash, 1);

    IF NOT calistirma_yasam_sinyali(
        v_run_uuid, 'schema-worker-a', v_generation, 60) THEN
        RAISE EXCEPTION 'Valid DB-time heartbeat was rejected';
    END IF;

    UPDATE hedef_kaynagi
       SET kiralama_bitis_zamani = clock_timestamp() - interval '1 second',
           guncellenme_zamani = clock_timestamp(),
           versiyon_no = versiyon_no + 1
     WHERE fiziksel_ozet = v_target_hash;

    UPDATE calistirma_durumu cd
       SET kiralama_bitis_zamani = clock_timestamp() - interval '1 second',
           guncellenme_zamani = clock_timestamp(),
           versiyon_no = cd.versiyon_no + 1
      FROM calistirma c
     WHERE c.id = cd.calistirma_id AND c.uuid = v_run_uuid;
END $$;

CREATE TEMP TABLE reaped_target AS
SELECT * FROM suresi_dolan_hedefleri_askiya_al(10);

DO $$
BEGIN
    IF (SELECT count(*) FROM reaped_target) <> 1
       OR NOT EXISTS (
           SELECT 1 FROM hedef_kaynagi
            WHERE fiziksel_ozet = encode(sha256(convert_to('oracle-target-identity', 'UTF8')), 'hex')
              AND durum_kodu = 'ASKIDA'
              AND calistirma_id IS NULL
              AND kiralama_bitis_zamani IS NULL
       ) OR NOT EXISTS (
           SELECT 1
             FROM calistirma_durumu cd
             JOIN calistirma c ON c.id = cd.calistirma_id
            WHERE c.uuid = (SELECT calistirma_uuid FROM first_claim)
              AND cd.durum_kodu = 'SONUC_BELIRSIZ'
       ) THEN
        RAISE EXCEPTION 'Expired target was not atomically suspended for reconciliation';
    END IF;
END $$;

CREATE TEMP TABLE second_claim AS
SELECT * FROM calistirma_sahiplen(
    (SELECT uuid FROM worker_profili WHERE kod = 'CLAIM_WORKER'),
    'schema-worker-b', 60);

DO $$
DECLARE
    v_run_uuid UUID;
    v_generation BIGINT;
    v_target_hash TEXT := encode(sha256(convert_to('oracle-target-identity', 'UTF8')), 'hex');
BEGIN
    SELECT calistirma_uuid, nesil_no INTO v_run_uuid, v_generation FROM second_claim;
    IF v_run_uuid IS NULL THEN
        RAISE EXCEPTION 'Second RUN could not be claimed';
    END IF;

    BEGIN
        PERFORM * FROM hedef_kaynagi_sahiplen(
            v_run_uuid, 'schema-worker-b', v_generation, v_target_hash, 1);
        RAISE EXCEPTION 'Expired target owner was directly preempted';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Expired target owner was directly preempted' THEN
            RAISE;
        END IF;
        IF position('askıdadır' in SQLERRM) = 0 THEN
            RAISE;
        END IF;
    END;

    BEGIN
        UPDATE hedef_kaynagi hk
           SET calistirma_id = c.id,
               nesil_no = hk.nesil_no + 1,
               kiralama_bitis_zamani = clock_timestamp() + interval '60 seconds',
               guncellenme_zamani = clock_timestamp(),
               versiyon_no = hk.versiyon_no + 1
          FROM calistirma c
         WHERE hk.fiziksel_ozet = v_target_hash AND c.uuid = v_run_uuid;
        RAISE EXCEPTION 'Direct target owner transfer was accepted';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Direct target owner transfer was accepted' THEN
            RAISE;
        END IF;
    END;
END $$;

UPDATE calistirma_durumu cd
   SET kiralama_bitis_zamani = clock_timestamp() - interval '1 second',
       guncellenme_zamani = clock_timestamp(),
       versiyon_no = cd.versiyon_no + 1
  FROM calistirma c
 WHERE c.id = cd.calistirma_id
   AND c.uuid = (SELECT calistirma_uuid FROM second_claim);

CREATE TEMP TABLE reaped_targetless_preparation AS
SELECT * FROM suresi_dolan_hedefsiz_hazirliklari_sonlandir(10);

DO $$
BEGIN
    IF (SELECT count(*) FROM reaped_targetless_preparation) <> 1
       OR NOT EXISTS (
           SELECT 1
             FROM calistirma_durumu cd
             JOIN calistirma c ON c.id = cd.calistirma_id
            WHERE c.uuid = (SELECT calistirma_uuid FROM second_claim)
              AND cd.durum_kodu = 'BASARISIZ'
              AND cd.hedef_kaynagi_id IS NULL
              AND cd.hedef_nesil_no IS NULL
              AND cd.kiralama_bitis_zamani IS NULL
              AND cd.bitis_zamani IS NOT NULL
              AND cd.nesil_no = (SELECT nesil_no FROM second_claim)
       ) OR NOT EXISTS (
           SELECT 1
             FROM calistirma_olayi co
             JOIN calistirma c ON c.id = co.calistirma_id
            WHERE c.uuid = (SELECT calistirma_uuid FROM second_claim)
              AND co.tur_kodu = 'PREPARATION_LEASE_EXPIRED'
              AND co.veri ->> 'requiresReconciliation' = 'false'
       ) THEN
        RAISE EXCEPTION 'Expired targetless preparation was not closed deterministically';
    END IF;
END $$;

INSERT INTO calistirma_adimi(
    proje_id, calistirma_id, adim_kodu, tur_kodu, sira_no, ad)
SELECT c.proje_id, c.id, 'TRANSFER_1', 'MAPPING', 1, 'Transfer checkpoint step'
  FROM calistirma c
 WHERE c.uuid = (SELECT calistirma_uuid FROM first_claim);

INSERT INTO kontrol_noktasi(
    proje_id, calistirma_id, calistirma_adimi_id,
    hedef_kaynagi_id, hedef_nesil_no,
    kapsam_ozeti, bolum_kodu, sira_no, paket_anahtari,
    hedef_defter_referansi, tur_kodu, dogrulama_zamani,
    yayin_ozeti, plan_ozeti, payload_ozeti, imlec)
SELECT c.proje_id, c.id, ca.id,
       cd.hedef_kaynagi_id, cd.hedef_nesil_no,
       encode(sha256(convert_to('checkpoint-scope', 'UTF8')), 'hex'),
       'FULL', 1,
       encode(sha256(convert_to('batch-key-1', 'UTF8')), 'hex'),
       'ETL_YUKLEME_DEFTERI:BATCH_1', 'BATCH', clock_timestamp(),
       c.yayin_ozeti, c.plan_ozeti,
       encode(sha256(convert_to('payload-1', 'UTF8')), 'hex'),
       jsonb_build_object('batch', 1)
  FROM calistirma c
  JOIN calistirma_durumu cd ON cd.calistirma_id = c.id
  JOIN calistirma_adimi ca ON ca.calistirma_id = c.id
 WHERE c.uuid = (SELECT calistirma_uuid FROM first_claim);

DO $$
BEGIN
    BEGIN
        UPDATE kontrol_noktasi SET imlec = jsonb_build_object('batch', 2)
         WHERE hedef_defter_referansi = 'ETL_YUKLEME_DEFTERI:BATCH_1';
        RAISE EXCEPTION 'Verified checkpoint update was accepted';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Verified checkpoint update was accepted' THEN
            RAISE;
        END IF;
    END;

    BEGIN
        DELETE FROM kontrol_noktasi
         WHERE hedef_defter_referansi = 'ETL_YUKLEME_DEFTERI:BATCH_1';
        RAISE EXCEPTION 'Verified checkpoint delete was accepted';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Verified checkpoint delete was accepted' THEN
            RAISE;
        END IF;
    END;
END $$;
'@

    $leaseSql |
        & $docker exec -i $container psql -v ON_ERROR_STOP=1 -1 -U $databaseUser -d $testDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Lease and fencing schema assertions failed."
    }

    & $docker exec $container createdb -U $databaseUser $cleanDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create clean migration test database."
    }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$cleanDatabase"
    & $maven -q -pl backend spring-boot:run "-Dspring-boot.run.arguments=--spring.main.banner-mode=off"
    if ($LASTEXITCODE -ne 0) {
        throw "Clean Flyway migration failed."
    }

    $cleanAssertion = @'
DO $$
BEGIN
    IF (SELECT count(*) FROM public.flyway_schema_history WHERE success) <> 5 THEN
        RAISE EXCEPTION 'Clean database did not apply all five migrations';
    END IF;
END $$;
'@
    $cleanAssertion |
        & $docker exec -i $container psql -v ON_ERROR_STOP=1 -U $databaseUser -d $cleanDatabase | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Clean migration assertions failed."
    }

    Write-Output "Metadata schema test: PASS (58 baseline tables, 9 definition types, 8 Flyway migrations, run, lease, publish-intent and RBAC guards)"
}
finally {
    & $docker exec $container dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
    & $docker exec $container dropdb --if-exists --force -U $databaseUser $cleanDatabase | Out-Null
}
