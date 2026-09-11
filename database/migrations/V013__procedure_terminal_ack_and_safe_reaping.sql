SET search_path TO entegrasyon, public;

-- V012 pinned the target generation and physical identity. Complete that
-- fencing tuple with the target identity version while remaining upgrade-safe
-- for any V012 intent rows already present.
ALTER TABLE prosedur_adim_niyeti
    ADD COLUMN hedef_kimlik_surumu INTEGER;
DROP TRIGGER tr_prosedur_adim_niyeti_immutable ON prosedur_adim_niyeti;
UPDATE prosedur_adim_niyeti pan
   SET hedef_kimlik_surumu = hk.kimlik_surumu
  FROM hedef_kaynagi hk
 WHERE hk.id = pan.hedef_kaynagi_id;
ALTER TABLE prosedur_adim_niyeti
    ALTER COLUMN hedef_kimlik_surumu SET NOT NULL,
    ADD CONSTRAINT ck_prosedur_adim_niyeti_kimlik
        CHECK (hedef_kimlik_surumu > 0);
CREATE TRIGGER tr_prosedur_adim_niyeti_immutable
    BEFORE UPDATE OR DELETE ON prosedur_adim_niyeti
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();

CREATE FUNCTION prosedur_adim_niyeti_eklemeyi_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_calisma calistirma%ROWTYPE;
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_kanit prosedur_adim_kaniti%ROWTYPE;
    v_adim_durumu prosedur_adim_durumu%ROWTYPE;
BEGIN
    SELECT * INTO v_calisma FROM calistirma
     WHERE proje_id = NEW.proje_id AND id = NEW.calistirma_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Prosedür intent run kanıtı geçersiz'; END IF;
    -- Global lock order is run state, then target fence.
    SELECT * INTO v_durum FROM calistirma_durumu
     WHERE proje_id = NEW.proje_id AND calistirma_id = NEW.calistirma_id
     FOR UPDATE;
    IF NOT FOUND OR v_durum.durum_kodu <> 'CALISIYOR'
       OR v_durum.nesil_no <> NEW.calistirma_nesil_no
       OR v_durum.isleyici_referansi IS DISTINCT FROM NEW.isleyici_referansi
       OR v_durum.kiralama_bitis_zamani <= clock_timestamp()
       OR v_durum.hedef_kaynagi_id <> NEW.hedef_kaynagi_id
       OR v_durum.hedef_nesil_no <> NEW.hedef_nesil_no THEN
        RAISE EXCEPTION 'Prosedür intent aktif run lease ile eşleşmiyor';
    END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = NEW.hedef_kaynagi_id FOR UPDATE;
    IF NOT FOUND OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
       OR v_hedef.calistirma_id <> NEW.calistirma_id
       OR v_hedef.nesil_no <> NEW.hedef_nesil_no
       OR v_hedef.kiralama_bitis_zamani <= clock_timestamp()
       OR v_hedef.fiziksel_ozet <> NEW.hedef_fiziksel_ozeti
       OR (NEW.hedef_kimlik_surumu IS NOT NULL
           AND v_hedef.kimlik_surumu <> NEW.hedef_kimlik_surumu) THEN
        RAISE EXCEPTION 'Prosedür intent target fence ile eşleşmiyor';
    END IF;
    NEW.hedef_kimlik_surumu := v_hedef.kimlik_surumu;

    SELECT * INTO v_kanit FROM prosedur_adim_kaniti
     WHERE proje_id = NEW.proje_id
       AND calistirma_id = NEW.calistirma_id
       AND calistirma_adimi_id = NEW.calistirma_adimi_id
       AND id = NEW.prosedur_adim_kaniti_id;
    IF NOT FOUND OR v_kanit.risk_kodu = 'READ_ONLY'
       OR v_kanit.runtime_plan_ozeti <> NEW.runtime_plan_ozeti
       OR v_kanit.komut_ozeti <> NEW.komut_ozeti THEN
        RAISE EXCEPTION 'Prosedür intent step kanıtı geçersiz';
    END IF;
    SELECT * INTO v_adim_durumu FROM prosedur_adim_durumu
     WHERE prosedur_adim_kaniti_id = v_kanit.id FOR UPDATE;
    IF NOT FOUND OR v_adim_durumu.durum_kodu <> 'BEKLIYOR'
       OR NEW.yayin_ozeti <> v_calisma.yayin_ozeti
       OR NEW.plan_ozeti <> v_calisma.plan_ozeti
       OR NOT EXISTS (
            SELECT 1 FROM is_talebi it
            JOIN yayin y ON y.proje_id = it.proje_id AND y.id = it.yayin_id
             WHERE it.proje_id = NEW.proje_id
               AND it.id = v_calisma.is_talebi_id
               AND y.release_hash = NEW.yayin_ozeti
               AND y.fiziksel_manifesto ->> 'runtimeCapability'
                    = 'ORACLE_PROCEDURE_V1'
               AND y.fiziksel_manifesto ->> 'runtimePlanHash'
                    = NEW.runtime_plan_ozeti) THEN
        RAISE EXCEPTION 'Prosedür intent pinned execution ile eşleşmiyor';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_prosedur_adim_niyeti_insert
    BEFORE INSERT ON prosedur_adim_niyeti
    FOR EACH ROW EXECUTE FUNCTION prosedur_adim_niyeti_eklemeyi_dogrula();

ALTER TABLE prosedur_adim_durumu
    ADD CONSTRAINT ck_prosedur_basarili_adim_sayimlari
        CHECK (durum_kodu <> 'BASARILI'
               OR (satir_sayisi IS NOT NULL AND bayt_sayisi IS NOT NULL));

-- Keep V012 immutable while putting explicit NULL checks in front of its two
-- worker entry points. The renamed implementations retain their revoked
-- privileges; only the validated wrappers keep the public API names.
ALTER FUNCTION prosedur_adimini_baslat(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT)
    RENAME TO prosedur_adimini_baslat_v012;

CREATE FUNCTION prosedur_adimini_baslat(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT, p_adim_kodu TEXT,
    p_operasyon_anahtari_ozeti TEXT DEFAULT NULL)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
BEGIN
    IF p_operasyon_anahtari_ozeti IS NOT NULL
       AND p_operasyon_anahtari_ozeti !~ '^[0-9a-f]{64}$' THEN
        RETURN FALSE;
    END IF;
    IF p_operasyon_anahtari_ozeti IS NULL AND EXISTS (
        SELECT 1 FROM calistirma c
        JOIN prosedur_adim_kaniti pak ON pak.calistirma_id = c.id
        JOIN calistirma_adimi ca ON ca.id = pak.calistirma_adimi_id
         WHERE c.uuid = p_calistirma_uuid
           AND ca.adim_kodu = p_adim_kodu
           AND pak.risk_kodu <> 'READ_ONLY') THEN
        RETURN FALSE;
    END IF;
    RETURN prosedur_adimini_baslat_v012(
        p_calistirma_uuid, p_isleyici_referansi,
        p_calistirma_nesil_no, p_hedef_kaynagi_uuid,
        p_hedef_nesil_no, p_adim_kodu, p_operasyon_anahtari_ozeti);
END;
$$;

ALTER FUNCTION prosedur_adimini_basarili_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, BIGINT, BIGINT)
    RENAME TO prosedur_adimini_basarili_tamamla_v012;

CREATE FUNCTION prosedur_adimini_basarili_tamamla(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT, p_adim_kodu TEXT,
    p_satir_sayisi BIGINT, p_bayt_sayisi BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_kanit prosedur_adim_kaniti%ROWTYPE;
BEGIN
    IF p_satir_sayisi IS NULL OR p_bayt_sayisi IS NULL
       OR p_satir_sayisi < 0 OR p_bayt_sayisi < 0 THEN
        RAISE EXCEPTION 'Prosedür adım sayımları geçersiz';
    END IF;
    SELECT pak.* INTO v_kanit
      FROM calistirma c
      JOIN prosedur_adim_kaniti pak ON pak.calistirma_id = c.id
      JOIN calistirma_adimi ca ON ca.id = pak.calistirma_adimi_id
     WHERE c.uuid = p_calistirma_uuid
       AND ca.adim_kodu = p_adim_kodu;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    IF v_kanit.risk_kodu = 'READ_ONLY' THEN
        IF EXISTS (
            SELECT 1 FROM prosedur_adim_niyeti pan
             WHERE pan.calistirma_adimi_id = v_kanit.calistirma_adimi_id) THEN
            RETURN FALSE;
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1 FROM prosedur_adim_niyeti pan
        JOIN hedef_kaynagi hk ON hk.id = pan.hedef_kaynagi_id
         WHERE pan.calistirma_adimi_id = v_kanit.calistirma_adimi_id
           AND pan.calistirma_nesil_no = p_calistirma_nesil_no
           AND pan.isleyici_referansi = p_isleyici_referansi
           AND hk.uuid = p_hedef_kaynagi_uuid
           AND pan.hedef_nesil_no = p_hedef_nesil_no
           AND pan.hedef_nesil_no = hk.nesil_no
           AND pan.hedef_fiziksel_ozeti = hk.fiziksel_ozet
           AND pan.hedef_kimlik_surumu = hk.kimlik_surumu
           AND pan.runtime_plan_ozeti = v_kanit.runtime_plan_ozeti
           AND pan.komut_ozeti = v_kanit.komut_ozeti) THEN
        RETURN FALSE;
    END IF;
    RETURN prosedur_adimini_basarili_tamamla_v012(
        p_calistirma_uuid, p_isleyici_referansi,
        p_calistirma_nesil_no, p_hedef_kaynagi_uuid,
        p_hedef_nesil_no, p_adim_kodu, p_satir_sayisi, p_bayt_sayisi);
