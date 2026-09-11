SET search_path TO entegrasyon, public;

-- A reconciliation claim must permanently supersede the possibly still-alive
-- publish writer in the Oracle target ledger. Keep the original publish fence
-- generation and the newer reconciliation barrier generation as separate
-- checkpoint evidence.
ALTER TABLE kontrol_noktasi
    ADD COLUMN mutabakat_hedef_nesil_no BIGINT,
    ADD CONSTRAINT ck_kontrol_noktasi_mutabakat_nesli CHECK (
        mutabakat_hedef_nesil_no IS NULL
        OR mutabakat_hedef_nesil_no = hedef_nesil_no + 1);

CREATE OR REPLACE FUNCTION hedef_kaynagi_degisikligini_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
BEGIN
    IF NEW.fiziksel_ozet IS DISTINCT FROM OLD.fiziksel_ozet
       OR NEW.kimlik_surumu IS DISTINCT FROM OLD.kimlik_surumu
       OR NEW.uuid IS DISTINCT FROM OLD.uuid
       OR NEW.olusturulma_zamani IS DISTINCT FROM OLD.olusturulma_zamani THEN
        RAISE EXCEPTION 'Hedef kaynağı kimlik alanları güncellenemez';
    END IF;

    IF NEW.versiyon_no <> OLD.versiyon_no + 1 THEN
        RAISE EXCEPTION 'Hedef kaynağı versiyonu tam olarak bir artırılmalıdır';
    END IF;
    IF NEW.nesil_no < OLD.nesil_no THEN
        RAISE EXCEPTION 'Hedef kaynağı nesli azaltılamaz';
    END IF;

    IF OLD.durum_kodu = 'BOS' AND NEW.durum_kodu = 'SAHIPLENILDI' THEN
        IF NEW.calistirma_id IS NULL
           OR NEW.nesil_no <> OLD.nesil_no + 1 THEN
            RAISE EXCEPTION 'Hedef sahiplenme yeni sahip ve tam bir nesil artışı gerektirir';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = 'SAHIPLENILDI' AND NEW.durum_kodu = 'SAHIPLENILDI' THEN
        IF NEW.calistirma_id IS DISTINCT FROM OLD.calistirma_id
           OR NEW.nesil_no <> OLD.nesil_no THEN
            RAISE EXCEPTION 'Aktif hedef sahibi veya nesli heartbeat sırasında değiştirilemez';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = 'SAHIPLENILDI'
       AND NEW.durum_kodu IN ('BOS', 'ASKIDA') THEN
        IF NEW.nesil_no <> OLD.nesil_no THEN
            RAISE EXCEPTION 'Hedef bırakılırken fencing nesli değiştirilemez';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = 'ASKIDA' AND NEW.durum_kodu = 'ASKIDA' THEN
        IF NEW.calistirma_id IS NOT NULL
           OR NEW.kiralama_bitis_zamani IS NOT NULL
           OR NEW.nesil_no NOT IN (OLD.nesil_no, OLD.nesil_no + 1) THEN
            RAISE EXCEPTION 'Askıdaki hedef yalnız mutabakat bariyeriyle bir nesil artırılabilir';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = 'ASKIDA' AND NEW.durum_kodu = 'BOS' THEN
        IF NEW.nesil_no <> OLD.nesil_no THEN
            RAISE EXCEPTION 'Askıdan çıkarılırken fencing nesli değiştirilemez';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = 'BOS' AND NEW.durum_kodu = 'ASKIDA' THEN
        IF NEW.nesil_no <> OLD.nesil_no THEN
            RAISE EXCEPTION 'Boş hedef askıya alınırken fencing nesli değiştirilemez';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = NEW.durum_kodu
       AND OLD.durum_kodu = 'BOS'
       AND NEW.nesil_no = OLD.nesil_no THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Geçersiz hedef kaynağı geçişi: % -> %',
        OLD.durum_kodu, NEW.durum_kodu;
END;
$$;

CREATE OR REPLACE FUNCTION calistirma_mutabakat_sahiplen(
    p_calistirma_uuid UUID, p_worker_profili_uuid UUID,
    p_isleyici_referansi TEXT, p_lease_saniyesi INTEGER DEFAULT 60)
