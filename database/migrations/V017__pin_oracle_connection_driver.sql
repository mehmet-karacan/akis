SET search_path TO entegrasyon, public;

CREATE OR REPLACE FUNCTION fn_baglanti_surumu_v2_dogrula()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_veritabani_turu TEXT;
BEGIN
    SELECT veritabani_turu
      INTO v_veritabani_turu
      FROM entegrasyon.baglanti
     WHERE id = NEW.baglanti_id
       AND proje_id = NEW.proje_id;

    IF NEW.baglanti_modu = 'JNDI' AND v_veritabani_turu <> 'ORACLE' THEN
        RAISE EXCEPTION 'JNDI mode is supported only for Oracle connections.';
    END IF;

    IF NEW.baglanti_modu = 'JDBC' AND v_veritabani_turu = 'ORACLE'
       AND ((NEW.servis_adi IS NULL) = (NEW.sid IS NULL)
            OR NEW.veritabani_adi IS NOT NULL
            OR NEW.surucu_referansi <> 'oracle.jdbc.OracleDriver') THEN
        RAISE EXCEPTION 'Oracle JDBC requires a pinned driver and exactly one of service name or SID.';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION entegrasyon.fn_baglanti_secret_bagi_dogrula()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM entegrasyon.baglanti_surumu
         WHERE id = NEW.baglanti_surumu_id
           AND proje_id = NEW.proje_id
           AND baglanti_modu = 'JNDI'
    ) THEN
        RAISE EXCEPTION 'JNDI connection versions cannot own secret bindings.';
    END IF;
    RETURN NEW;
END;
$$;