END;
$$;

CREATE FUNCTION prosedur_mutating_adim_gecisini_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_kanit prosedur_adim_kaniti%ROWTYPE;
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
BEGIN
    IF NOT ((OLD.durum_kodu = 'BEKLIYOR' AND NEW.durum_kodu = 'CALISIYOR')
            OR (OLD.durum_kodu = 'CALISIYOR'
                AND NEW.durum_kodu = 'BASARILI')) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO v_kanit FROM prosedur_adim_kaniti
     WHERE id = NEW.prosedur_adim_kaniti_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Prosedür step kanıtı bulunamadı'; END IF;
    IF v_kanit.risk_kodu = 'READ_ONLY' THEN
        IF EXISTS (
            SELECT 1 FROM prosedur_adim_niyeti pan
             WHERE pan.calistirma_adimi_id = NEW.calistirma_adimi_id) THEN
            RAISE EXCEPTION 'READ_ONLY prosedür adımı mutation intent içeremez';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO v_durum FROM calistirma_durumu
     WHERE proje_id = NEW.proje_id AND calistirma_id = NEW.calistirma_id;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id;
    IF v_durum.durum_kodu <> 'CALISIYOR'
       OR v_hedef.id IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM prosedur_adim_niyeti pan
             WHERE pan.proje_id = NEW.proje_id
               AND pan.calistirma_id = NEW.calistirma_id
               AND pan.calistirma_adimi_id = NEW.calistirma_adimi_id
               AND pan.prosedur_adim_kaniti_id = NEW.prosedur_adim_kaniti_id
               AND pan.calistirma_nesil_no = v_durum.nesil_no
               AND pan.isleyici_referansi = v_durum.isleyici_referansi
               AND pan.hedef_kaynagi_id = v_hedef.id
               AND pan.hedef_nesil_no = v_durum.hedef_nesil_no
               AND pan.hedef_nesil_no = v_hedef.nesil_no
               AND pan.hedef_fiziksel_ozeti = v_hedef.fiziksel_ozet
               AND pan.hedef_kimlik_surumu = v_hedef.kimlik_surumu
               AND pan.runtime_plan_ozeti = v_kanit.runtime_plan_ozeti
               AND pan.komut_ozeti = v_kanit.komut_ozeti) THEN
        RAISE EXCEPTION 'Mutating prosedür step geçişi exact intent gerektirir';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_prosedur_mutating_adim_niyet_guard
    BEFORE UPDATE ON prosedur_adim_durumu
    FOR EACH ROW EXECUTE FUNCTION prosedur_mutating_adim_gecisini_dogrula();

-- Procedure terminal events contain only bounded codes and pinned hashes. Raw
-- SQL, bind values, connection secrets and database error messages are never
-- accepted by these metadata functions.
CREATE FUNCTION prosedur_bekleyen_adimlari_atla(
    p_proje_id BIGINT, p_calistirma_id BIGINT, p_sira_no INTEGER,
    p_ilk_olay_no BIGINT, p_neden_kodu TEXT, p_olay_zamani TIMESTAMPTZ)
RETURNS BIGINT
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_adim RECORD;
    v_olay_no BIGINT := p_ilk_olay_no;
BEGIN
    IF p_neden_kodu NOT IN (
        'PREVIOUS_STEP_FAILED', 'OUTCOME_UNKNOWN',
        'LEASE_EXPIRED_AFTER_MUTATION') THEN
        RAISE EXCEPTION 'Desteklenmeyen prosedür atlama nedeni';
    END IF;

    FOR v_adim IN
        SELECT pad.id AS durum_id, pad.versiyon_no,
               pak.calistirma_adimi_id, pak.runtime_plan_ozeti,
               pak.komut_ozeti
          FROM prosedur_adim_kaniti pak
          JOIN prosedur_adim_durumu pad
            ON pad.prosedur_adim_kaniti_id = pak.id
         WHERE pak.proje_id = p_proje_id
           AND pak.calistirma_id = p_calistirma_id
           AND pak.sira_no > p_sira_no
           AND pad.durum_kodu = 'BEKLIYOR'
         ORDER BY pak.sira_no
         FOR UPDATE OF pad
    LOOP
        v_olay_no := v_olay_no + 1;
        UPDATE prosedur_adim_durumu
           SET durum_kodu = 'ATLANDI', son_olay_no = v_olay_no,
               bitis_zamani = p_olay_zamani,
               guncellenme_zamani = p_olay_zamani,
               versiyon_no = v_adim.versiyon_no + 1
         WHERE id = v_adim.durum_id;
        INSERT INTO calistirma_olayi(
            proje_id, calistirma_id, calistirma_adimi_id, olay_no,
            tur_kodu, olay_zamani, veri)
        VALUES (p_proje_id, p_calistirma_id,
                v_adim.calistirma_adimi_id, v_olay_no,
                'PROCEDURE_STEP_SKIPPED', p_olay_zamani,
                jsonb_build_object(
                    'reasonCode', p_neden_kodu,
                    'runtimePlanHash', v_adim.runtime_plan_ozeti,
                    'commandHash', v_adim.komut_ozeti));
    END LOOP;
    RETURN v_olay_no;
END;
$$;

