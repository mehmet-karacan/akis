SET search_path TO entegrasyon, public;

-- A future unified dispatcher must select work before choosing a runtime.
-- Keep the original calistirma_sahiplen API immutable for the existing pilot.
CREATE FUNCTION calistirma_yeteneklerle_sahiplen(
    p_worker_profili_uuid UUID,
    p_isleyici_referansi TEXT,
    p_runtime_yetenekleri TEXT[],
    p_lease_saniyesi INTEGER DEFAULT 60)
RETURNS TABLE (
    calistirma_uuid UUID,
    nesil_no BIGINT,
    yayin_ozeti TEXT,
    plan_ozeti TEXT,
    kiralama_bitis_zamani TIMESTAMPTZ,
    runtime_yetenegi TEXT)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_worker_profili_id BIGINT;
    v_durum calistirma_durumu%ROWTYPE;
    v_runtime_yetenegi TEXT;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_worker_profili_uuid IS NULL THEN
        RAISE EXCEPTION 'Worker profili UUID zorunludur';
    END IF;
    IF p_isleyici_referansi IS NULL
       OR length(btrim(p_isleyici_referansi)) NOT BETWEEN 1 AND 200 THEN
        RAISE EXCEPTION 'İşleyici referansı geçersizdir';
    END IF;
    IF p_lease_saniyesi IS NULL
       OR p_lease_saniyesi < 30 OR p_lease_saniyesi > 300 THEN
        RAISE EXCEPTION 'Lease süresi 30 ile 300 saniye arasında olmalıdır';
    END IF;
    IF p_runtime_yetenekleri IS NULL
       OR array_ndims(p_runtime_yetenekleri) IS DISTINCT FROM 1
       OR array_lower(p_runtime_yetenekleri, 1) IS DISTINCT FROM 1
       OR cardinality(p_runtime_yetenekleri) NOT BETWEEN 1 AND 2 THEN
        RAISE EXCEPTION 'Runtime yetenek listesi 1 ile 2 öğe arasında olmalıdır';
    END IF;
    IF EXISTS (
        SELECT 1 FROM unnest(p_runtime_yetenekleri) AS yetenek(kod)
         WHERE kod IS NULL
            OR kod NOT IN ('ORACLE_TABLE_COPY_V1', 'ORACLE_PROCEDURE_V1'))
       OR (SELECT count(DISTINCT kod)
             FROM unnest(p_runtime_yetenekleri) AS yetenek(kod))
          <> cardinality(p_runtime_yetenekleri) THEN
        RAISE EXCEPTION 'Runtime yetenek listesi kanonik ve desteklenen kodlardan oluşmalıdır';
    END IF;

    SELECT id INTO v_worker_profili_id
      FROM worker_profili
     WHERE uuid = p_worker_profili_uuid AND durum_kodu = 'AKTIF';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Aktif worker profili bulunamadı';
    END IF;

    -- Use the same serialization identity as the original exact-ACK claim API.
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

        SELECT y.fiziksel_manifesto ->> 'runtimeCapability'
          INTO v_runtime_yetenegi
          FROM calistirma c
          JOIN is_talebi it
            ON it.proje_id = c.proje_id AND it.id = c.is_talebi_id
          JOIN yayin y
            ON y.proje_id = it.proje_id AND y.id = it.yayin_id
         WHERE c.id = v_durum.calistirma_id;
        IF NOT FOUND
           OR v_runtime_yetenegi IS NULL
           OR NOT (v_runtime_yetenegi = ANY(p_runtime_yetenekleri)) THEN
            RAISE EXCEPTION 'Aktif preflight claim istenen runtime yeteneğiyle eşleşmiyor';
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
               AND co.veri ? 'runtimeCapability'
               AND co.veri ->> 'generation' = v_durum.nesil_no::TEXT
               AND co.veri ->> 'workerProfileUuid' = p_worker_profili_uuid::TEXT
               AND co.veri ->> 'workerReference' = p_isleyici_referansi
               AND co.veri ->> 'releaseHash' = c.yayin_ozeti
               AND co.veri ->> 'planHash' = c.plan_ozeti
               AND co.veri ->> 'runtimeCapability' = v_runtime_yetenegi) THEN
            RAISE EXCEPTION 'Aktif capability claim exact ACK kanıtı taşımıyor';
        END IF;

        RETURN QUERY
        SELECT c.uuid, v_durum.nesil_no, c.yayin_ozeti, c.plan_ozeti,
               v_durum.kiralama_bitis_zamani, v_runtime_yetenegi
          FROM calistirma c
         WHERE c.id = v_durum.calistirma_id;
        RETURN;
    END IF;

    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c
        ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
      JOIN is_talebi it
        ON it.proje_id = c.proje_id AND it.id = c.is_talebi_id
      JOIN yayin y
        ON y.proje_id = it.proje_id AND y.id = it.yayin_id
      JOIN senaryo s ON s.id = y.senaryo_id
     WHERE cd.durum_kodu = 'BEKLIYOR'
       AND it.is_turu = 'RUN'
       AND y.durum_kodu = 'AKTIF'
       AND y.fiziksel_manifesto ->> 'runtimeCapability'
            = ANY(p_runtime_yetenekleri)
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

    SELECT y.fiziksel_manifesto ->> 'runtimeCapability'
      INTO STRICT v_runtime_yetenegi
      FROM calistirma c
      JOIN is_talebi it
        ON it.proje_id = c.proje_id AND it.id = c.is_talebi_id
      JOIN yayin y
        ON y.proje_id = it.proje_id AND y.id = it.yayin_id
     WHERE c.id = v_durum.calistirma_id;

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
               'planHash', c.plan_ozeti,
               'runtimeCapability', v_runtime_yetenegi)
      FROM calistirma c
     WHERE c.id = v_durum.calistirma_id;

    RETURN QUERY
    SELECT c.uuid, cd.nesil_no, c.yayin_ozeti, c.plan_ozeti,
           cd.kiralama_bitis_zamani, v_runtime_yetenegi
      FROM calistirma c
      JOIN calistirma_durumu cd
        ON cd.proje_id = c.proje_id AND cd.calistirma_id = c.id
     WHERE c.id = v_durum.calistirma_id;
END;
$$;

REVOKE ALL ON FUNCTION calistirma_yeteneklerle_sahiplen(
    UUID, TEXT, TEXT[], INTEGER) FROM PUBLIC;

COMMENT ON FUNCTION calistirma_yeteneklerle_sahiplen(
    UUID, TEXT, TEXT[], INTEGER) IS
    'Kanonik ve sınırlı runtime capability listesine göre tek run claim eder; kayıp ACK tekrarında aynı capability kanıtını döndürür';
