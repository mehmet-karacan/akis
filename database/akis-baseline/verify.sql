SET search_path TO akis, public;

DO $$
DECLARE
    actual BIGINT;
BEGIN
    SELECT COUNT(*) INTO actual
      FROM information_schema.tables
     WHERE table_schema = 'akis' AND table_type = 'BASE TABLE';
    IF actual <> 8 THEN
        RAISE EXCEPTION 'Expected 8 Akis tables, found %', actual;
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
VALUES ('00000000-0000-0000-0000-000000000001', 'Contract User');

INSERT INTO proje(id, kod, ad)
VALUES ('00000000-0000-0000-0000-000000000002', 'CONTRACT', 'Contract Project');

INSERT INTO proje_uyeligi(id, proje_id, kullanici_id)
VALUES (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001');

DO $$
DECLARE
    system_role_id UUID;
    project_role_id UUID;
BEGIN
    SELECT id INTO system_role_id FROM rol WHERE kod = 'SISTEM_YONETICISI';
    SELECT id INTO project_role_id FROM rol WHERE kod = 'GELISTIRICI';

    BEGIN
        INSERT INTO kullanici_rol(kullanici_id, rol_id, rol_kapsami, proje_id)
        VALUES (
            '00000000-0000-0000-0000-000000000001',
            system_role_id,
            'SISTEM',
            '00000000-0000-0000-0000-000000000002');
        RAISE EXCEPTION 'Proje kimliği taşıyan sistem rolü kabul edildi.';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO kullanici_rol(kullanici_id, rol_id, rol_kapsami)
        VALUES (
            '00000000-0000-0000-0000-000000000001',
            project_role_id,
            'PROJE');
        RAISE EXCEPTION 'Proje kimliği bulunmayan proje rolü kabul edildi.';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    INSERT INTO kullanici_rol(kullanici_id, rol_id, rol_kapsami)
    VALUES (
        '00000000-0000-0000-0000-000000000001',
        system_role_id,
        'SISTEM');

    INSERT INTO kullanici_rol(kullanici_id, rol_id, rol_kapsami, proje_id)
    VALUES (
        '00000000-0000-0000-0000-000000000001',
        project_role_id,
        'PROJE',
        '00000000-0000-0000-0000-000000000002');
END
$$;

ROLLBACK;

SELECT 'AKIS_BASELINE_OK' AS verification_result;