-- Generic pilot finalizers must not bypass the procedure step journal once a
-- Procedure V1 run has been materialized.
CREATE FUNCTION prosedur_run_terminal_gecisini_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_prosedur BOOLEAN;
BEGIN
    IF NOT ((OLD.durum_kodu IN ('CALISIYOR', 'IPTAL_ISTENDI')
             AND NEW.durum_kodu IN (
                 'BASARILI', 'BASARISIZ', 'SONUC_BELIRSIZ'))
            OR (OLD.durum_kodu = 'SONUC_BELIRSIZ'
                AND NEW.durum_kodu = 'MUTABAKAT')) THEN
        RETURN NEW;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM calistirma c
        JOIN is_talebi it
          ON it.proje_id = c.proje_id AND it.id = c.is_talebi_id
        JOIN yayin y
          ON y.proje_id = it.proje_id AND y.id = it.yayin_id
         WHERE c.proje_id = NEW.proje_id AND c.id = NEW.calistirma_id
           AND y.fiziksel_manifesto ->> 'runtimeCapability'
                = 'ORACLE_PROCEDURE_V1'
           AND EXISTS (
                SELECT 1 FROM prosedur_adim_kaniti pak
                 WHERE pak.proje_id = NEW.proje_id
                   AND pak.calistirma_id = NEW.calistirma_id))
      INTO v_prosedur;
    IF NOT v_prosedur THEN RETURN NEW; END IF;

    IF OLD.durum_kodu = 'SONUC_BELIRSIZ'
       AND NEW.durum_kodu = 'MUTABAKAT' THEN
        RAISE EXCEPTION 'Procedure V1 belirsiz sonucu otomatik pilot mutabakatına alınamaz';
    END IF;

    IF NEW.durum_kodu = 'BASARILI' AND NOT EXISTS (
        SELECT 1 FROM prosedur_adim_durumu pad
         WHERE pad.proje_id = NEW.proje_id
           AND pad.calistirma_id = NEW.calistirma_id
           AND pad.durum_kodu NOT IN ('BASARILI', 'HATA_DEVAM')) THEN
        RETURN NEW;
    END IF;
    IF NEW.durum_kodu = 'BASARISIZ'
       AND (EXISTS (
                SELECT 1 FROM prosedur_adim_durumu pad
                 WHERE pad.proje_id = NEW.proje_id
                   AND pad.calistirma_id = NEW.calistirma_id
                   AND pad.durum_kodu = 'BASARISIZ')
            OR NOT EXISTS (
                SELECT 1 FROM prosedur_adim_niyeti pan
                 WHERE pan.proje_id = NEW.proje_id
                   AND pan.calistirma_id = NEW.calistirma_id))
       AND NOT EXISTS (
            SELECT 1 FROM prosedur_adim_durumu pad
             WHERE pad.proje_id = NEW.proje_id
               AND pad.calistirma_id = NEW.calistirma_id
               AND pad.durum_kodu IN ('BEKLIYOR', 'CALISIYOR')) THEN
        RETURN NEW;
    END IF;
    IF NEW.durum_kodu = 'SONUC_BELIRSIZ'
       AND (EXISTS (
                SELECT 1 FROM prosedur_adim_durumu pad
                 WHERE pad.proje_id = NEW.proje_id
                   AND pad.calistirma_id = NEW.calistirma_id
                   AND pad.durum_kodu = 'SONUC_BELIRSIZ')
            OR EXISTS (
                SELECT 1 FROM prosedur_adim_niyeti pan
                 WHERE pan.proje_id = NEW.proje_id
                   AND pan.calistirma_id = NEW.calistirma_id))
       AND NOT EXISTS (
            SELECT 1 FROM prosedur_adim_durumu pad
             WHERE pad.proje_id = NEW.proje_id
               AND pad.calistirma_id = NEW.calistirma_id
               AND pad.durum_kodu IN ('BEKLIYOR', 'CALISIYOR')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Procedure V1 terminal geçişi exact step journal gerektirir';
END;
$$;

CREATE TRIGGER tr_prosedur_run_terminal_guard
    BEFORE UPDATE ON calistirma_durumu
    FOR EACH ROW EXECUTE FUNCTION prosedur_run_terminal_gecisini_dogrula();

CREATE FUNCTION prosedur_adimini_basarisiz_tamamla(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT, p_adim_kodu TEXT, p_hata_kodu TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_calisma calistirma%ROWTYPE;
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_kanit prosedur_adim_kaniti%ROWTYPE;
    v_adim_durumu prosedur_adim_durumu%ROWTYPE;
    v_yeni_durum TEXT;
    v_simdi TIMESTAMPTZ := clock_timestamp();
    v_olay_no BIGINT;
BEGIN
    IF p_hata_kodu IS NULL
       OR p_hata_kodu !~ '^[A-Z0-9][A-Z0-9_.:-]{0,99}$' THEN
        RAISE EXCEPTION 'Prosedür hata kodu geçersiz';
    END IF;
    SELECT * INTO v_calisma FROM calistirma WHERE uuid = p_calistirma_uuid;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_durum FROM calistirma_durumu
     WHERE proje_id = v_calisma.proje_id AND calistirma_id = v_calisma.id
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id AND uuid = p_hedef_kaynagi_uuid
     FOR UPDATE;
    SELECT pak.* INTO v_kanit FROM prosedur_adim_kaniti pak
      JOIN calistirma_adimi ca ON ca.id = pak.calistirma_adimi_id
     WHERE pak.proje_id = v_calisma.proje_id
       AND pak.calistirma_id = v_calisma.id
       AND ca.adim_kodu = p_adim_kodu;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_adim_durumu FROM prosedur_adim_durumu
     WHERE prosedur_adim_kaniti_id = v_kanit.id FOR UPDATE;
    v_yeni_durum := CASE v_kanit.hata_politikasi
        WHEN 'CONTINUE' THEN 'HATA_DEVAM' ELSE 'BASARISIZ' END;

    IF v_yeni_durum = 'BASARISIZ'
       AND v_durum.durum_kodu = 'BASARISIZ' THEN
        RETURN COALESCE((
            v_durum.isleyici_referansi = p_isleyici_referansi
            AND v_durum.nesil_no = p_calistirma_nesil_no
            AND v_durum.hedef_nesil_no = p_hedef_nesil_no
            AND v_durum.kiralama_bitis_zamani IS NULL
            AND v_hedef.uuid = p_hedef_kaynagi_uuid
            AND v_hedef.durum_kodu = 'BOS'
            AND v_hedef.nesil_no = p_hedef_nesil_no
            AND v_adim_durumu.durum_kodu = 'BASARISIZ'
            AND v_adim_durumu.hata_kodu = p_hata_kodu
            AND EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_calisma.proje_id
                   AND co.calistirma_id = v_calisma.id
                   AND co.olay_no = v_durum.son_olay_no
                   AND co.tur_kodu = 'PROCEDURE_RUN_FAILED'
                   AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
                   AND co.veri ->> 'workerReference' = p_isleyici_referansi
                   AND co.veri ->> 'targetGeneration' = p_hedef_nesil_no::TEXT
                   AND co.veri ->> 'runtimePlanHash' = v_kanit.runtime_plan_ozeti
                   AND co.veri ->> 'failedStepCode' = p_adim_kodu
                   AND co.veri ->> 'errorCode' = p_hata_kodu)), FALSE);
    END IF;

    IF v_adim_durumu.durum_kodu = v_yeni_durum THEN
        RETURN COALESCE((
            v_durum.durum_kodu = 'CALISIYOR'
            AND v_durum.isleyici_referansi = p_isleyici_referansi
            AND v_durum.nesil_no = p_calistirma_nesil_no
            AND v_durum.hedef_nesil_no = p_hedef_nesil_no
            AND v_durum.kiralama_bitis_zamani > v_simdi
            AND v_hedef.durum_kodu = 'SAHIPLENILDI'
            AND v_hedef.calistirma_id = v_calisma.id
            AND v_hedef.nesil_no = p_hedef_nesil_no
            AND v_hedef.kiralama_bitis_zamani > v_simdi
            AND v_adim_durumu.hata_kodu = p_hata_kodu
            AND EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_calisma.proje_id
                   AND co.calistirma_id = v_calisma.id
                   AND co.calistirma_adimi_id = v_kanit.calistirma_adimi_id
                   AND co.olay_no = v_adim_durumu.son_olay_no
                   AND co.tur_kodu = 'PROCEDURE_STEP_FAILED_SAFE'
                   AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
                   AND co.veri ->> 'workerReference' = p_isleyici_referansi
                   AND co.veri ->> 'targetGeneration' = p_hedef_nesil_no::TEXT
                   AND co.veri ->> 'errorCode' = p_hata_kodu
                   AND co.veri ->> 'stepState' = v_yeni_durum
                   AND co.veri ->> 'rollbackConfirmed' = 'true')), FALSE);
    END IF;

    IF v_durum.durum_kodu <> 'CALISIYOR'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.hedef_nesil_no <> p_hedef_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_hedef.id IS NULL OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
       OR v_hedef.calistirma_id <> v_calisma.id
       OR v_hedef.nesil_no <> p_hedef_nesil_no
       OR v_hedef.kiralama_bitis_zamani <= v_simdi
       OR v_adim_durumu.durum_kodu <> 'CALISIYOR' THEN
        RETURN FALSE;
    END IF;
    IF v_kanit.risk_kodu <> 'READ_ONLY' AND NOT EXISTS (
        SELECT 1 FROM prosedur_adim_niyeti pan
         WHERE pan.calistirma_adimi_id = v_kanit.calistirma_adimi_id
           AND pan.calistirma_nesil_no = p_calistirma_nesil_no
           AND pan.isleyici_referansi = p_isleyici_referansi
           AND pan.hedef_kaynagi_id = v_hedef.id
           AND pan.hedef_nesil_no = p_hedef_nesil_no
           AND pan.hedef_fiziksel_ozeti = v_hedef.fiziksel_ozet
           AND pan.hedef_kimlik_surumu = v_hedef.kimlik_surumu
           AND pan.runtime_plan_ozeti = v_kanit.runtime_plan_ozeti
           AND pan.komut_ozeti = v_kanit.komut_ozeti) THEN
        RETURN FALSE;
    END IF;

    UPDATE prosedur_adim_durumu
       SET durum_kodu = v_yeni_durum,
           son_olay_no = v_durum.son_olay_no + 1,
           bitis_zamani = v_simdi, hata_kodu = p_hata_kodu,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_adim_durumu.versiyon_no + 1
     WHERE id = v_adim_durumu.id;
    UPDATE calistirma_durumu
       SET son_olay_no = v_durum.son_olay_no + 1,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, calistirma_adimi_id, olay_no,
        tur_kodu, olay_zamani, veri)
    VALUES (v_calisma.proje_id, v_calisma.id,
            v_kanit.calistirma_adimi_id, v_durum.son_olay_no + 1,
            'PROCEDURE_STEP_FAILED_SAFE', v_simdi,
            jsonb_build_object(
                'generation', p_calistirma_nesil_no,
                'workerReference', p_isleyici_referansi,
                'targetResourceUuid', p_hedef_kaynagi_uuid,
                'targetGeneration', p_hedef_nesil_no,
                'runtimePlanHash', v_kanit.runtime_plan_ozeti,
                'commandHash', v_kanit.komut_ozeti,
                'errorCode', p_hata_kodu,
                'stepState', v_yeni_durum,
                'rollbackConfirmed', TRUE));

    IF v_yeni_durum = 'BASARISIZ' THEN
        v_olay_no := prosedur_bekleyen_adimlari_atla(
            v_calisma.proje_id, v_calisma.id, v_kanit.sira_no,
            v_durum.son_olay_no + 1, 'PREVIOUS_STEP_FAILED', v_simdi);
        UPDATE hedef_kaynagi
           SET calistirma_id = NULL, durum_kodu = 'BOS',
               kiralama_bitis_zamani = NULL, guncellenme_zamani = v_simdi,
               versiyon_no = v_hedef.versiyon_no + 1
         WHERE id = v_hedef.id;
        UPDATE calistirma_durumu
           SET durum_kodu = 'BASARISIZ', son_olay_no = v_olay_no + 1,
               kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
               guncellenme_zamani = v_simdi,
               versiyon_no = v_durum.versiyon_no + 2
         WHERE id = v_durum.id;
        INSERT INTO calistirma_olayi(
            proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
        VALUES (v_calisma.proje_id, v_calisma.id, v_olay_no + 1,
                'PROCEDURE_RUN_FAILED', v_simdi,
                jsonb_build_object(
                    'generation', p_calistirma_nesil_no,
                    'workerReference', p_isleyici_referansi,
                    'targetResourceUuid', p_hedef_kaynagi_uuid,
                    'targetGeneration', p_hedef_nesil_no,
                    'runtimePlanHash', v_kanit.runtime_plan_ozeti,
                    'failedStepCode', p_adim_kodu,
                    'errorCode', p_hata_kodu,
                    'rollbackConfirmed', TRUE));
    END IF;
    RETURN TRUE;
END;
$$;

CREATE FUNCTION prosedur_calistirmayi_basarili_tamamla(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT, p_runtime_plan_ozeti TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_calisma calistirma%ROWTYPE;
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
    v_adim_sayisi INTEGER;
    v_hata_devam_sayisi INTEGER;
BEGIN
    IF p_runtime_plan_ozeti IS NULL
       OR p_runtime_plan_ozeti !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Runtime plan özeti geçersiz';
    END IF;
    SELECT * INTO v_calisma FROM calistirma WHERE uuid = p_calistirma_uuid;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_durum FROM calistirma_durumu
     WHERE proje_id = v_calisma.proje_id AND calistirma_id = v_calisma.id
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;

    IF v_durum.durum_kodu = 'BASARILI' THEN
        RETURN COALESCE((
            v_durum.isleyici_referansi = p_isleyici_referansi
            AND v_durum.nesil_no = p_calistirma_nesil_no
            AND v_durum.hedef_nesil_no = p_hedef_nesil_no
            AND v_durum.kiralama_bitis_zamani IS NULL
            AND v_hedef.uuid = p_hedef_kaynagi_uuid
            AND v_hedef.durum_kodu = 'BOS'
            AND v_hedef.nesil_no = p_hedef_nesil_no
            AND EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_calisma.proje_id
                   AND co.calistirma_id = v_calisma.id
                   AND co.olay_no = v_durum.son_olay_no
                   AND co.tur_kodu = 'PROCEDURE_RUN_SUCCEEDED'
                   AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
                   AND co.veri ->> 'workerReference' = p_isleyici_referansi
                   AND co.veri ->> 'targetGeneration' = p_hedef_nesil_no::TEXT
                   AND co.veri ->> 'runtimePlanHash' = p_runtime_plan_ozeti)), FALSE);
    END IF;

    IF v_durum.durum_kodu <> 'CALISIYOR'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.hedef_nesil_no <> p_hedef_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_hedef.id IS NULL OR v_hedef.uuid <> p_hedef_kaynagi_uuid
       OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
       OR v_hedef.calistirma_id <> v_calisma.id
       OR v_hedef.nesil_no <> p_hedef_nesil_no
       OR v_hedef.kiralama_bitis_zamani <= v_simdi THEN
        RETURN FALSE;
    END IF;
    SELECT count(*), count(*) FILTER (WHERE pad.durum_kodu = 'HATA_DEVAM')
      INTO v_adim_sayisi, v_hata_devam_sayisi
      FROM prosedur_adim_kaniti pak
      JOIN prosedur_adim_durumu pad ON pad.prosedur_adim_kaniti_id = pak.id
     WHERE pak.calistirma_id = v_calisma.id
       AND pak.runtime_plan_ozeti = p_runtime_plan_ozeti;
    IF v_adim_sayisi < 1 OR EXISTS (
        SELECT 1 FROM prosedur_adim_kaniti pak
        JOIN prosedur_adim_durumu pad ON pad.prosedur_adim_kaniti_id = pak.id
         WHERE pak.calistirma_id = v_calisma.id
           AND (pak.runtime_plan_ozeti <> p_runtime_plan_ozeti
                OR pad.durum_kodu NOT IN ('BASARILI', 'HATA_DEVAM'))) THEN
        RETURN FALSE;
    END IF;

    UPDATE hedef_kaynagi
       SET calistirma_id = NULL, durum_kodu = 'BOS',
           kiralama_bitis_zamani = NULL, guncellenme_zamani = v_simdi,
           versiyon_no = v_hedef.versiyon_no + 1
     WHERE id = v_hedef.id;
    UPDATE calistirma_durumu
       SET durum_kodu = 'BASARILI', son_olay_no = v_durum.son_olay_no + 1,
           kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_calisma.proje_id, v_calisma.id,
            v_durum.son_olay_no + 1, 'PROCEDURE_RUN_SUCCEEDED', v_simdi,
            jsonb_build_object(
                'generation', p_calistirma_nesil_no,
                'workerReference', p_isleyici_referansi,
                'targetResourceUuid', p_hedef_kaynagi_uuid,
                'targetGeneration', p_hedef_nesil_no,
                'runtimePlanHash', p_runtime_plan_ozeti,
                'taskCount', v_adim_sayisi,
                'continuedErrorCount', v_hata_devam_sayisi));
    RETURN TRUE;