RETURNS TABLE (calistirma_nesil_no BIGINT, hedef_kaynagi_uuid UUID,
               hedef_nesil_no BIGINT, kiralama_bitis_zamani TIMESTAMPTZ)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_worker_id BIGINT;
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_isleyici_referansi IS NULL OR btrim(p_isleyici_referansi) = ''
       OR p_lease_saniyesi < 30 OR p_lease_saniyesi > 300 THEN
        RAISE EXCEPTION 'Mutabakat worker veya lease bilgisi geçersiz';
    END IF;
    SELECT id INTO v_worker_id FROM worker_profili
     WHERE uuid = p_worker_profili_uuid AND durum_kodu = 'AKTIF';
    IF NOT FOUND THEN RAISE EXCEPTION 'Aktif worker profili bulunamadı'; END IF;

    SELECT cd.* INTO v_durum FROM calistirma_durumu cd
    JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
     WHERE c.uuid = p_calistirma_uuid FOR UPDATE OF cd;
    IF NOT FOUND OR v_durum.hedef_kaynagi_id IS NULL THEN RETURN; END IF;

    -- A lost acknowledgement must return the same barrier, never increment it.
    IF v_durum.durum_kodu = 'MUTABAKAT' THEN
        IF v_durum.worker_profili_id <> v_worker_id
           OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
           OR v_durum.kiralama_bitis_zamani <= v_simdi THEN RETURN; END IF;
        SELECT * INTO v_hedef FROM hedef_kaynagi
         WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;
        IF NOT FOUND OR v_hedef.durum_kodu <> 'ASKIDA'
           OR v_hedef.nesil_no <> v_durum.hedef_nesil_no THEN RETURN; END IF;
        RETURN QUERY SELECT v_durum.nesil_no, v_hedef.uuid, v_hedef.nesil_no,
            v_durum.kiralama_bitis_zamani;
        RETURN;
    END IF;

    IF v_durum.durum_kodu <> 'SONUC_BELIRSIZ' THEN RETURN; END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;
    IF NOT FOUND OR v_hedef.durum_kodu <> 'ASKIDA'
       OR v_hedef.nesil_no <> v_durum.hedef_nesil_no THEN RETURN; END IF;

    UPDATE hedef_kaynagi
       SET nesil_no = v_hedef.nesil_no + 1,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_hedef.versiyon_no + 1
     WHERE id = v_hedef.id;
    UPDATE calistirma_durumu
       SET worker_profili_id = v_worker_id,
           isleyici_referansi = p_isleyici_referansi,
           durum_kodu = 'MUTABAKAT',
           nesil_no = v_durum.nesil_no + 1,
           hedef_nesil_no = v_hedef.nesil_no + 1,
           son_olay_no = v_durum.son_olay_no + 1,
           kiralama_bitis_zamani = v_simdi + make_interval(secs => p_lease_saniyesi),
           yasam_sinyali_zamani = v_simdi,
           bitis_zamani = NULL,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1, 'RECONCILIATION_CLAIMED', v_simdi,
            jsonb_build_object('generation', v_durum.nesil_no + 1,
                               'publishTargetGeneration', v_hedef.nesil_no,
                               'targetGeneration', v_hedef.nesil_no + 1));
    RETURN QUERY SELECT v_durum.nesil_no + 1, v_hedef.uuid,
        v_hedef.nesil_no + 1,
        v_simdi + make_interval(secs => p_lease_saniyesi);
END;
$$;

