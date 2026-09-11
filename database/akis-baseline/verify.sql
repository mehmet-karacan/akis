SET search_path TO akis, public;

DO $$
DECLARE
    actual BIGINT;
    required_column TEXT;
BEGIN
    SELECT COUNT(*) INTO actual
      FROM information_schema.tables
     WHERE table_schema = 'akis' AND table_type = 'BASE TABLE';
    IF actual <> 8 THEN
        RAISE EXCEPTION 'Expected 8 Akis tables, found %', actual;
    END IF;

    FOREACH required_column IN ARRAY ARRAY[
        'id', 'uuid', 'olusturulma_zamani', 'olusturan_kullanici_id',
        'guncellenme_zamani', 'guncelleyen_kullanici_id', 'versiyon_no'
    ] LOOP
        SELECT COUNT(*) INTO actual
          FROM information_schema.columns
         WHERE table_schema = 'akis'
           AND column_name = required_column;
        IF actual <> 8 THEN
            RAISE EXCEPTION 'Ortak kolon % yalnız % tabloda bulundu.', required_column, actual;
        END IF;
    END LOOP;

    SELECT COUNT(*) INTO actual
      FROM information_schema.columns
     WHERE table_schema = 'akis'
       AND column_name = 'id'
       AND data_type = 'bigint'
       AND is_identity = 'YES';
    IF actual <> 8 THEN
        RAISE EXCEPTION 'Tüm id kolonları BIGINT identity olmalıdır; uygun tablo sayısı %.', actual;
    END IF;

    SELECT COUNT(*) INTO actual
      FROM information_schema.columns
     WHERE table_schema = 'akis'
       AND column_name = 'uuid'
       AND data_type = 'uuid'
       AND is_nullable = 'NO';
    IF actual <> 8 THEN
        RAISE EXCEPTION 'Tüm uuid kolonları zorunlu UUID olmalıdır; uygun tablo sayısı %.', actual;
    END IF;

    SELECT COUNT(*) INTO actual
      FROM information_schema.table_constraints tc
      JOIN information_schema.key_column_usage kcu
        ON kcu.constraint_schema = tc.constraint_schema
       AND kcu.constraint_name = tc.constraint_name
       AND kcu.table_name = tc.table_name
     WHERE tc.table_schema = 'akis'
       AND tc.constraint_type = 'UNIQUE'
       AND kcu.column_name = 'uuid';
    IF actual <> 8 THEN
        RAISE EXCEPTION 'Her tabloda uuid UNIQUE olmalıdır; uygun tablo sayısı %.', actual;
    END IF;

    SELECT COUNT(*) INTO actual
      FROM information_schema.columns
     WHERE table_schema = 'akis'
       AND column_name IN ('olusturan_kullanici_id', 'guncelleyen_kullanici_id')
       AND data_type = 'bigint';
    IF actual <> 16 THEN
        RAISE EXCEPTION 'Audit kullanıcı kolonları BIGINT olmalıdır; uygun kolon sayısı %.', actual;
    END IF;

    SELECT COUNT(*) INTO actual
      FROM information_schema.columns
     WHERE table_schema = 'akis'
       AND column_name = 'versiyon_no'
       AND data_type = 'bigint'
       AND is_nullable = 'NO'
       AND column_default = '1';
    IF actual <> 8 THEN
        RAISE EXCEPTION 'Her tabloda versiyon_no BIGINT NOT NULL DEFAULT 1 olmalıdır; uygun tablo sayısı %.', actual;
    END IF;

    SELECT COUNT(*) INTO actual FROM rol;
    IF actual <> 6 THEN
        RAISE EXCEPTION 'Expected 6 built-in roles, found %', actual;
    END IF;

    SELECT COUNT(*) INTO actual FROM yetki;
    IF actual <> 22 THEN
        RAISE EXCEPTION 'Expected 22 permissions, found %', actual;
    END IF;

    SELECT COUNT(*) INTO actual FROM kullanici;
    IF actual <> 0 THEN
        RAISE EXCEPTION 'Business identity data must start empty.';
    END IF;

    SELECT COUNT(*) INTO actual FROM proje;
    IF actual <> 0 THEN
        RAISE EXCEPTION 'Project data must start empty.';
    END IF;
END
$$;

BEGIN;

INSERT INTO kullanici(id, gorunen_ad)
VALUES (900001, 'Contract User');

INSERT INTO proje(id, kod, ad)
VALUES (900002, 'CONTRACT', 'Contract Project');

INSERT INTO proje_uyeligi(id, proje_id, kullanici_id)
VALUES (
    900003,
    900002,
    900001);

DO $$
DECLARE
    system_role_id BIGINT;
    project_role_id BIGINT;
BEGIN
    SELECT id INTO system_role_id FROM rol WHERE kod = 'SISTEM_YONETICISI';
    SELECT id INTO project_role_id FROM rol WHERE kod = 'GELISTIRICI';

    BEGIN
        INSERT INTO kullanici_rol(kullanici_id, rol_id, rol_kapsami, proje_id)
        VALUES (
            900001,
            system_role_id,
            'SISTEM',
            900002);
        RAISE EXCEPTION 'Proje kimliği taşıyan sistem rolü kabul edildi.';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO kullanici_rol(kullanici_id, rol_id, rol_kapsami)
        VALUES (
            900001,
            project_role_id,
            'PROJE');
        RAISE EXCEPTION 'Proje kimliği bulunmayan proje rolü kabul edildi.';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    INSERT INTO kullanici_rol(kullanici_id, rol_id, rol_kapsami)
    VALUES (
        900001,
        system_role_id,
        'SISTEM');

    INSERT INTO kullanici_rol(kullanici_id, rol_id, rol_kapsami, proje_id)
    VALUES (
        900001,
        project_role_id,
        'PROJE',
        900002);
END
$$;

ROLLBACK;

SELECT 'AKIS_BASELINE_OK' AS verification_result;