END;
$$;

CREATE FUNCTION prosedur_calistirmayi_basarisiz_tamamla(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT, p_runtime_plan_ozeti TEXT,
    p_basarisiz_adim_kodu TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_calisma calistirma%ROWTYPE;
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_kanit prosedur_adim_kaniti%ROWTYPE;
    v_adim_durumu prosedur_adim_durumu%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
    v_olay_no BIGINT;
BEGIN
    IF p_runtime_plan_ozeti IS NULL
       OR p_runtime_plan_ozeti !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Runtime plan özeti geçersiz';
    END IF;
    SELECT * INTO v_calisma FROM calistirma WHERE uuid = p_calistirma_uuid;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_durum FROM calistirma_durumu
     WHERE proje_id = v_calisma.proje_id AND calistirma_id = v_calisma.id
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;
    SELECT pak.* INTO v_kanit FROM prosedur_adim_kaniti pak
      JOIN calistirma_adimi ca ON ca.id = pak.calistirma_adimi_id
     WHERE pak.proje_id = v_calisma.proje_id
       AND pak.calistirma_id = v_calisma.id
       AND ca.adim_kodu = p_basarisiz_adim_kodu;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_adim_durumu FROM prosedur_adim_durumu
     WHERE prosedur_adim_kaniti_id = v_kanit.id FOR UPDATE;

    IF v_durum.durum_kodu = 'BASARISIZ' THEN
        RETURN COALESCE((
            v_durum.isleyici_referansi = p_isleyici_referansi
            AND v_durum.nesil_no = p_calistirma_nesil_no
            AND v_durum.hedef_nesil_no = p_hedef_nesil_no
            AND v_durum.kiralama_bitis_zamani IS NULL
            AND v_hedef.uuid = p_hedef_kaynagi_uuid
            AND v_hedef.durum_kodu = 'BOS'
            AND v_hedef.nesil_no = p_hedef_nesil_no
            AND v_adim_durumu.durum_kodu = 'BASARISIZ'
            AND EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_calisma.proje_id
                   AND co.calistirma_id = v_calisma.id
                   AND co.olay_no = v_durum.son_olay_no
                   AND co.tur_kodu = 'PROCEDURE_RUN_FAILED'
                   AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
                   AND co.veri ->> 'workerReference' = p_isleyici_referansi
                   AND co.veri ->> 'targetGeneration' = p_hedef_nesil_no::TEXT
                   AND co.veri ->> 'runtimePlanHash' = p_runtime_plan_ozeti
                   AND co.veri ->> 'failedStepCode' = p_basarisiz_adim_kodu
                   AND co.veri ->> 'errorCode' = v_adim_durumu.hata_kodu)), FALSE);
    END IF;

    IF v_durum.durum_kodu <> 'CALISIYOR'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.hedef_nesil_no <> p_hedef_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_hedef.id IS NULL OR v_hedef.uuid <> p_hedef_kaynagi_uuid
       OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
       OR v_hedef.calistirma_id <> v_calisma.id
       OR v_hedef.nesil_no <> p_hedef_nesil_no
       OR v_hedef.kiralama_bitis_zamani <= v_simdi
       OR v_kanit.runtime_plan_ozeti <> p_runtime_plan_ozeti
       OR v_adim_durumu.durum_kodu <> 'BASARISIZ'
       OR EXISTS (
            SELECT 1 FROM prosedur_adim_kaniti onceki
            JOIN prosedur_adim_durumu od
              ON od.prosedur_adim_kaniti_id = onceki.id
             WHERE onceki.calistirma_id = v_calisma.id
               AND onceki.sira_no < v_kanit.sira_no
               AND od.durum_kodu NOT IN ('BASARILI', 'HATA_DEVAM'))
       OR EXISTS (
            SELECT 1 FROM prosedur_adim_kaniti sonraki
            JOIN prosedur_adim_durumu sd
              ON sd.prosedur_adim_kaniti_id = sonraki.id
             WHERE sonraki.calistirma_id = v_calisma.id
               AND sonraki.sira_no > v_kanit.sira_no
               AND sd.durum_kodu <> 'BEKLIYOR') THEN
        RETURN FALSE;
    END IF;

    v_olay_no := prosedur_bekleyen_adimlari_atla(
        v_calisma.proje_id, v_calisma.id, v_kanit.sira_no,
        v_durum.son_olay_no, 'PREVIOUS_STEP_FAILED', v_simdi);
    UPDATE hedef_kaynagi
       SET calistirma_id = NULL, durum_kodu = 'BOS',
           kiralama_bitis_zamani = NULL, guncellenme_zamani = v_simdi,
           versiyon_no = v_hedef.versiyon_no + 1
     WHERE id = v_hedef.id;
    UPDATE calistirma_durumu
       SET durum_kodu = 'BASARISIZ', son_olay_no = v_olay_no + 1,
           kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_calisma.proje_id, v_calisma.id, v_olay_no + 1,
            'PROCEDURE_RUN_FAILED', v_simdi,
            jsonb_build_object(
                'generation', p_calistirma_nesil_no,
                'workerReference', p_isleyici_referansi,
                'targetResourceUuid', p_hedef_kaynagi_uuid,
                'targetGeneration', p_hedef_nesil_no,
                'runtimePlanHash', p_runtime_plan_ozeti,
                'failedStepCode', p_basarisiz_adim_kodu,
                'errorCode', v_adim_durumu.hata_kodu,
                'rollbackConfirmed', TRUE));
    RETURN TRUE;