-- Runs before the generic checkpoint and immutable-intent guards. The V006
-- completion function supplies the current barrier generation; retain it in a
-- dedicated field and restore the original publish generation from V007.
CREATE FUNCTION kontrol_noktasi_mutabakat_kanitini_sabitle() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_yayin_hedef_nesli BIGINT;
BEGIN
    -- This field is trigger-owned evidence. Reject it for every caller, including
    -- ordinary checkpoints, before deciding whether reconciliation applies.
    IF NEW.mutabakat_hedef_nesil_no IS NOT NULL THEN
        RAISE EXCEPTION 'Mutabakat hedef nesli caller tarafından verilemez';
    END IF;
    IF NEW.tur_kodu <> 'PUBLISH'
       OR COALESCE((NEW.imlec ->> 'reconciled')::BOOLEAN, FALSE) = FALSE THEN
        RETURN NEW;
    END IF;
    SELECT pyn.hedef_nesil_no INTO v_yayin_hedef_nesli
      FROM pilot_yayin_niyeti pyn
     WHERE pyn.proje_id = NEW.proje_id
       AND pyn.calistirma_id = NEW.calistirma_id
       AND pyn.hedef_kaynagi_id = NEW.hedef_kaynagi_id
       AND pyn.hedef_nesil_no + 1 = NEW.hedef_nesil_no
       AND pyn.yayin_ozeti = NEW.yayin_ozeti
       AND pyn.plan_ozeti = NEW.plan_ozeti
       AND pyn.runtime_plan_ozeti = NEW.kapsam_ozeti
       AND pyn.yayin_anahtari_ozeti = NEW.hedef_defter_referansi
       AND pyn.payload_ozeti = NEW.payload_ozeti
       AND pyn.satir_sayisi = (NEW.imlec ->> 'rowCount')::BIGINT
       AND pyn.bayt_sayisi = (NEW.imlec ->> 'byteCount')::BIGINT;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Mutabakat checkpointi immutable publish intent ile eşleşmiyor';
    END IF;
    NEW.mutabakat_hedef_nesil_no := NEW.hedef_nesil_no;
    NEW.hedef_nesil_no := v_yayin_hedef_nesli;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_00_kontrol_noktasi_mutabakat_kaniti
    BEFORE INSERT ON kontrol_noktasi
    FOR EACH ROW EXECUTE FUNCTION kontrol_noktasi_mutabakat_kanitini_sabitle();

CREATE OR REPLACE FUNCTION kontrol_noktasi_kanitini_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
BEGIN
    IF NEW.hedef_kaynagi_id IS NULL
       OR NEW.hedef_nesil_no IS NULL
       OR NEW.yayin_ozeti IS NULL
       OR NEW.plan_ozeti IS NULL
       OR NEW.payload_ozeti IS NULL THEN
        RAISE EXCEPTION 'Yeni checkpoint doğrulanmış hedef defteri kanıtı gerektirir';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM entegrasyon.calistirma c
          JOIN entegrasyon.calistirma_durumu cd
            ON cd.proje_id = c.proje_id AND cd.calistirma_id = c.id
         WHERE c.proje_id = NEW.proje_id
           AND c.id = NEW.calistirma_id
           AND c.yayin_ozeti = NEW.yayin_ozeti
           AND c.plan_ozeti = NEW.plan_ozeti
           AND cd.hedef_kaynagi_id = NEW.hedef_kaynagi_id
           AND cd.hedef_nesil_no = COALESCE(
                NEW.mutabakat_hedef_nesil_no, NEW.hedef_nesil_no)
    ) THEN
        RAISE EXCEPTION 'Checkpoint run, release, plan ve hedef fencing kanıtıyla eşleşmelidir';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM entegrasyon.calistirma_adimi ca
         WHERE ca.proje_id = NEW.proje_id
           AND ca.calistirma_id = NEW.calistirma_id
           AND ca.id = NEW.calistirma_adimi_id
    ) THEN
        RAISE EXCEPTION 'Checkpoint adımı aynı çalıştırmaya ait olmalıdır';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION calistirma_mutabakat_sahiplen(
    UUID, UUID, TEXT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION kontrol_noktasi_mutabakat_kanitini_sabitle() FROM PUBLIC;

COMMENT ON COLUMN kontrol_noktasi.mutabakat_hedef_nesil_no IS
    'Belirsiz publish neslini kalıcı olarak aşan Oracle reconciliation fence bariyeri';
COMMENT ON FUNCTION calistirma_mutabakat_sahiplen(UUID, UUID, TEXT, INTEGER) IS
    'Run ve target generationı atomik artırıp idempotent reconciliation fence bariyeri üretir';
