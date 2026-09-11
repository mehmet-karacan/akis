SET search_path TO entegrasyon, public;

-- Every worker mutation below has a durable, exact acknowledgement branch.
-- A retry may observe the state committed by its first call, but it must never
-- advance a generation, acquire a second run/target, or accept weaker evidence.

CREATE OR REPLACE FUNCTION calistirma_sahiplen(
    p_worker_profili_uuid UUID,
    p_isleyici_referansi TEXT,
    p_lease_saniyesi INTEGER DEFAULT 60)
RETURNS TABLE (
    calistirma_uuid UUID,
    nesil_no BIGINT,
    yayin_ozeti TEXT,
    plan_ozeti TEXT,
    kiralama_bitis_zamani TIMESTAMPTZ)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_worker_profili_id BIGINT;
    v_durum calistirma_durumu%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_isleyici_referansi IS NULL OR btrim(p_isleyici_referansi) = '' THEN
        RAISE EXCEPTION 'İşleyici referansı zorunludur';
    END IF;
    IF p_lease_saniyesi < 30 OR p_lease_saniyesi > 300 THEN
        RAISE EXCEPTION 'Lease süresi 30 ile 300 saniye arasında olmalıdır';
    END IF;

    SELECT id INTO v_worker_profili_id
      FROM worker_profili
     WHERE uuid = p_worker_profili_uuid AND durum_kodu = 'AKTIF';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Aktif worker profili bulunamadı';
    END IF;

    -- Serialize the first claim and its acknowledgement retry for this exact
    -- profile/reference pair. A hash collision only causes harmless contention.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        v_worker_profili_id::TEXT || ':' || p_isleyici_referansi, 0));

    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
     WHERE cd.worker_profili_id = v_worker_profili_id
       AND cd.isleyici_referansi = p_isleyici_referansi
       AND cd.durum_kodu = 'HAZIRLANIYOR'
       AND cd.kiralama_bitis_zamani > v_simdi
       AND cd.hedef_kaynagi_id IS NULL
       AND cd.hedef_nesil_no IS NULL
     ORDER BY cd.id
     FOR UPDATE OF cd
     LIMIT 1;

    IF FOUND THEN
        IF EXISTS (
            SELECT 1
              FROM calistirma_durumu cd2
             WHERE cd2.id <> v_durum.id
               AND cd2.worker_profili_id = v_worker_profili_id
               AND cd2.isleyici_referansi = p_isleyici_referansi
               AND cd2.durum_kodu = 'HAZIRLANIYOR'
               AND cd2.kiralama_bitis_zamani > v_simdi
               AND cd2.hedef_kaynagi_id IS NULL
               AND cd2.hedef_nesil_no IS NULL) THEN
            RAISE EXCEPTION 'Aynı worker için birden fazla aktif preflight claim bulundu';
        END IF;

        IF NOT EXISTS (
            SELECT 1
              FROM calistirma c
              JOIN calistirma_olayi co
                ON co.proje_id = c.proje_id
               AND co.calistirma_id = c.id
               AND co.olay_no = v_durum.son_olay_no
             WHERE c.id = v_durum.calistirma_id
               AND co.tur_kodu = 'RUN_CLAIMED'
               AND co.veri ? 'generation'
               AND co.veri ? 'workerProfileUuid'
               AND co.veri ? 'workerReference'
               AND co.veri ->> 'generation' = v_durum.nesil_no::TEXT
               AND co.veri ->> 'workerProfileUuid' = p_worker_profili_uuid::TEXT
               AND co.veri ->> 'workerReference' = p_isleyici_referansi
               AND co.veri ->> 'releaseHash' = c.yayin_ozeti
               AND co.veri ->> 'planHash' = c.plan_ozeti) THEN
            RAISE EXCEPTION 'Aktif preflight claim exact ACK kanıtı taşımıyor';
        END IF;

        RETURN QUERY
        SELECT c.uuid, v_durum.nesil_no, c.yayin_ozeti, c.plan_ozeti,
               v_durum.kiralama_bitis_zamani
          FROM calistirma c
         WHERE c.id = v_durum.calistirma_id;
        RETURN;
    END IF;

    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
      JOIN is_talebi it ON it.proje_id = c.proje_id AND it.id = c.is_talebi_id
      JOIN yayin y ON y.proje_id = it.proje_id AND y.id = it.yayin_id
      JOIN senaryo s ON s.id = y.senaryo_id
     WHERE cd.durum_kodu = 'BEKLIYOR'
       AND it.is_turu = 'RUN'
       AND y.durum_kodu = 'AKTIF'
       AND c.yayin_ozeti = y.release_hash
       AND c.plan_ozeti = s.plan_ozeti
       AND (it.planlanan_zamani IS NULL OR it.planlanan_zamani <= v_simdi)
     ORDER BY it.oncelik DESC,
              COALESCE(it.planlanan_zamani, it.olusturulma_zamani),
              it.id
     FOR UPDATE OF cd SKIP LOCKED
     LIMIT 1;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    UPDATE calistirma_durumu
       SET worker_profili_id = v_worker_profili_id,
           isleyici_referansi = p_isleyici_referansi,
           durum_kodu = 'HAZIRLANIYOR',
           nesil_no = v_durum.nesil_no + 1,
           son_olay_no = v_durum.son_olay_no + 1,
           kiralama_bitis_zamani = v_simdi + make_interval(secs => p_lease_saniyesi),
           yasam_sinyali_zamani = v_simdi,
           baslama_zamani = v_simdi,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;

    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    SELECT v_durum.proje_id, v_durum.calistirma_id,
           v_durum.son_olay_no + 1, 'RUN_CLAIMED', v_simdi,
           jsonb_build_object(
               'generation', v_durum.nesil_no + 1,
               'workerProfileUuid', p_worker_profili_uuid,
               'workerReference', p_isleyici_referansi,
               'releaseHash', c.yayin_ozeti,
               'planHash', c.plan_ozeti)
      FROM calistirma c
     WHERE c.id = v_durum.calistirma_id;

    RETURN QUERY
    SELECT c.uuid, cd.nesil_no, c.yayin_ozeti, c.plan_ozeti,
           cd.kiralama_bitis_zamani
      FROM calistirma c
      JOIN calistirma_durumu cd
        ON cd.proje_id = c.proje_id AND cd.calistirma_id = c.id
     WHERE c.id = v_durum.calistirma_id;