END;
$$;

CREATE FUNCTION prosedur_adimini_sonuc_belirsiz_isaretle(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT, p_adim_kodu TEXT, p_hata_kodu TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_calisma calistirma%ROWTYPE;
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_kanit prosedur_adim_kaniti%ROWTYPE;
    v_adim_durumu prosedur_adim_durumu%ROWTYPE;
    v_niyet prosedur_adim_niyeti%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
    v_olay_no BIGINT;
BEGIN
    IF p_hata_kodu IS NULL
       OR p_hata_kodu !~ '^[A-Z0-9][A-Z0-9_.:-]{0,99}$' THEN
        RAISE EXCEPTION 'Prosedür hata kodu geçersiz';
    END IF;
    SELECT * INTO v_calisma FROM calistirma WHERE uuid = p_calistirma_uuid;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_durum FROM calistirma_durumu
     WHERE proje_id = v_calisma.proje_id AND calistirma_id = v_calisma.id
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;
    SELECT pak.* INTO v_kanit FROM prosedur_adim_kaniti pak
      JOIN calistirma_adimi ca ON ca.id = pak.calistirma_adimi_id
     WHERE pak.proje_id = v_calisma.proje_id
       AND pak.calistirma_id = v_calisma.id
       AND ca.adim_kodu = p_adim_kodu;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT * INTO v_adim_durumu FROM prosedur_adim_durumu
     WHERE prosedur_adim_kaniti_id = v_kanit.id FOR UPDATE;
    SELECT * INTO v_niyet FROM prosedur_adim_niyeti
     WHERE calistirma_adimi_id = v_kanit.calistirma_adimi_id;

    IF v_durum.durum_kodu = 'SONUC_BELIRSIZ' THEN
        RETURN COALESCE((
            v_adim_durumu.durum_kodu = 'SONUC_BELIRSIZ'
            AND v_adim_durumu.hata_kodu = p_hata_kodu
            AND v_durum.isleyici_referansi = p_isleyici_referansi
            AND v_durum.nesil_no = p_calistirma_nesil_no
            AND v_durum.hedef_nesil_no = p_hedef_nesil_no
            AND v_durum.kiralama_bitis_zamani IS NULL
            AND v_hedef.uuid = p_hedef_kaynagi_uuid
            AND v_hedef.durum_kodu = 'ASKIDA'
            AND v_hedef.nesil_no = p_hedef_nesil_no
            AND v_niyet.calistirma_nesil_no = p_calistirma_nesil_no
            AND v_niyet.hedef_nesil_no = p_hedef_nesil_no
            AND EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_calisma.proje_id
                   AND co.calistirma_id = v_calisma.id
                   AND co.olay_no = v_durum.son_olay_no
                   AND co.tur_kodu = 'PROCEDURE_RESULT_UNCERTAIN'
                   AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
                   AND co.veri ->> 'workerReference' = p_isleyici_referansi
                   AND co.veri ->> 'targetGeneration' = p_hedef_nesil_no::TEXT
                   AND co.veri ->> 'uncertainStepCode' = p_adim_kodu
                   AND co.veri ->> 'errorCode' = p_hata_kodu
                   AND co.veri ->> 'operationKeyHash' =
                       v_niyet.operasyon_anahtari_ozeti)), FALSE);
    END IF;

    IF v_durum.durum_kodu <> 'CALISIYOR'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.hedef_nesil_no <> p_hedef_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_hedef.id IS NULL OR v_hedef.uuid <> p_hedef_kaynagi_uuid
       OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
       OR v_hedef.calistirma_id <> v_calisma.id
       OR v_hedef.nesil_no <> p_hedef_nesil_no
       OR v_hedef.kiralama_bitis_zamani <= v_simdi
       OR v_adim_durumu.durum_kodu <> 'CALISIYOR'
       OR v_kanit.risk_kodu = 'READ_ONLY'
       OR v_niyet.id IS NULL
       OR v_niyet.calistirma_nesil_no <> p_calistirma_nesil_no
       OR v_niyet.isleyici_referansi <> p_isleyici_referansi
       OR v_niyet.hedef_kaynagi_id <> v_hedef.id
       OR v_niyet.hedef_nesil_no <> p_hedef_nesil_no
       OR v_niyet.hedef_fiziksel_ozeti <> v_hedef.fiziksel_ozet
       OR v_niyet.runtime_plan_ozeti <> v_kanit.runtime_plan_ozeti
       OR v_niyet.komut_ozeti <> v_kanit.komut_ozeti THEN
        RETURN FALSE;
    END IF;

    v_olay_no := v_durum.son_olay_no + 1;
    UPDATE prosedur_adim_durumu
       SET durum_kodu = 'SONUC_BELIRSIZ', son_olay_no = v_olay_no,
           bitis_zamani = v_simdi, hata_kodu = p_hata_kodu,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_adim_durumu.versiyon_no + 1
     WHERE id = v_adim_durumu.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, calistirma_adimi_id, olay_no,
        tur_kodu, olay_zamani, veri)
    VALUES (v_calisma.proje_id, v_calisma.id,
            v_kanit.calistirma_adimi_id, v_olay_no,
            'PROCEDURE_STEP_OUTCOME_UNKNOWN', v_simdi,
            jsonb_build_object(
                'generation', p_calistirma_nesil_no,
                'workerReference', p_isleyici_referansi,
                'targetResourceUuid', p_hedef_kaynagi_uuid,
                'targetGeneration', p_hedef_nesil_no,
                'runtimePlanHash', v_kanit.runtime_plan_ozeti,
                'commandHash', v_kanit.komut_ozeti,
                'operationKeyHash', v_niyet.operasyon_anahtari_ozeti,
                'errorCode', p_hata_kodu,
                'requiresReconciliation', FALSE,
                'requiresManualIntervention', TRUE));
    v_olay_no := prosedur_bekleyen_adimlari_atla(
        v_calisma.proje_id, v_calisma.id, v_kanit.sira_no,
        v_olay_no, 'OUTCOME_UNKNOWN', v_simdi);
    UPDATE hedef_kaynagi
       SET calistirma_id = NULL, durum_kodu = 'ASKIDA',
           kiralama_bitis_zamani = NULL, guncellenme_zamani = v_simdi,
           versiyon_no = v_hedef.versiyon_no + 1
     WHERE id = v_hedef.id;
    UPDATE calistirma_durumu
       SET durum_kodu = 'SONUC_BELIRSIZ', son_olay_no = v_olay_no + 1,
           kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_calisma.proje_id, v_calisma.id, v_olay_no + 1,
            'PROCEDURE_RESULT_UNCERTAIN', v_simdi,
            jsonb_build_object(
                'generation', p_calistirma_nesil_no,
                'workerReference', p_isleyici_referansi,
                'targetResourceUuid', p_hedef_kaynagi_uuid,
                'targetGeneration', p_hedef_nesil_no,
                'runtimePlanHash', v_kanit.runtime_plan_ozeti,
                'commandHash', v_kanit.komut_ozeti,
                'operationKeyHash', v_niyet.operasyon_anahtari_ozeti,
                'uncertainStepCode', p_adim_kodu,
                'errorCode', p_hata_kodu,
                'requiresReconciliation', FALSE,
                'requiresManualIntervention', TRUE));
    RETURN TRUE;
