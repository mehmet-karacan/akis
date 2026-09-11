SET search_path TO entegrasyon, public;

-- Worker lifecycle functions are the only supported mutation boundary for the
-- pilot state machine. Every call re-proves the run lease and target generation
-- using database time before changing state.

CREATE FUNCTION pilot_calistirma_asamasina_gec(
    p_calistirma_uuid UUID,
    p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT,
    p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT,
    p_beklenen_durum TEXT,
    p_yeni_durum TEXT,
    p_olay_turu TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF (p_beklenen_durum, p_yeni_durum, p_olay_turu) NOT IN (
        ('HAZIRLANIYOR', 'CALISIYOR', 'PREFLIGHT_COMPLETED'),
        ('CALISIYOR', 'YAYINLANIYOR', 'PUBLISH_STARTED')) THEN
        RAISE EXCEPTION 'Desteklenmeyen pilot durum geçişi';
    END IF;

    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
     WHERE c.uuid = p_calistirma_uuid
     FOR UPDATE OF cd;

    IF NOT FOUND
       OR v_durum.durum_kodu <> p_beklenen_durum
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_durum.hedef_kaynagi_id IS NULL
       OR v_durum.hedef_nesil_no <> p_hedef_nesil_no THEN
        RETURN FALSE;
    END IF;

    SELECT * INTO v_hedef
      FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id
       AND uuid = p_hedef_kaynagi_uuid
     FOR UPDATE;
    IF NOT FOUND
       OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
       OR v_hedef.calistirma_id <> v_durum.calistirma_id
       OR v_hedef.nesil_no <> p_hedef_nesil_no
       OR v_hedef.kiralama_bitis_zamani <= v_simdi THEN
        RETURN FALSE;
    END IF;

    IF p_yeni_durum = 'CALISIYOR' THEN
        INSERT INTO calistirma_adimi(
            proje_id, calistirma_id, adim_kodu, tur_kodu, sira_no, ad)
        VALUES (v_durum.proje_id, v_durum.calistirma_id,
                'PILOT_PREFLIGHT', 'PREFLIGHT', 1, 'Pilot preflight')
        ON CONFLICT (calistirma_id, adim_kodu) DO NOTHING;
        INSERT INTO calistirma_adimi(
            proje_id, calistirma_id, adim_kodu, tur_kodu, sira_no, ad)
        VALUES (v_durum.proje_id, v_durum.calistirma_id,
                'PILOT_PUBLISH', 'PUBLISH', 2, 'Pilot publish')
        ON CONFLICT (calistirma_id, adim_kodu) DO NOTHING;
    END IF;

    UPDATE calistirma_durumu
       SET durum_kodu = p_yeni_durum,
           son_olay_no = v_durum.son_olay_no + 1,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1, p_olay_turu, v_simdi,
            jsonb_build_object('generation', p_calistirma_nesil_no,
                               'targetGeneration', p_hedef_nesil_no));
    RETURN TRUE;
END;
$$;

CREATE FUNCTION calistirma_calismaya_baslat(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT)
RETURNS BOOLEAN LANGUAGE sql
SET search_path = entegrasyon, public
AS $$
    SELECT pilot_calistirma_asamasina_gec(
        p_calistirma_uuid, p_isleyici_referansi, p_calistirma_nesil_no,
        p_hedef_kaynagi_uuid, p_hedef_nesil_no,
        'HAZIRLANIYOR', 'CALISIYOR', 'PREFLIGHT_COMPLETED')
$$;

CREATE FUNCTION calistirma_yayina_gec(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT)
RETURNS BOOLEAN LANGUAGE sql
SET search_path = entegrasyon, public
AS $$
    SELECT pilot_calistirma_asamasina_gec(
        p_calistirma_uuid, p_isleyici_referansi, p_calistirma_nesil_no,
        p_hedef_kaynagi_uuid, p_hedef_nesil_no,
        'CALISIYOR', 'YAYINLANIYOR', 'PUBLISH_STARTED')
$$;

