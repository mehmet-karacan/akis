SET search_path TO akis, public;

DO $$
DECLARE
    actual BIGINT;
BEGIN
    SELECT count(*) INTO actual FROM kullanici;
    IF actual <> 1 THEN
        RAISE EXCEPTION 'Bootstrap exactly one user expected, found %.', actual;
    END IF;

    SELECT count(*) INTO actual
    FROM harici_kimlik
    WHERE saglayici_turu = 'YEREL'
      AND harici_kullanici_anahtari = 'bootstrap-admin';
    IF actual <> 1 THEN
        RAISE EXCEPTION 'Bootstrap exactly one local identity expected, found %.', actual;
    END IF;

    SELECT count(*) INTO actual
    FROM kullanici_rol kr
    JOIN rol r ON r.id = kr.rol_id
    WHERE r.kod = 'SISTEM_YONETICISI'
      AND kr.rol_kapsami = 'SISTEM'
      AND kr.proje_id IS NULL
      AND kr.iptal_zamani IS NULL;
    IF actual <> 1 THEN
        RAISE EXCEPTION 'Bootstrap exactly one active system administrator expected, found %.', actual;
    END IF;

    SELECT count(*) INTO actual FROM proje;
    IF actual <> 0 THEN
        RAISE EXCEPTION 'Bootstrap must not create a project.';
    END IF;
END
$$;

SELECT 'AKIS_BOOTSTRAP_OK' AS verification_result;