END;
$$;

-- Replace V011's target reaper without changing its pilot branch. Procedure
-- runs are classified from their step journal before the pilot intent rules
-- are evaluated.
CREATE OR REPLACE FUNCTION suresi_dolan_hedefleri_askiya_al(
    p_limit INTEGER DEFAULT 100)
RETURNS TABLE (
    calistirma_uuid UUID,
    hedef_kaynagi_uuid UUID,
    hedef_nesil_no BIGINT)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_simdi TIMESTAMPTZ := clock_timestamp();
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_niyet pilot_yayin_niyeti%ROWTYPE;
    v_prosedur_niyet prosedur_adim_niyeti%ROWTYPE;
    v_aktif_kanit prosedur_adim_kaniti%ROWTYPE;
    v_aktif_adim prosedur_adim_durumu%ROWTYPE;
    v_pilot_niyet_var BOOLEAN;
    v_pilot_token_exact BOOLEAN;
    v_prosedur BOOLEAN;
    v_prosedur_tamam BOOLEAN;
    v_prosedur_belirsiz BOOLEAN;
    v_prosedur_token_exact BOOLEAN;
    v_lease_token_exact BOOLEAN;
    v_guvenli_pre_publish BOOLEAN;
    v_invariant_conflict TEXT;
    v_aktif_adim_sayisi INTEGER;
    v_cozulmemis_niyet_sayisi INTEGER;
    v_toplam_niyet_sayisi INTEGER;
    v_runtime_plan_ozeti TEXT;
    v_sira_no INTEGER := 0;
    v_olay_no BIGINT;