CREATE FUNCTION calistirma_guvenli_hata_ile_sonlandir(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT, p_hata_kodu TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_hata_kodu IS NULL OR p_hata_kodu !~ '^[A-Z][A-Z0-9_]{0,99}$' THEN
        RAISE EXCEPTION 'Güvenli hata kodu geçersiz';
    END IF;
    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
     WHERE c.uuid = p_calistirma_uuid
     FOR UPDATE OF cd;
    IF NOT FOUND
       OR v_durum.durum_kodu NOT IN ('HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR')
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi THEN
        RETURN FALSE;
    END IF;
    IF v_durum.hedef_kaynagi_id IS NOT NULL THEN
        SELECT * INTO v_hedef FROM hedef_kaynagi
         WHERE id = v_durum.hedef_kaynagi_id AND uuid = p_hedef_kaynagi_uuid
         FOR UPDATE;
        IF NOT FOUND OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
           OR v_hedef.calistirma_id <> v_durum.calistirma_id
           OR v_hedef.nesil_no <> p_hedef_nesil_no
           OR v_hedef.kiralama_bitis_zamani <= v_simdi THEN
            RETURN FALSE;
        END IF;
        UPDATE hedef_kaynagi
           SET calistirma_id = NULL, durum_kodu = 'BOS',
               kiralama_bitis_zamani = NULL, guncellenme_zamani = v_simdi,
               versiyon_no = v_hedef.versiyon_no + 1
         WHERE id = v_hedef.id;
    ELSIF p_hedef_kaynagi_uuid IS NOT NULL OR p_hedef_nesil_no IS NOT NULL THEN
        RETURN FALSE;
    END IF;
    UPDATE calistirma_durumu
       SET durum_kodu = 'BASARISIZ', son_olay_no = v_durum.son_olay_no + 1,
           kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
           guncellenme_zamani = v_simdi, versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1, 'RUN_FAILED_SAFE', v_simdi,
            jsonb_build_object('errorCode', p_hata_kodu,
                               'rollbackConfirmed', TRUE));
    RETURN TRUE;
END;
$$;

CREATE FUNCTION calistirma_sonucu_belirsiz_isaretle(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
     WHERE c.uuid = p_calistirma_uuid
     FOR UPDATE OF cd;
    IF NOT FOUND
       OR v_durum.durum_kodu NOT IN ('CALISIYOR', 'YAYINLANIYOR', 'IPTAL_ISTENDI')
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_durum.hedef_kaynagi_id IS NULL
       OR v_durum.hedef_nesil_no <> p_hedef_nesil_no THEN
        RETURN FALSE;
    END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id AND uuid = p_hedef_kaynagi_uuid
     FOR UPDATE;
    IF NOT FOUND OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
       OR v_hedef.calistirma_id <> v_durum.calistirma_id
       OR v_hedef.nesil_no <> p_hedef_nesil_no
       OR v_hedef.kiralama_bitis_zamani <= v_simdi THEN
        RETURN FALSE;
    END IF;
    UPDATE hedef_kaynagi
       SET calistirma_id = NULL, durum_kodu = 'ASKIDA',
           kiralama_bitis_zamani = NULL, guncellenme_zamani = v_simdi,
           versiyon_no = v_hedef.versiyon_no + 1
     WHERE id = v_hedef.id;
    UPDATE calistirma_durumu
       SET durum_kodu = 'SONUC_BELIRSIZ', son_olay_no = v_durum.son_olay_no + 1,
           kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
           guncellenme_zamani = v_simdi, versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1, 'RESULT_UNCERTAIN', v_simdi,
            jsonb_build_object('targetGeneration', p_hedef_nesil_no,
                               'requiresReconciliation', TRUE));
    RETURN TRUE;
END;
$$;

