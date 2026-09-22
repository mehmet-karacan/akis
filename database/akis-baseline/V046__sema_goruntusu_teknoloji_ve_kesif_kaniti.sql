SET search_path TO akis, public;

-- Schema snapshots now say which technology produced them and carry the discovery provenance separately from the
-- structural fingerprint: engine product/version and driver are evidence, not part of the hash, so a server patch
-- upgrade never invalidates a pinned snapshot. parmak_izi_surumu names the fingerprint algorithm (1 = engineVersion
-- constant + properties + columns + constraints, as before) so a later algorithm can coexist with pinned rows.
ALTER TABLE sema_goruntusu
    ADD COLUMN teknoloji_kodu VARCHAR(40) NOT NULL DEFAULT 'ORACLE',
    ADD COLUMN parmak_izi_surumu SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN kesif_kaniti JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_sema_goruntusu_teknoloji CHECK (teknoloji_kodu IN ('ORACLE', 'POSTGRESQL')),
    ADD CONSTRAINT ck_sema_goruntusu_parmak_izi_surumu CHECK (parmak_izi_surumu >= 1),
    ADD CONSTRAINT ck_sema_goruntusu_kesif_kaniti CHECK (jsonb_typeof(kesif_kaniti) = 'object');

-- Existing rows were all captured from Oracle 19c dictionaries; the column default records that. Snapshot rows are
-- immutable evidence (the catalog trigger rejects UPDATE), so no backfill statement runs here.

-- Physical schema, logical schema, connection and model must agree on the provider before a snapshot is pinned.
CREATE OR REPLACE FUNCTION sema_goruntusu_teknoloji_dogrula() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = akis, public AS $$
DECLARE v_saglayici TEXT;
BEGIN
    SELECT b.saglayici_turu INTO v_saglayici
      FROM fiziksel_sema fs JOIN baglanti b ON b.id = fs.baglanti_id
     WHERE fs.id = NEW.fiziksel_sema_id;
    IF v_saglayici IS NOT NULL AND v_saglayici <> NEW.teknoloji_kodu THEN
        RAISE EXCEPTION 'Şema görüntüsü teknolojisi (%) fiziksel şemanın bağlantı sağlayıcısıyla (%) uyuşmuyor.',
            NEW.teknoloji_kodu, v_saglayici USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_sema_goruntusu_teknoloji
    BEFORE INSERT OR UPDATE OF teknoloji_kodu, fiziksel_sema_id ON sema_goruntusu
    FOR EACH ROW EXECUTE FUNCTION sema_goruntusu_teknoloji_dogrula();