BEGIN
    IF p_limit < 1 OR p_limit > 1000 THEN
        RAISE EXCEPTION 'Reaper limiti 1 ile 1000 arasında olmalıdır';
    END IF;

    FOR v_durum IN
        SELECT cd.*
          FROM calistirma_durumu cd
         WHERE cd.durum_kodu IN (
                   'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR',
                   'IPTAL_ISTENDI')
           AND cd.hedef_kaynagi_id IS NOT NULL
           AND cd.kiralama_bitis_zamani <= v_simdi
         ORDER BY cd.kiralama_bitis_zamani, cd.id
         FOR UPDATE SKIP LOCKED
         LIMIT p_limit
    LOOP
        SELECT * INTO v_hedef FROM hedef_kaynagi
         WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;
        IF NOT FOUND OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
           OR v_hedef.calistirma_id <> v_durum.calistirma_id
           OR v_hedef.nesil_no <> v_durum.hedef_nesil_no THEN
            CONTINUE;
        END IF;

        v_lease_token_exact :=
            v_durum.kiralama_bitis_zamani IS NOT DISTINCT FROM
                v_hedef.kiralama_bitis_zamani
            AND v_durum.kiralama_bitis_zamani <= v_simdi
            AND v_hedef.kiralama_bitis_zamani <= v_simdi;
        SELECT EXISTS (
            SELECT 1 FROM prosedur_adim_kaniti pak
             WHERE pak.proje_id = v_durum.proje_id
               AND pak.calistirma_id = v_durum.calistirma_id)
          INTO v_prosedur;

        IF v_prosedur THEN
            SELECT count(*) INTO v_aktif_adim_sayisi
              FROM prosedur_adim_durumu
             WHERE proje_id = v_durum.proje_id
               AND calistirma_id = v_durum.calistirma_id
               AND durum_kodu = 'CALISIYOR';
            v_aktif_kanit := NULL;
            v_aktif_adim := NULL;
            IF v_aktif_adim_sayisi = 1 THEN
                SELECT pak.* INTO v_aktif_kanit
                  FROM prosedur_adim_kaniti pak
                  JOIN prosedur_adim_durumu pad
                    ON pad.prosedur_adim_kaniti_id = pak.id
                 WHERE pak.proje_id = v_durum.proje_id
                   AND pak.calistirma_id = v_durum.calistirma_id
                   AND pad.durum_kodu = 'CALISIYOR'
                 FOR UPDATE OF pad;
                SELECT * INTO v_aktif_adim FROM prosedur_adim_durumu
                 WHERE prosedur_adim_kaniti_id = v_aktif_kanit.id
                 FOR UPDATE;
            END IF;

            v_prosedur_niyet := NULL;
            SELECT * INTO v_prosedur_niyet FROM prosedur_adim_niyeti
             WHERE proje_id = v_durum.proje_id
               AND calistirma_id = v_durum.calistirma_id
             ORDER BY id DESC LIMIT 1;
            v_prosedur_token_exact := COALESCE((
                v_prosedur_niyet.id IS NOT NULL
                AND v_prosedur_niyet.calistirma_adimi_id =
                    v_aktif_kanit.calistirma_adimi_id
                AND v_prosedur_niyet.calistirma_nesil_no = v_durum.nesil_no
                AND v_prosedur_niyet.isleyici_referansi =
                    v_durum.isleyici_referansi
                AND v_prosedur_niyet.hedef_kaynagi_id = v_hedef.id
                AND v_prosedur_niyet.hedef_nesil_no = v_hedef.nesil_no
                AND v_prosedur_niyet.hedef_fiziksel_ozeti =
                    v_hedef.fiziksel_ozet
                AND v_prosedur_niyet.hedef_kimlik_surumu =
                    v_hedef.kimlik_surumu
                AND v_prosedur_niyet.runtime_plan_ozeti =
                    v_aktif_kanit.runtime_plan_ozeti
                AND v_prosedur_niyet.komut_ozeti =
                    v_aktif_kanit.komut_ozeti), FALSE);
            SELECT count(*) INTO v_cozulmemis_niyet_sayisi
              FROM prosedur_adim_niyeti pan
              JOIN prosedur_adim_durumu pad
                ON pad.calistirma_adimi_id = pan.calistirma_adimi_id
             WHERE pan.proje_id = v_durum.proje_id
               AND pan.calistirma_id = v_durum.calistirma_id
               AND pad.durum_kodu NOT IN (
                   'BASARILI', 'HATA_DEVAM', 'BASARISIZ');
            SELECT count(*) INTO v_toplam_niyet_sayisi
              FROM prosedur_adim_niyeti
             WHERE proje_id = v_durum.proje_id
               AND calistirma_id = v_durum.calistirma_id;
            SELECT min(pak.runtime_plan_ozeti), NOT EXISTS (
                SELECT 1
                  FROM prosedur_adim_kaniti evidence
                  JOIN prosedur_adim_durumu state
                    ON state.prosedur_adim_kaniti_id = evidence.id
                  LEFT JOIN calistirma_olayi event
                    ON event.proje_id = evidence.proje_id
                   AND event.calistirma_id = evidence.calistirma_id
                   AND event.calistirma_adimi_id = evidence.calistirma_adimi_id
                   AND event.olay_no = state.son_olay_no
                 WHERE evidence.proje_id = v_durum.proje_id
                   AND evidence.calistirma_id = v_durum.calistirma_id
                   AND (state.durum_kodu NOT IN ('BASARILI', 'HATA_DEVAM')
                        OR state.durum_kodu = 'BASARILI'
                           AND (event.tur_kodu IS DISTINCT FROM
                                    'PROCEDURE_STEP_SUCCEEDED'
                                OR event.veri ->> 'runtimePlanHash'
                                    IS DISTINCT FROM evidence.runtime_plan_ozeti
                                OR event.veri ->> 'commandHash'
                                    IS DISTINCT FROM evidence.komut_ozeti)
                        OR state.durum_kodu = 'HATA_DEVAM'
                           AND (event.tur_kodu IS DISTINCT FROM
                                    'PROCEDURE_STEP_FAILED_SAFE'
                                OR event.veri ->> 'runtimePlanHash'
                                    IS DISTINCT FROM evidence.runtime_plan_ozeti
                                OR event.veri ->> 'commandHash'
                                    IS DISTINCT FROM evidence.komut_ozeti
                                OR event.veri ->> 'rollbackConfirmed'
                                    IS DISTINCT FROM 'true')))
              INTO v_runtime_plan_ozeti, v_prosedur_tamam
              FROM prosedur_adim_kaniti pak
             WHERE pak.proje_id = v_durum.proje_id
               AND pak.calistirma_id = v_durum.calistirma_id;
            v_prosedur_belirsiz :=
                (NOT v_lease_token_exact
                 AND (v_aktif_adim_sayisi > 0
                      OR v_toplam_niyet_sayisi > 0))
                OR v_aktif_adim_sayisi > 1
                OR v_cozulmemis_niyet_sayisi > 1
                OR (v_aktif_kanit.id IS NOT NULL
                    AND v_aktif_kanit.risk_kodu <> 'READ_ONLY')
                OR (v_cozulmemis_niyet_sayisi = 1
                    AND (v_aktif_kanit.id IS NULL
                         OR NOT v_prosedur_token_exact));

            IF v_prosedur_tamam AND v_lease_token_exact THEN
                UPDATE hedef_kaynagi
                   SET calistirma_id = NULL, durum_kodu = 'BOS',
                       kiralama_bitis_zamani = NULL,
                       guncellenme_zamani = v_simdi,
                       versiyon_no = v_hedef.versiyon_no + 1
                 WHERE id = v_hedef.id;
                UPDATE calistirma_durumu
                   SET durum_kodu = 'BASARILI',
                       son_olay_no = v_durum.son_olay_no + 1,
                       kiralama_bitis_zamani = NULL,
                       bitis_zamani = v_simdi,
                       guncellenme_zamani = v_simdi,
                       versiyon_no = v_durum.versiyon_no + 1
                 WHERE id = v_durum.id;
                INSERT INTO calistirma_olayi(
                    proje_id, calistirma_id, olay_no,
                    tur_kodu, olay_zamani, veri)
                VALUES (v_durum.proje_id, v_durum.calistirma_id,
                        v_durum.son_olay_no + 1,
                        'PROCEDURE_RUN_SUCCEEDED_AFTER_LEASE_EXPIRY',
                        v_simdi,
                        jsonb_build_object(
                            'generation', v_durum.nesil_no,
                            'workerReference', v_durum.isleyici_referansi,
                            'targetResourceUuid', v_hedef.uuid,
                            'targetGeneration', v_hedef.nesil_no,
                            'runtimePlanHash', v_runtime_plan_ozeti,
                            'completionEvidenceExact', TRUE,
                            'requiresReconciliation', FALSE));
            ELSIF v_prosedur_belirsiz THEN
                v_olay_no := v_durum.son_olay_no;
                v_sira_no := COALESCE(v_aktif_kanit.sira_no, 0);
                IF v_aktif_adim.id IS NOT NULL THEN
                    v_olay_no := v_olay_no + 1;
                    UPDATE prosedur_adim_durumu
                       SET durum_kodu = 'SONUC_BELIRSIZ',
                           son_olay_no = v_olay_no,
                           bitis_zamani = v_simdi,
                           hata_kodu = 'LEASE_EXPIRED',
                           guncellenme_zamani = v_simdi,
                           versiyon_no = v_aktif_adim.versiyon_no + 1
                     WHERE id = v_aktif_adim.id;
                    INSERT INTO calistirma_olayi(
                        proje_id, calistirma_id, calistirma_adimi_id,
                        olay_no, tur_kodu, olay_zamani, veri)
                    VALUES (v_durum.proje_id, v_durum.calistirma_id,
                            v_aktif_kanit.calistirma_adimi_id, v_olay_no,
                            'PROCEDURE_STEP_OUTCOME_UNKNOWN', v_simdi,
                            jsonb_build_object(
                                'generation', v_durum.nesil_no,
                                'workerReference', v_durum.isleyici_referansi,
                                'targetGeneration', v_hedef.nesil_no,
                                'runtimePlanHash',
                                    v_aktif_kanit.runtime_plan_ozeti,
                                'commandHash', v_aktif_kanit.komut_ozeti,
                                'operationKeyHash',
                                    v_prosedur_niyet.operasyon_anahtari_ozeti,
                                'errorCode', 'LEASE_EXPIRED',
                                'requiresManualIntervention', TRUE));
                END IF;
                v_olay_no := prosedur_bekleyen_adimlari_atla(
                    v_durum.proje_id, v_durum.calistirma_id,
                    v_sira_no, v_olay_no,
                    'LEASE_EXPIRED_AFTER_MUTATION', v_simdi);
                UPDATE hedef_kaynagi
                   SET calistirma_id = NULL, durum_kodu = 'ASKIDA',
                       kiralama_bitis_zamani = NULL,
                       guncellenme_zamani = v_simdi,
                       versiyon_no = v_hedef.versiyon_no + 1
                 WHERE id = v_hedef.id;
                UPDATE calistirma_durumu
                   SET durum_kodu = 'SONUC_BELIRSIZ',
                       son_olay_no = v_olay_no + 1,
                       kiralama_bitis_zamani = NULL,
                       bitis_zamani = v_simdi,
                       guncellenme_zamani = v_simdi,
                       versiyon_no = v_durum.versiyon_no + 1
                 WHERE id = v_durum.id;
                INSERT INTO calistirma_olayi(
                    proje_id, calistirma_id, olay_no,
                    tur_kodu, olay_zamani, veri)
                VALUES (v_durum.proje_id, v_durum.calistirma_id,
                        v_olay_no + 1, 'PROCEDURE_LEASE_EXPIRED_UNKNOWN',
                        v_simdi,
                        jsonb_build_object(
                            'generation', v_durum.nesil_no,
                            'workerReference', v_durum.isleyici_referansi,
                            'targetResourceUuid', v_hedef.uuid,
                            'targetGeneration', v_hedef.nesil_no,
                            'procedureIntentPresent',
                                v_toplam_niyet_sayisi > 0,
                            'intentTokenExact', v_prosedur_token_exact,
                            'leaseTokenExact', v_lease_token_exact,
                            'runtimePlanHash',
                                v_prosedur_niyet.runtime_plan_ozeti,
                            'commandHash', v_prosedur_niyet.komut_ozeti,
                            'operationKeyHash',
                                v_prosedur_niyet.operasyon_anahtari_ozeti,
                            'requiresReconciliation', FALSE,
                            'requiresManualIntervention', TRUE));
            ELSE
                v_olay_no := v_durum.son_olay_no;
                v_sira_no := 0;
                IF v_aktif_adim.id IS NOT NULL THEN
                    v_sira_no := v_aktif_kanit.sira_no;
                    v_olay_no := v_olay_no + 1;
                    UPDATE prosedur_adim_durumu
                       SET durum_kodu = 'BASARISIZ',
                           son_olay_no = v_olay_no,
                           bitis_zamani = v_simdi,
                           hata_kodu = 'LEASE_EXPIRED',
                           guncellenme_zamani = v_simdi,
                           versiyon_no = v_aktif_adim.versiyon_no + 1
                     WHERE id = v_aktif_adim.id;
                    INSERT INTO calistirma_olayi(
                        proje_id, calistirma_id, calistirma_adimi_id,
                        olay_no, tur_kodu, olay_zamani, veri)
                    VALUES (v_durum.proje_id, v_durum.calistirma_id,
                            v_aktif_kanit.calistirma_adimi_id, v_olay_no,
                            'PROCEDURE_STEP_FAILED_SAFE', v_simdi,
                            jsonb_build_object(
                                'generation', v_durum.nesil_no,
                                'workerReference', v_durum.isleyici_referansi,
                                'targetGeneration', v_hedef.nesil_no,
                                'runtimePlanHash',
                                    v_aktif_kanit.runtime_plan_ozeti,
                                'commandHash', v_aktif_kanit.komut_ozeti,
                                'errorCode', 'LEASE_EXPIRED',
                                'stepState', 'BASARISIZ',
                                'rollbackConfirmed', TRUE));
                ELSE
                    SELECT COALESCE(min(pak.sira_no), 0) INTO v_sira_no
                      FROM prosedur_adim_kaniti pak
                      JOIN prosedur_adim_durumu pad
                        ON pad.prosedur_adim_kaniti_id = pak.id
                     WHERE pak.calistirma_id = v_durum.calistirma_id
                       AND pad.durum_kodu = 'BASARISIZ';
                END IF;
                v_olay_no := prosedur_bekleyen_adimlari_atla(
                    v_durum.proje_id, v_durum.calistirma_id,
                    v_sira_no, v_olay_no,
                    'PREVIOUS_STEP_FAILED', v_simdi);
                UPDATE hedef_kaynagi
                   SET calistirma_id = NULL, durum_kodu = 'BOS',
                       kiralama_bitis_zamani = NULL,
                       guncellenme_zamani = v_simdi,
                       versiyon_no = v_hedef.versiyon_no + 1
                 WHERE id = v_hedef.id;
                UPDATE calistirma_durumu
                   SET durum_kodu = 'BASARISIZ',
                       son_olay_no = v_olay_no + 1,
                       kiralama_bitis_zamani = NULL,
                       bitis_zamani = v_simdi,
                       guncellenme_zamani = v_simdi,
                       versiyon_no = v_durum.versiyon_no + 1
                 WHERE id = v_durum.id;
                INSERT INTO calistirma_olayi(
                    proje_id, calistirma_id, olay_no,
                    tur_kodu, olay_zamani, veri)
                VALUES (v_durum.proje_id, v_durum.calistirma_id,
                        v_olay_no + 1,
                        'PROCEDURE_LEASE_EXPIRED_SAFE_FAILURE', v_simdi,
                        jsonb_build_object(
                            'generation', v_durum.nesil_no,
                            'workerReference', v_durum.isleyici_referansi,
                            'targetResourceUuid', v_hedef.uuid,
                            'targetGeneration', v_hedef.nesil_no,
                            'procedureIntentPresent', FALSE,
                            'rollbackConfirmed', TRUE,
                            'requiresReconciliation', FALSE));
            END IF;

            RETURN QUERY
            SELECT c.uuid, v_hedef.uuid, v_hedef.nesil_no
              FROM calistirma c WHERE c.id = v_durum.calistirma_id;
            CONTINUE;
        END IF;

        -- Pilot branch retained from V011.
        v_niyet := NULL;
        SELECT * INTO v_niyet FROM pilot_yayin_niyeti
         WHERE proje_id = v_durum.proje_id
           AND calistirma_id = v_durum.calistirma_id;
        v_pilot_niyet_var := FOUND;
        v_pilot_token_exact := NOT v_pilot_niyet_var OR (
            v_niyet.calistirma_nesil_no = v_durum.nesil_no
            AND v_niyet.isleyici_referansi = v_durum.isleyici_referansi
            AND v_niyet.hedef_kaynagi_id = v_hedef.id
            AND v_niyet.hedef_nesil_no = v_hedef.nesil_no
            AND v_niyet.hedef_fiziksel_ozeti = v_hedef.fiziksel_ozet
            AND v_niyet.hedef_kimlik_surumu = v_hedef.kimlik_surumu);
        v_guvenli_pre_publish :=
            v_durum.durum_kodu IN (
                'HAZIRLANIYOR', 'CALISIYOR', 'IPTAL_ISTENDI')
            AND NOT v_pilot_niyet_var AND v_lease_token_exact;

        IF v_guvenli_pre_publish THEN
            UPDATE hedef_kaynagi
               SET calistirma_id = NULL, durum_kodu = 'BOS',
                   kiralama_bitis_zamani = NULL,
                   guncellenme_zamani = v_simdi,
                   versiyon_no = v_hedef.versiyon_no + 1
             WHERE id = v_hedef.id;
            UPDATE calistirma_durumu
               SET durum_kodu = CASE WHEN v_durum.durum_kodu = 'IPTAL_ISTENDI'
                       THEN 'IPTAL' ELSE 'BASARISIZ' END,
                   son_olay_no = v_durum.son_olay_no + 1,
                   kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
                   guncellenme_zamani = v_simdi,
                   versiyon_no = v_durum.versiyon_no + 1
             WHERE id = v_durum.id;
            INSERT INTO calistirma_olayi(
                proje_id, calistirma_id, olay_no,
                tur_kodu, olay_zamani, veri)
            VALUES (v_durum.proje_id, v_durum.calistirma_id,
                    v_durum.son_olay_no + 1,
                    CASE WHEN v_durum.durum_kodu = 'IPTAL_ISTENDI'
                         THEN 'PRE_PUBLISH_CANCEL_LEASE_EXPIRED'
                         ELSE 'PRE_PUBLISH_TARGET_LEASE_EXPIRED' END,
                    v_simdi,
                    jsonb_build_object(
                        'generation', v_durum.nesil_no,
                        'workerReference', v_durum.isleyici_referansi,
                        'targetResourceUuid', v_hedef.uuid,
                        'targetGeneration', v_hedef.nesil_no,
                        'canonicalTargetHash', v_hedef.fiziksel_ozet,
                        'targetIdentityVersion', v_hedef.kimlik_surumu,
                        'previousState', v_durum.durum_kodu,
                        'publishIntentPresent', FALSE,
                        'oracleDmlStarted', FALSE,
                        'cancellationAcknowledged',
                            v_durum.durum_kodu = 'IPTAL_ISTENDI',
                        'requiresReconciliation', FALSE));
        ELSE
            v_invariant_conflict := CASE
                WHEN v_durum.durum_kodu = 'YAYINLANIYOR'
                     AND NOT v_pilot_niyet_var
                    THEN 'PUBLISH_STATE_WITHOUT_INTENT'
                WHEN v_pilot_niyet_var AND NOT v_pilot_token_exact
                    THEN 'PUBLISH_INTENT_TOKEN_MISMATCH'
                WHEN NOT v_lease_token_exact THEN 'LEASE_TOKEN_MISMATCH'
                ELSE NULL END;
            UPDATE hedef_kaynagi
               SET calistirma_id = NULL, durum_kodu = 'ASKIDA',
                   kiralama_bitis_zamani = NULL,
                   guncellenme_zamani = v_simdi,
                   versiyon_no = v_hedef.versiyon_no + 1
             WHERE id = v_hedef.id;
            UPDATE calistirma_durumu
               SET durum_kodu = 'SONUC_BELIRSIZ',
                   son_olay_no = v_durum.son_olay_no + 1,
                   kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
                   guncellenme_zamani = v_simdi,
                   versiyon_no = v_durum.versiyon_no + 1
             WHERE id = v_durum.id;
            INSERT INTO calistirma_olayi(
                proje_id, calistirma_id, olay_no,
                tur_kodu, olay_zamani, veri)
            VALUES (v_durum.proje_id, v_durum.calistirma_id,
                    v_durum.son_olay_no + 1, 'LEASE_EXPIRED', v_simdi,
                    jsonb_build_object(
                        'generation', v_durum.nesil_no,
                        'workerReference', v_durum.isleyici_referansi,
                        'targetResourceUuid', v_hedef.uuid,
                        'targetGeneration', v_hedef.nesil_no,
                        'canonicalTargetHash', v_hedef.fiziksel_ozet,
                        'targetIdentityVersion', v_hedef.kimlik_surumu,
                        'previousState', v_durum.durum_kodu,
                        'publishIntentPresent', v_pilot_niyet_var,
                        'invariantConflict', v_invariant_conflict,
                        'publishTargetGeneration',
                            v_niyet.hedef_nesil_no,
                        'runtimePlanHash',
                            v_niyet.runtime_plan_ozeti,
                        'publishKeyHash',
                            v_niyet.yayin_anahtari_ozeti,
                        'payloadHash', v_niyet.payload_ozeti,
                        'rowCount', v_niyet.satir_sayisi,
                        'byteCount', v_niyet.bayt_sayisi,
                        'requiresReconciliation', TRUE));
        END IF;
        RETURN QUERY
        SELECT c.uuid, v_hedef.uuid, v_hedef.nesil_no
          FROM calistirma c WHERE c.id = v_durum.calistirma_id;
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION prosedur_adim_niyeti_eklemeyi_dogrula() FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_adimini_baslat(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_adimini_basarili_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, BIGINT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_mutating_adim_gecisini_dogrula() FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_bekleyen_adimlari_atla(
    BIGINT, BIGINT, INTEGER, BIGINT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_run_terminal_gecisini_dogrula() FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_adimini_basarisiz_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_calistirmayi_basarili_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_calistirmayi_basarisiz_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_adimini_sonuc_belirsiz_isaretle(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION suresi_dolan_hedefleri_askiya_al(INTEGER) FROM PUBLIC;

COMMENT ON FUNCTION prosedur_adimini_basarisiz_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT) IS
    'Bounded hata kodu ve rollback kanıtıyla step safe-failure exact ACK; STOP atomik run failure üretir';
COMMENT ON FUNCTION prosedur_adimini_sonuc_belirsiz_isaretle(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT) IS
    'Exact mutating intent sonrası belirsiz sonucu ASKIDA hedef ve manuel müdahale kanıtıyla sonlandırır';
