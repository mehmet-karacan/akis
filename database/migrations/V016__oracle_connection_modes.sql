SET search_path TO entegrasyon, public;

ALTER TABLE baglanti_surumu
    ADD COLUMN baglanti_modu TEXT NOT NULL DEFAULT 'JDBC',
    ADD COLUMN jndi_adi TEXT;

-- Existing connection versions (including SKY and GPU) are JDBC definitions.
-- JNDI definitions deliberately carry no host, port or driver metadata: the
-- application server owns that configuration and the secret material.
ALTER TABLE baglanti_surumu
    ALTER COLUMN surucu_referansi DROP NOT NULL,
    ALTER COLUMN sunucu_adi DROP NOT NULL,
    ALTER COLUMN port DROP NOT NULL;

ALTER TABLE baglanti_surumu
    ADD CONSTRAINT ck_baglanti_surumu_modu
        CHECK (baglanti_modu IN ('JDBC', 'JNDI')),
    ADD CONSTRAINT ck_baglanti_surumu_mod_yapisi
        CHECK (
            (baglanti_modu = 'JDBC'
                AND surucu_referansi IS NOT NULL
                AND sunucu_adi IS NOT NULL
                AND port IS NOT NULL
                AND jndi_adi IS NULL)
            OR
            (baglanti_modu = 'JNDI'
                AND surucu_referansi IS NULL
                AND sunucu_adi IS NULL
                AND servis_adi IS NULL
                AND sid IS NULL
                AND veritabani_adi IS NULL
                AND port IS NULL
                AND tls_modu = 'DISABLED'
                AND jndi_adi ~ '^java:comp/env/jdbc/[A-Za-z0-9_.-]{1,180}$')
        );

CREATE INDEX ix_baglanti_surumu_modu
    ON baglanti_surumu(proje_id, baglanti_id, baglanti_modu);

CREATE OR REPLACE FUNCTION fn_baglanti_surumu_v2_dogrula()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_veritabani_turu TEXT;
BEGIN
    SELECT veritabani_turu
      INTO v_veritabani_turu
      FROM baglanti
     WHERE id = NEW.baglanti_id
       AND proje_id = NEW.proje_id;

    IF NEW.baglanti_modu = 'JNDI' AND v_veritabani_turu <> 'ORACLE' THEN
        RAISE EXCEPTION 'JNDI mode is supported only for Oracle connections.';
    END IF;

    IF NEW.baglanti_modu = 'JDBC' AND v_veritabani_turu = 'ORACLE'
       AND ((NEW.servis_adi IS NULL) = (NEW.sid IS NULL)
            OR NEW.veritabani_adi IS NOT NULL) THEN
        RAISE EXCEPTION 'Oracle JDBC requires exactly one of service name or SID.';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_baglanti_surumu_v2_dogrula
BEFORE INSERT OR UPDATE ON baglanti_surumu
FOR EACH ROW EXECUTE FUNCTION fn_baglanti_surumu_v2_dogrula();

CREATE OR REPLACE FUNCTION fn_baglanti_surumu_degistirilemez()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Connection versions are immutable.';
END;
$$;

CREATE TRIGGER tr_baglanti_surumu_degistirilemez
BEFORE UPDATE OR DELETE ON baglanti_surumu
FOR EACH ROW EXECUTE FUNCTION fn_baglanti_surumu_degistirilemez();

CREATE OR REPLACE FUNCTION fn_baglanti_secret_bagi_dogrula()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM baglanti_surumu
         WHERE id = NEW.baglanti_surumu_id
           AND proje_id = NEW.proje_id
           AND baglanti_modu = 'JNDI'
    ) THEN
        RAISE EXCEPTION 'JNDI connection versions cannot own secret bindings.';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_baglanti_secret_bagi_dogrula
BEFORE INSERT OR UPDATE ON baglanti_secret_bagi
FOR EACH ROW EXECUTE FUNCTION fn_baglanti_secret_bagi_dogrula();

CREATE TRIGGER tr_baglanti_secret_bagi_degistirilemez
BEFORE UPDATE OR DELETE ON baglanti_secret_bagi
FOR EACH ROW EXECUTE FUNCTION fn_baglanti_surumu_degistirilemez();