END;
$$;

CREATE OR REPLACE FUNCTION hedef_kaynagi_sahiplen(
    p_calistirma_uuid UUID,
    p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT,
    p_fiziksel_ozet TEXT,
    p_kimlik_surumu INTEGER DEFAULT 1)
RETURNS TABLE (
    hedef_kaynagi_uuid UUID,
    hedef_nesil_no BIGINT,
    kiralama_bitis_zamani TIMESTAMPTZ)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_fiziksel_ozet !~ '^[0-9a-f]{64}$' OR p_kimlik_surumu <= 0 THEN
        RAISE EXCEPTION 'Kanonik hedef kimliği geçersiz';
    END IF;

    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
     WHERE c.uuid = p_calistirma_uuid
     FOR UPDATE OF cd;

    IF NOT FOUND
       OR v_durum.durum_kodu <> 'HAZIRLANIYOR'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi THEN
        RAISE EXCEPTION 'Çalıştırma geçerli bir preflight lease sahibi değildir';
    END IF;

    IF v_durum.hedef_kaynagi_id IS NOT NULL THEN
        SELECT hk.* INTO v_hedef
          FROM hedef_kaynagi hk
         WHERE hk.id = v_durum.hedef_kaynagi_id
         FOR UPDATE;

        IF NOT FOUND
           OR v_hedef.fiziksel_ozet <> p_fiziksel_ozet
           OR v_hedef.kimlik_surumu <> p_kimlik_surumu
           OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
           OR v_hedef.calistirma_id <> v_durum.calistirma_id
           OR v_hedef.nesil_no <> v_durum.hedef_nesil_no
           OR v_hedef.kiralama_bitis_zamani <= v_simdi
           OR v_hedef.kiralama_bitis_zamani IS DISTINCT FROM
              v_durum.kiralama_bitis_zamani
           OR NOT EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_durum.proje_id
                   AND co.calistirma_id = v_durum.calistirma_id
                   AND co.olay_no = v_durum.son_olay_no
                   AND co.tur_kodu = 'TARGET_ACQUIRED'
                   AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
                   AND co.veri ->> 'targetResourceUuid' = v_hedef.uuid::TEXT
                   AND co.veri ->> 'targetGeneration' = v_hedef.nesil_no::TEXT
                   AND co.veri ->> 'targetIdentityVersion' = p_kimlik_surumu::TEXT
                   AND co.veri ->> 'canonicalTargetHash' = p_fiziksel_ozet) THEN
            RAISE EXCEPTION 'Mevcut hedef claim exact retry kanıtıyla eşleşmiyor';
        END IF;

        RETURN QUERY SELECT v_hedef.uuid, v_hedef.nesil_no,
                            v_hedef.kiralama_bitis_zamani;
        RETURN;
    END IF;

    IF v_durum.hedef_nesil_no IS NOT NULL THEN
        RAISE EXCEPTION 'Çalıştırma hedef fence çifti bozuk';
    END IF;

    INSERT INTO hedef_kaynagi(fiziksel_ozet, kimlik_surumu)
    VALUES (p_fiziksel_ozet, p_kimlik_surumu)
    ON CONFLICT (fiziksel_ozet) DO NOTHING;

    SELECT * INTO v_hedef
      FROM hedef_kaynagi
     WHERE fiziksel_ozet = p_fiziksel_ozet
     FOR UPDATE;

    IF v_hedef.kimlik_surumu <> p_kimlik_surumu THEN
        RAISE EXCEPTION 'Hedef kimlik şeması sürümü eşleşmiyor';
    END IF;
    IF v_hedef.durum_kodu = 'SAHIPLENILDI' THEN
        IF v_hedef.kiralama_bitis_zamani <= v_simdi THEN
            RAISE EXCEPTION 'Süresi dolmuş hedef sahibi mutabakat gerektirir';
        END IF;
        RAISE EXCEPTION 'Hedef başka bir çalıştırma tarafından sahiplenildi';
    END IF;
    IF v_hedef.durum_kodu = 'ASKIDA' THEN
        RAISE EXCEPTION 'Hedef mutabakat veya operasyon nedeniyle askıdadır';
    END IF;

    UPDATE hedef_kaynagi
       SET calistirma_id = v_durum.calistirma_id,
           durum_kodu = 'SAHIPLENILDI',
           nesil_no = v_hedef.nesil_no + 1,
           kiralama_bitis_zamani = v_durum.kiralama_bitis_zamani,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_hedef.versiyon_no + 1
     WHERE id = v_hedef.id;

    UPDATE calistirma_durumu
       SET hedef_kaynagi_id = v_hedef.id,
           hedef_nesil_no = v_hedef.nesil_no + 1,
           son_olay_no = v_durum.son_olay_no + 1,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;

    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (
        v_durum.proje_id, v_durum.calistirma_id, v_durum.son_olay_no + 1,
        'TARGET_ACQUIRED', v_simdi,
        jsonb_build_object(
            'generation', p_calistirma_nesil_no,
            'targetResourceUuid', v_hedef.uuid,
            'targetGeneration', v_hedef.nesil_no + 1,
            'targetIdentityVersion', p_kimlik_surumu,
            'canonicalTargetHash', p_fiziksel_ozet));

    RETURN QUERY
    SELECT hk.uuid, hk.nesil_no, hk.kiralama_bitis_zamani
      FROM hedef_kaynagi hk
     WHERE hk.id = v_hedef.id;