CREATE FUNCTION calistirma_basarili_tamamla(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT, p_runtime_plan_ozeti TEXT,
    p_yayin_anahtari_ozeti TEXT, p_payload_ozeti TEXT,
    p_satir_sayisi BIGINT, p_bayt_sayisi BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_calisma calistirma%ROWTYPE;
    v_adim_id BIGINT;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_runtime_plan_ozeti !~ '^[0-9a-f]{64}$'
       OR p_yayin_anahtari_ozeti !~ '^[0-9a-f]{64}$'
       OR p_payload_ozeti !~ '^[0-9a-f]{64}$'
       OR p_satir_sayisi < 0 OR p_bayt_sayisi < 0 THEN
        RAISE EXCEPTION 'Pilot tamamlama kanıtı geçersiz';
    END IF;
    SELECT c.* INTO v_calisma
      FROM calistirma c
     WHERE c.uuid = p_calistirma_uuid;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
     WHERE cd.proje_id = v_calisma.proje_id
       AND cd.calistirma_id = v_calisma.id
     FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    IF v_durum.durum_kodu = 'BASARILI' THEN
        RETURN EXISTS (
            SELECT 1 FROM kontrol_noktasi kn
             WHERE kn.calistirma_id = v_calisma.id
               AND kn.hedef_kaynagi_id = v_durum.hedef_kaynagi_id
               AND kn.hedef_nesil_no = p_hedef_nesil_no
               AND kn.kapsam_ozeti = p_runtime_plan_ozeti
               AND kn.hedef_defter_referansi = p_yayin_anahtari_ozeti
               AND kn.payload_ozeti = p_payload_ozeti);
    END IF;
    IF v_durum.durum_kodu <> 'YAYINLANIYOR'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_durum.hedef_nesil_no <> p_hedef_nesil_no THEN
        RETURN FALSE;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM is_talebi it
        JOIN yayin y ON y.proje_id = it.proje_id AND y.id = it.yayin_id
        WHERE it.id = v_calisma.is_talebi_id
          AND y.fiziksel_manifesto ->> 'runtimeCapability' = 'ORACLE_TABLE_COPY_V1'
          AND y.fiziksel_manifesto ->> 'runtimePlanHash' = p_runtime_plan_ozeti
          AND y.release_hash = v_calisma.yayin_ozeti) THEN
        RETURN FALSE;
    END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id AND uuid = p_hedef_kaynagi_uuid
     FOR UPDATE;
    IF NOT FOUND OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
       OR v_hedef.calistirma_id <> v_calisma.id
       OR v_hedef.nesil_no <> p_hedef_nesil_no
       OR v_hedef.kiralama_bitis_zamani <= v_simdi THEN
        RETURN FALSE;
    END IF;
    SELECT id INTO v_adim_id FROM calistirma_adimi
     WHERE calistirma_id = v_calisma.id AND adim_kodu = 'PILOT_PUBLISH';
    IF NOT FOUND THEN RETURN FALSE; END IF;

    INSERT INTO kontrol_noktasi(
        proje_id, calistirma_id, calistirma_adimi_id, kapsam_ozeti,
        bolum_kodu, sira_no, paket_anahtari, hedef_defter_referansi,
        tur_kodu, dogrulama_zamani, imlec,
        hedef_kaynagi_id, hedef_nesil_no, yayin_ozeti, plan_ozeti,
        payload_ozeti)
    VALUES (v_calisma.proje_id, v_calisma.id, v_adim_id, p_runtime_plan_ozeti,
            'FULL', 1, p_yayin_anahtari_ozeti, p_yayin_anahtari_ozeti,
            'PUBLISH', v_simdi,
            jsonb_build_object('rowCount', p_satir_sayisi,
                               'byteCount', p_bayt_sayisi),
            v_hedef.id, p_hedef_nesil_no, v_calisma.yayin_ozeti,
            v_calisma.plan_ozeti, p_payload_ozeti);
    UPDATE hedef_kaynagi
       SET calistirma_id = NULL, durum_kodu = 'BOS',
           kiralama_bitis_zamani = NULL, guncellenme_zamani = v_simdi,
           versiyon_no = v_hedef.versiyon_no + 1
     WHERE id = v_hedef.id;
    UPDATE calistirma_durumu
       SET durum_kodu = 'BASARILI', son_olay_no = v_durum.son_olay_no + 1,
           kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
           guncellenme_zamani = v_simdi, versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_calisma.proje_id, v_calisma.id,
            v_durum.son_olay_no + 1, 'RUN_SUCCEEDED', v_simdi,
            jsonb_build_object('rowCount', p_satir_sayisi,
                               'byteCount', p_bayt_sayisi,
                               'targetGeneration', p_hedef_nesil_no));
    RETURN TRUE;
END;
$$;

CREATE FUNCTION calistirma_mutabakat_sahiplen(
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
    IF NOT FOUND OR v_durum.durum_kodu <> 'SONUC_BELIRSIZ'
       OR v_durum.hedef_kaynagi_id IS NULL THEN RETURN; END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;
    IF NOT FOUND OR v_hedef.durum_kodu <> 'ASKIDA'
       OR v_hedef.nesil_no <> v_durum.hedef_nesil_no THEN RETURN; END IF;
    UPDATE calistirma_durumu
       SET worker_profili_id = v_worker_id, isleyici_referansi = p_isleyici_referansi,
           durum_kodu = 'MUTABAKAT', nesil_no = v_durum.nesil_no + 1,
           son_olay_no = v_durum.son_olay_no + 1,
           kiralama_bitis_zamani = v_simdi + make_interval(secs => p_lease_saniyesi),
           yasam_sinyali_zamani = v_simdi, bitis_zamani = NULL,
           guncellenme_zamani = v_simdi, versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1, 'RECONCILIATION_CLAIMED', v_simdi,
            jsonb_build_object('generation', v_durum.nesil_no + 1,
                               'targetGeneration', v_hedef.nesil_no));
    RETURN QUERY SELECT v_durum.nesil_no + 1, v_hedef.uuid, v_hedef.nesil_no,
        v_simdi + make_interval(secs => p_lease_saniyesi);
