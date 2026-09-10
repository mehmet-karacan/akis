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

    IF actual_table_count <> 55 THEN
        RAISE EXCEPTION 'Expected 55 metadata tables, found %', actual_table_count;
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

    IF (SELECT count(*) FROM public.flyway_schema_history WHERE success) <> 1 THEN
        RAISE EXCEPTION 'Flyway replay was not a no-op';
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
    IF (SELECT count(*) FROM entegrasyon.tanim) <> 9 THEN
        RAISE EXCEPTION 'Could not persist every required definition type';
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

INSERT INTO entegrasyon.tanim_surumu(tanim_id, surum_no, sema_surumu, icerik_ozeti, icerik)
SELECT id, 1, 1, encode(sha256(convert_to(kod, 'UTF8')), 'hex'), '{}'::jsonb
  FROM entegrasyon.tanim;

DO $$
BEGIN
    BEGIN
        UPDATE entegrasyon.tanim_surumu SET aciklama = 'değiştirilemez';
        RAISE EXCEPTION 'Immutable update trigger did not reject update';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM = 'Immutable update trigger did not reject update' THEN
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

    Write-Output "Metadata schema test: PASS (55 tables, 9 definition types)"
}
finally {
    & $docker exec $container dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
}