END;
$$;

CREATE OR REPLACE FUNCTION pilot_calistirma_asamasina_gec(
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
    v_preflight_retry BOOLEAN := FALSE;
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

    IF FOUND
       AND p_beklenen_durum = 'HAZIRLANIYOR'
       AND p_yeni_durum = 'CALISIYOR'
       AND p_olay_turu = 'PREFLIGHT_COMPLETED'
       AND v_durum.durum_kodu = 'CALISIYOR' THEN
        v_preflight_retry := TRUE;
    END IF;

    IF NOT FOUND
       OR (NOT v_preflight_retry AND v_durum.durum_kodu <> p_beklenen_durum)
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

    IF v_preflight_retry THEN
        RETURN EXISTS (
            SELECT 1
              FROM calistirma_olayi co
             WHERE co.proje_id = v_durum.proje_id
               AND co.calistirma_id = v_durum.calistirma_id
               AND co.olay_no = v_durum.son_olay_no
               AND co.tur_kodu = 'PREFLIGHT_COMPLETED'
               AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
               AND co.veri ->> 'workerReference' = p_isleyici_referansi
               AND co.veri ->> 'targetResourceUuid' = p_hedef_kaynagi_uuid::TEXT
               AND co.veri ->> 'targetGeneration' = p_hedef_nesil_no::TEXT
               AND (SELECT count(*) FROM calistirma_adimi ca
                     WHERE ca.calistirma_id = v_durum.calistirma_id
                       AND ca.adim_kodu IN ('PILOT_PREFLIGHT', 'PILOT_PUBLISH')) = 2);
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
            jsonb_build_object(
                'generation', p_calistirma_nesil_no,
                'workerReference', p_isleyici_referansi,
                'targetResourceUuid', p_hedef_kaynagi_uuid,
                'targetGeneration', p_hedef_nesil_no));
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION calistirma_guvenli_hata_ile_sonlandir(
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
    v_niyet pilot_yayin_niyeti%ROWTYPE;
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
    IF NOT FOUND THEN RETURN FALSE; END IF;

    SELECT pyn.* INTO v_niyet FROM pilot_yayin_niyeti pyn
     WHERE pyn.calistirma_id = v_durum.calistirma_id;

    IF v_durum.durum_kodu = 'BASARISIZ' THEN
        RETURN v_durum.isleyici_referansi IS NOT DISTINCT FROM p_isleyici_referansi
           AND v_durum.nesil_no = p_calistirma_nesil_no
           AND v_durum.kiralama_bitis_zamani IS NULL
           AND v_durum.bitis_zamani IS NOT NULL
           AND v_durum.hedef_nesil_no IS NOT DISTINCT FROM p_hedef_nesil_no
           AND ((v_durum.hedef_kaynagi_id IS NULL
                 AND p_hedef_kaynagi_uuid IS NULL)
                OR EXISTS (
                    SELECT 1 FROM hedef_kaynagi hk
                     WHERE hk.id = v_durum.hedef_kaynagi_id
                       AND hk.uuid = p_hedef_kaynagi_uuid))
           AND EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_durum.proje_id
                   AND co.calistirma_id = v_durum.calistirma_id
                   AND co.olay_no = v_durum.son_olay_no
                   AND co.tur_kodu = 'RUN_FAILED_SAFE'
                   AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
                   AND co.veri ->> 'workerReference' = p_isleyici_referansi
                   AND (co.veri ->> 'targetResourceUuid') IS NOT DISTINCT FROM
                       p_hedef_kaynagi_uuid::TEXT
                   AND (co.veri ->> 'targetGeneration') IS NOT DISTINCT FROM
                       p_hedef_nesil_no::TEXT
                   AND co.veri ->> 'errorCode' = p_hata_kodu
                   AND co.veri ->> 'rollbackConfirmed' = 'true'
                   AND (co.veri ->> 'runtimePlanHash') IS NOT DISTINCT FROM
                       v_niyet.runtime_plan_ozeti
                   AND (co.veri ->> 'publishKeyHash') IS NOT DISTINCT FROM
                       v_niyet.yayin_anahtari_ozeti
                   AND (co.veri ->> 'payloadHash') IS NOT DISTINCT FROM
                       v_niyet.payload_ozeti
                   AND (co.veri ->> 'rowCount') IS NOT DISTINCT FROM
                       v_niyet.satir_sayisi::TEXT
                   AND (co.veri ->> 'byteCount') IS NOT DISTINCT FROM
                       v_niyet.bayt_sayisi::TEXT);
    END IF;

    IF v_durum.durum_kodu NOT IN ('HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR')
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
           OR v_durum.hedef_nesil_no <> p_hedef_nesil_no
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
            jsonb_build_object(
                'generation', p_calistirma_nesil_no,
                'workerReference', p_isleyici_referansi,
                'targetResourceUuid', p_hedef_kaynagi_uuid,
                'targetGeneration', p_hedef_nesil_no,
                'errorCode', p_hata_kodu,
                'rollbackConfirmed', TRUE,
                'runtimePlanHash', v_niyet.runtime_plan_ozeti,
                'publishKeyHash', v_niyet.yayin_anahtari_ozeti,
                'payloadHash', v_niyet.payload_ozeti,
                'rowCount', v_niyet.satir_sayisi,
                'byteCount', v_niyet.bayt_sayisi));
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION calistirma_sonucu_belirsiz_isaretle(
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
    v_niyet pilot_yayin_niyeti%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
     WHERE c.uuid = p_calistirma_uuid
     FOR UPDATE OF cd;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    SELECT pyn.* INTO v_niyet FROM pilot_yayin_niyeti pyn
     WHERE pyn.calistirma_id = v_durum.calistirma_id;

    IF v_durum.durum_kodu = 'SONUC_BELIRSIZ' THEN
        RETURN v_durum.isleyici_referansi IS NOT DISTINCT FROM p_isleyici_referansi
           AND v_durum.nesil_no = p_calistirma_nesil_no
           AND v_durum.kiralama_bitis_zamani IS NULL
           AND v_durum.bitis_zamani IS NOT NULL
           AND v_durum.hedef_nesil_no = p_hedef_nesil_no
           AND EXISTS (
                SELECT 1 FROM hedef_kaynagi hk
                 WHERE hk.id = v_durum.hedef_kaynagi_id
                   AND hk.uuid = p_hedef_kaynagi_uuid)
           AND EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_durum.proje_id
                   AND co.calistirma_id = v_durum.calistirma_id
                   AND co.olay_no = v_durum.son_olay_no
                   AND co.tur_kodu = 'RESULT_UNCERTAIN'
                   AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
                   AND co.veri ->> 'workerReference' = p_isleyici_referansi
                   AND co.veri ->> 'targetResourceUuid' = p_hedef_kaynagi_uuid::TEXT
                   AND co.veri ->> 'targetGeneration' = p_hedef_nesil_no::TEXT
                   AND co.veri ->> 'requiresReconciliation' = 'true'
                   AND (co.veri ->> 'runtimePlanHash') IS NOT DISTINCT FROM
                       v_niyet.runtime_plan_ozeti
                   AND (co.veri ->> 'publishKeyHash') IS NOT DISTINCT FROM
                       v_niyet.yayin_anahtari_ozeti
                   AND (co.veri ->> 'payloadHash') IS NOT DISTINCT FROM
                       v_niyet.payload_ozeti
                   AND (co.veri ->> 'rowCount') IS NOT DISTINCT FROM
                       v_niyet.satir_sayisi::TEXT
                   AND (co.veri ->> 'byteCount') IS NOT DISTINCT FROM
                       v_niyet.bayt_sayisi::TEXT);
    END IF;

    IF v_durum.durum_kodu NOT IN ('CALISIYOR', 'YAYINLANIYOR', 'IPTAL_ISTENDI')
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
            jsonb_build_object(
                'generation', p_calistirma_nesil_no,
                'workerReference', p_isleyici_referansi,
                'targetResourceUuid', p_hedef_kaynagi_uuid,
                'targetGeneration', p_hedef_nesil_no,
                'requiresReconciliation', TRUE,
                'runtimePlanHash', v_niyet.runtime_plan_ozeti,
                'publishKeyHash', v_niyet.yayin_anahtari_ozeti,
                'payloadHash', v_niyet.payload_ozeti,
                'rowCount', v_niyet.satir_sayisi,
                'byteCount', v_niyet.bayt_sayisi));
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION calistirma_basarili_tamamla(
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
        RETURN v_durum.isleyici_referansi IS NOT DISTINCT FROM p_isleyici_referansi
           AND v_durum.nesil_no = p_calistirma_nesil_no
           AND v_durum.kiralama_bitis_zamani IS NULL
           AND v_durum.bitis_zamani IS NOT NULL
           AND v_durum.hedef_nesil_no = p_hedef_nesil_no
           AND EXISTS (
                SELECT 1 FROM hedef_kaynagi hk
                 WHERE hk.id = v_durum.hedef_kaynagi_id
                   AND hk.uuid = p_hedef_kaynagi_uuid)
           AND EXISTS (
                SELECT 1 FROM pilot_yayin_niyeti pyn
                 WHERE pyn.calistirma_id = v_calisma.id
                   AND pyn.calistirma_nesil_no = p_calistirma_nesil_no
                   AND pyn.isleyici_referansi = p_isleyici_referansi
                   AND pyn.hedef_kaynagi_id = v_durum.hedef_kaynagi_id
                   AND pyn.hedef_nesil_no = p_hedef_nesil_no
                   AND pyn.runtime_plan_ozeti = p_runtime_plan_ozeti
                   AND pyn.yayin_anahtari_ozeti = p_yayin_anahtari_ozeti
                   AND pyn.payload_ozeti = p_payload_ozeti
                   AND pyn.satir_sayisi = p_satir_sayisi
                   AND pyn.bayt_sayisi = p_bayt_sayisi)
           AND EXISTS (
                SELECT 1 FROM kontrol_noktasi kn
                 WHERE kn.calistirma_id = v_calisma.id
                   AND kn.hedef_kaynagi_id = v_durum.hedef_kaynagi_id
                   AND kn.hedef_nesil_no = p_hedef_nesil_no
                   AND kn.kapsam_ozeti = p_runtime_plan_ozeti
                   AND kn.hedef_defter_referansi = p_yayin_anahtari_ozeti
                   AND kn.payload_ozeti = p_payload_ozeti
                   AND kn.imlec ->> 'rowCount' = p_satir_sayisi::TEXT
                   AND kn.imlec ->> 'byteCount' = p_bayt_sayisi::TEXT)
           AND EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_calisma.proje_id
                   AND co.calistirma_id = v_calisma.id
                   AND co.olay_no = v_durum.son_olay_no
                   AND co.tur_kodu = 'RUN_SUCCEEDED'
                   AND co.veri ->> 'generation' = p_calistirma_nesil_no::TEXT
                   AND co.veri ->> 'workerReference' = p_isleyici_referansi
                   AND co.veri ->> 'targetResourceUuid' = p_hedef_kaynagi_uuid::TEXT
                   AND co.veri ->> 'targetGeneration' = p_hedef_nesil_no::TEXT
                   AND co.veri ->> 'runtimePlanHash' = p_runtime_plan_ozeti
                   AND co.veri ->> 'publishKeyHash' = p_yayin_anahtari_ozeti
                   AND co.veri ->> 'payloadHash' = p_payload_ozeti
                   AND co.veri ->> 'rowCount' = p_satir_sayisi::TEXT
                   AND co.veri ->> 'byteCount' = p_bayt_sayisi::TEXT);
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
            jsonb_build_object(
                'generation', p_calistirma_nesil_no,
                'workerReference', p_isleyici_referansi,
                'targetResourceUuid', p_hedef_kaynagi_uuid,
                'targetGeneration', p_hedef_nesil_no,
                'runtimePlanHash', p_runtime_plan_ozeti,
                'publishKeyHash', p_yayin_anahtari_ozeti,
                'payloadHash', p_payload_ozeti,
                'rowCount', p_satir_sayisi,
                'byteCount', p_bayt_sayisi));
    RETURN TRUE;
END;
$$;

REVOKE ALL ON FUNCTION calistirma_sahiplen(UUID, TEXT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION hedef_kaynagi_sahiplen(UUID, TEXT, BIGINT, TEXT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION pilot_calistirma_asamasina_gec(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_guvenli_hata_ile_sonlandir(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_sonucu_belirsiz_isaretle(
    UUID, TEXT, BIGINT, UUID, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_basarili_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) FROM PUBLIC;

COMMENT ON FUNCTION calistirma_sahiplen(UUID, TEXT, INTEGER) IS
    'Kayıp ACK tekrarında exact aktif hedefsiz preflight claimini yeni run almadan döndürür';
COMMENT ON FUNCTION hedef_kaynagi_sahiplen(UUID, TEXT, BIGINT, TEXT, INTEGER) IS
    'Kayıp ACK tekrarında exact aktif target UUID, generation ve deadline kanıtını döndürür';
COMMENT ON FUNCTION calistirma_basarili_tamamla(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) IS
    'Başarı terminal ACK tekrarını token, target, immutable intent, checkpoint, event ve sayımlarla doğrular';