END;
$$;

CREATE FUNCTION calistirma_mutabakat_yasam_sinyali(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_lease_saniyesi INTEGER DEFAULT 60)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_lease_saniyesi < 30 OR p_lease_saniyesi > 300 THEN
        RAISE EXCEPTION 'Lease süresi geçersiz';
    END IF;
    SELECT cd.* INTO v_durum FROM calistirma_durumu cd
     JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
     WHERE c.uuid = p_calistirma_uuid FOR UPDATE OF cd;
    IF NOT FOUND OR v_durum.durum_kodu <> 'MUTABAKAT'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR NOT EXISTS (SELECT 1 FROM hedef_kaynagi hk
                       WHERE hk.id = v_durum.hedef_kaynagi_id
                         AND hk.durum_kodu = 'ASKIDA'
                         AND hk.nesil_no = v_durum.hedef_nesil_no) THEN
        RETURN FALSE;
    END IF;
    UPDATE calistirma_durumu
       SET kiralama_bitis_zamani = v_simdi + make_interval(secs => p_lease_saniyesi),
           yasam_sinyali_zamani = v_simdi, guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    RETURN TRUE;
END;
$$;

CREATE FUNCTION calistirma_mutabakat_sonlandir(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT, p_hedef_kaynagi_uuid UUID,
    p_hedef_nesil_no BIGINT, p_sonuc TEXT,
    p_runtime_plan_ozeti TEXT, p_yayin_anahtari_ozeti TEXT,
    p_payload_ozeti TEXT, p_satir_sayisi BIGINT, p_bayt_sayisi BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_calisma calistirma%ROWTYPE;
    v_adim_id BIGINT;
    v_yeni_durum TEXT;
    v_hedef_durum TEXT;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_sonuc NOT IN ('PUBLISHED', 'NOT_PUBLISHED', 'CONFLICT') THEN
        RAISE EXCEPTION 'Mutabakat sonucu desteklenmiyor';
    END IF;
    SELECT c.* INTO v_calisma
      FROM calistirma c
     WHERE c.uuid = p_calistirma_uuid;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
     WHERE cd.proje_id = v_calisma.proje_id
       AND cd.calistirma_id = v_calisma.id
     FOR UPDATE;
    IF NOT FOUND OR v_durum.durum_kodu <> 'MUTABAKAT'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_durum.hedef_nesil_no <> p_hedef_nesil_no THEN RETURN FALSE; END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id AND uuid = p_hedef_kaynagi_uuid
     FOR UPDATE;
    IF NOT FOUND OR v_hedef.durum_kodu <> 'ASKIDA'
       OR v_hedef.nesil_no <> p_hedef_nesil_no THEN RETURN FALSE; END IF;
    IF p_sonuc = 'PUBLISHED' THEN
        IF p_runtime_plan_ozeti !~ '^[0-9a-f]{64}$'
           OR p_yayin_anahtari_ozeti !~ '^[0-9a-f]{64}$'
           OR p_payload_ozeti !~ '^[0-9a-f]{64}$'
           OR p_satir_sayisi < 0 OR p_bayt_sayisi < 0
           OR NOT EXISTS (
                SELECT 1 FROM is_talebi it
                JOIN yayin y ON y.proje_id = it.proje_id AND y.id = it.yayin_id
                WHERE it.id = v_calisma.is_talebi_id
                  AND y.fiziksel_manifesto ->> 'runtimeCapability' = 'ORACLE_TABLE_COPY_V1'
                  AND y.fiziksel_manifesto ->> 'runtimePlanHash' = p_runtime_plan_ozeti
                  AND y.release_hash = v_calisma.yayin_ozeti) THEN
            RETURN FALSE;
        END IF;
        SELECT id INTO v_adim_id FROM calistirma_adimi
         WHERE calistirma_id = v_calisma.id AND adim_kodu = 'PILOT_PUBLISH';
        IF NOT FOUND THEN RETURN FALSE; END IF;
        INSERT INTO kontrol_noktasi(
            proje_id, calistirma_id, calistirma_adimi_id, kapsam_ozeti,
            bolum_kodu, sira_no, paket_anahtari, hedef_defter_referansi,
            tur_kodu, dogrulama_zamani, imlec,
            hedef_kaynagi_id, hedef_nesil_no, yayin_ozeti, plan_ozeti,
            payload_ozeti)
        VALUES (v_calisma.proje_id, v_calisma.id, v_adim_id, p_runtime_plan_ozeti,
                'FULL', 1, p_yayin_anahtari_ozeti, p_yayin_anahtari_ozeti,
                'PUBLISH', v_simdi,
                jsonb_build_object('rowCount', p_satir_sayisi,
                                   'byteCount', p_bayt_sayisi,
                                   'reconciled', TRUE),
                v_hedef.id, p_hedef_nesil_no, v_calisma.yayin_ozeti,
                v_calisma.plan_ozeti, p_payload_ozeti);
    END IF;
    v_yeni_durum := CASE p_sonuc
        WHEN 'PUBLISHED' THEN 'BASARILI'
        WHEN 'NOT_PUBLISHED' THEN 'YENIDEN_DENENEBILIR'
        ELSE 'MUDAHALE_GEREKLI' END;
    v_hedef_durum := CASE WHEN p_sonuc IN ('PUBLISHED', 'NOT_PUBLISHED')
        THEN 'BOS' ELSE 'ASKIDA' END;
    IF v_hedef_durum = 'BOS' THEN
        UPDATE hedef_kaynagi
           SET durum_kodu = 'BOS', guncellenme_zamani = v_simdi,
               versiyon_no = v_hedef.versiyon_no + 1
         WHERE id = v_hedef.id;
    END IF;
    UPDATE calistirma_durumu
       SET durum_kodu = v_yeni_durum, son_olay_no = v_durum.son_olay_no + 1,
           kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
           guncellenme_zamani = v_simdi, versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;
    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1, 'RECONCILIATION_COMPLETED', v_simdi,
            jsonb_build_object('outcome', p_sonuc,
                               'targetStatus', v_hedef_durum));
    RETURN TRUE;
END;
$$;

CREATE FUNCTION suresi_dolan_mutabakatlari_sonlandir(p_limit INTEGER DEFAULT 100)
RETURNS TABLE (calistirma_uuid UUID, hedef_kaynagi_uuid UUID, hedef_nesil_no BIGINT)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_limit < 1 OR p_limit > 1000 THEN RAISE EXCEPTION 'Reaper limiti geçersiz'; END IF;
    FOR v_durum IN
        SELECT * FROM calistirma_durumu
         WHERE durum_kodu = 'MUTABAKAT'
           AND kiralama_bitis_zamani <= v_simdi
         ORDER BY kiralama_bitis_zamani, id
         FOR UPDATE SKIP LOCKED LIMIT p_limit
    LOOP
        SELECT * INTO v_hedef FROM hedef_kaynagi
         WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;
        IF NOT FOUND OR v_hedef.durum_kodu <> 'ASKIDA'
           OR v_hedef.nesil_no <> v_durum.hedef_nesil_no THEN CONTINUE; END IF;
        UPDATE calistirma_durumu
           SET durum_kodu = 'MUDAHALE_GEREKLI',
               son_olay_no = v_durum.son_olay_no + 1,
               kiralama_bitis_zamani = NULL, bitis_zamani = v_simdi,
               guncellenme_zamani = v_simdi,
               versiyon_no = v_durum.versiyon_no + 1
         WHERE id = v_durum.id;
        INSERT INTO calistirma_olayi(
            proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
        VALUES (v_durum.proje_id, v_durum.calistirma_id,
                v_durum.son_olay_no + 1, 'RECONCILIATION_LEASE_EXPIRED', v_simdi,
                jsonb_build_object('targetGeneration', v_durum.hedef_nesil_no));
        RETURN QUERY SELECT c.uuid, v_hedef.uuid, v_hedef.nesil_no
         FROM calistirma c WHERE c.id = v_durum.calistirma_id;
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION pilot_calistirma_asamasina_gec(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_calismaya_baslat(UUID, TEXT, BIGINT, UUID, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_yayina_gec(UUID, TEXT, BIGINT, UUID, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_guvenli_hata_ile_sonlandir(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_sonucu_belirsiz_isaretle(
    UUID, TEXT, BIGINT, UUID, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_basarili_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_mutabakat_sahiplen(UUID, UUID, TEXT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_mutabakat_yasam_sinyali(UUID, TEXT, BIGINT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_mutabakat_sonlandir(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT, TEXT, BIGINT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION suresi_dolan_mutabakatlari_sonlandir(INTEGER) FROM PUBLIC;

COMMENT ON FUNCTION calistirma_basarili_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) IS
    'Oracle publish verify kanıtını checkpoint ile sabitler, runı tamamlar ve hedefi atomik bırakır';
COMMENT ON FUNCTION calistirma_mutabakat_sonlandir(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT, TEXT, BIGINT, BIGINT) IS
    'Fence bariyeri sonrası exact marker, marker yok veya conflict sonucunu atomik tamamlar';
