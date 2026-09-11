SET search_path TO entegrasyon, public;

-- Pin the reconciliation worker into the claim event as well as the current
-- lease row. This gives the expiry reaper an append-only acknowledgement to
-- attest after a worker loses the claim response.
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

    IF v_durum.durum_kodu = 'MUTABAKAT' THEN
        IF v_durum.worker_profili_id <> v_worker_id
           OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
           OR v_durum.kiralama_bitis_zamani <= v_simdi THEN RETURN; END IF;
        SELECT * INTO v_hedef FROM hedef_kaynagi
         WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;
        IF NOT FOUND OR v_hedef.durum_kodu <> 'ASKIDA'
           OR v_hedef.calistirma_id IS NOT NULL
           OR v_hedef.kiralama_bitis_zamani IS NOT NULL
           OR v_hedef.nesil_no <> v_durum.hedef_nesil_no
           OR NOT EXISTS (
                SELECT 1 FROM calistirma_olayi co
                 WHERE co.proje_id = v_durum.proje_id
                   AND co.calistirma_id = v_durum.calistirma_id
                   AND co.olay_no = v_durum.son_olay_no
                   AND co.tur_kodu = 'RECONCILIATION_CLAIMED'
                   AND co.veri ->> 'generation' = v_durum.nesil_no::TEXT
                   AND co.veri ->> 'targetGeneration' = v_hedef.nesil_no::TEXT
                   AND (NOT co.veri ? 'reconciliationWorkerReference'
                        OR co.veri ->> 'reconciliationWorkerReference' =
                           p_isleyici_referansi)) THEN RETURN; END IF;
        RETURN QUERY SELECT v_durum.nesil_no, v_hedef.uuid, v_hedef.nesil_no,
            v_durum.kiralama_bitis_zamani;
        RETURN;
    END IF;

    IF v_durum.durum_kodu <> 'SONUC_BELIRSIZ' THEN RETURN; END IF;
    SELECT * INTO v_hedef FROM hedef_kaynagi
     WHERE id = v_durum.hedef_kaynagi_id FOR UPDATE;
    IF NOT FOUND OR v_hedef.durum_kodu <> 'ASKIDA'
       OR v_hedef.calistirma_id IS NOT NULL
       OR v_hedef.kiralama_bitis_zamani IS NOT NULL
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
            jsonb_build_object(
                'generation', v_durum.nesil_no + 1,
                'publishTargetGeneration', v_hedef.nesil_no,
                'targetGeneration', v_hedef.nesil_no + 1,
                'reconciliationWorkerReference', p_isleyici_referansi));
    RETURN QUERY SELECT v_durum.nesil_no + 1, v_hedef.uuid,
        v_hedef.nesil_no + 1,
        v_simdi + make_interval(secs => p_lease_saniyesi);
END;
$$;

-- The target can be released deterministically only before the durable publish
-- intent exists. The run row is locked before this decision, which also
-- serializes against pilot_yayin_niyeti's insert trigger.
CREATE OR REPLACE FUNCTION suresi_dolan_hedefleri_askiya_al(p_limit INTEGER DEFAULT 100)
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
    v_yeni_durum TEXT;
    v_publish_intent_var BOOLEAN;
    v_publish_intent_token_exact BOOLEAN;
    v_guvenli_pre_publish BOOLEAN;
    v_lease_token_exact BOOLEAN;
    v_invariant_conflict TEXT;
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
        SELECT * INTO v_hedef
          FROM hedef_kaynagi
         WHERE id = v_durum.hedef_kaynagi_id
         FOR UPDATE;

        IF NOT FOUND
           OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
           OR v_hedef.calistirma_id <> v_durum.calistirma_id
           OR v_hedef.nesil_no <> v_durum.hedef_nesil_no THEN
            CONTINUE;
        END IF;

        v_niyet := NULL;
        SELECT pyn.* INTO v_niyet
          FROM pilot_yayin_niyeti pyn
         WHERE pyn.proje_id = v_durum.proje_id
           AND pyn.calistirma_id = v_durum.calistirma_id;
        v_publish_intent_var := FOUND;
        v_publish_intent_token_exact :=
            NOT v_publish_intent_var
            OR (v_niyet.calistirma_nesil_no = v_durum.nesil_no
                AND v_niyet.isleyici_referansi = v_durum.isleyici_referansi
                AND v_niyet.hedef_kaynagi_id = v_hedef.id
                AND v_niyet.hedef_nesil_no = v_hedef.nesil_no
                AND v_niyet.hedef_fiziksel_ozeti = v_hedef.fiziksel_ozet
                AND v_niyet.hedef_kimlik_surumu = v_hedef.kimlik_surumu);

        v_lease_token_exact :=
            v_durum.kiralama_bitis_zamani IS NOT DISTINCT FROM
                v_hedef.kiralama_bitis_zamani
            AND v_durum.kiralama_bitis_zamani <= v_simdi
            AND v_hedef.kiralama_bitis_zamani <= v_simdi;

        v_guvenli_pre_publish :=
            v_durum.durum_kodu IN ('HAZIRLANIYOR', 'CALISIYOR', 'IPTAL_ISTENDI')
            AND NOT v_publish_intent_var
            AND v_lease_token_exact;

        IF v_guvenli_pre_publish THEN
            UPDATE hedef_kaynagi
               SET calistirma_id = NULL,
                   durum_kodu = 'BOS',
                   kiralama_bitis_zamani = NULL,
                   guncellenme_zamani = v_simdi,
                   versiyon_no = v_hedef.versiyon_no + 1
             WHERE id = v_hedef.id;

            UPDATE calistirma_durumu
               SET durum_kodu = CASE
                       WHEN v_durum.durum_kodu = 'IPTAL_ISTENDI' THEN 'IPTAL'
                       ELSE 'BASARISIZ'
                   END,
                   son_olay_no = v_durum.son_olay_no + 1,
                   kiralama_bitis_zamani = NULL,
                   bitis_zamani = v_simdi,
                   guncellenme_zamani = v_simdi,
                   versiyon_no = v_durum.versiyon_no + 1
             WHERE id = v_durum.id;

            INSERT INTO calistirma_olayi(
                proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
            VALUES (
                v_durum.proje_id, v_durum.calistirma_id,
                v_durum.son_olay_no + 1,
                CASE WHEN v_durum.durum_kodu = 'IPTAL_ISTENDI'
                     THEN 'PRE_PUBLISH_CANCEL_LEASE_EXPIRED'
                     ELSE 'PRE_PUBLISH_TARGET_LEASE_EXPIRED'
                END,
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
            v_yeni_durum := 'SONUC_BELIRSIZ';

            v_invariant_conflict := CASE
                WHEN v_durum.durum_kodu = 'YAYINLANIYOR'
                     AND NOT v_publish_intent_var
                    THEN 'PUBLISH_STATE_WITHOUT_INTENT'
                WHEN v_publish_intent_var
                     AND v_durum.durum_kodu <> 'MUTABAKAT'
                     AND NOT v_publish_intent_token_exact
                    THEN 'PUBLISH_INTENT_TOKEN_MISMATCH'
                WHEN NOT v_lease_token_exact
                    THEN 'LEASE_TOKEN_MISMATCH'
                ELSE NULL
            END;

            UPDATE hedef_kaynagi
               SET calistirma_id = NULL,
                   durum_kodu = 'ASKIDA',
                   kiralama_bitis_zamani = NULL,
                   guncellenme_zamani = v_simdi,
                   versiyon_no = v_hedef.versiyon_no + 1
             WHERE id = v_hedef.id;

            UPDATE calistirma_durumu
               SET durum_kodu = v_yeni_durum,
                   son_olay_no = v_durum.son_olay_no + 1,
                   kiralama_bitis_zamani = NULL,
                   bitis_zamani = v_simdi,
                   guncellenme_zamani = v_simdi,
                   versiyon_no = v_durum.versiyon_no + 1
             WHERE id = v_durum.id;

            INSERT INTO calistirma_olayi(
                proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
            VALUES (
                v_durum.proje_id, v_durum.calistirma_id,
                v_durum.son_olay_no + 1, 'LEASE_EXPIRED', v_simdi,
                jsonb_build_object(
                    'generation', v_durum.nesil_no,
                    'workerReference', v_durum.isleyici_referansi,
                    'targetResourceUuid', v_hedef.uuid,
                    'targetGeneration', v_hedef.nesil_no,
                    'canonicalTargetHash', v_hedef.fiziksel_ozet,
                    'targetIdentityVersion', v_hedef.kimlik_surumu,
                    'previousState', v_durum.durum_kodu,
                    'publishIntentPresent', v_publish_intent_var,
                    'invariantConflict', v_invariant_conflict,
                    'publishTargetGeneration', v_niyet.hedef_nesil_no,
                    'runtimePlanHash', v_niyet.runtime_plan_ozeti,
                    'publishKeyHash', v_niyet.yayin_anahtari_ozeti,
                    'payloadHash', v_niyet.payload_ozeti,
                    'rowCount', v_niyet.satir_sayisi,
                    'byteCount', v_niyet.bayt_sayisi,
                    'requiresReconciliation', TRUE));
        END IF;

        RETURN QUERY
        SELECT c.uuid, v_hedef.uuid, v_hedef.nesil_no
          FROM calistirma c
         WHERE c.id = v_durum.calistirma_id;
    END LOOP;
END;
$$;

-- V009 keeps the reconciliation barrier target deliberately ASKIDA with no
-- target lease owner. Its expiry therefore has a dedicated attestation path;
-- the ordinary target lease reaper must never decide this state.
CREATE OR REPLACE FUNCTION suresi_dolan_mutabakatlari_sonlandir(
    p_limit INTEGER DEFAULT 100)
RETURNS TABLE (
    calistirma_uuid UUID,
    hedef_kaynagi_uuid UUID,
    hedef_nesil_no BIGINT)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_niyet pilot_yayin_niyeti%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
    v_claim_turu TEXT;
    v_claim_veri JSONB;
    v_exact BOOLEAN;
    v_conflict TEXT;
BEGIN
    IF p_limit < 1 OR p_limit > 1000 THEN
        RAISE EXCEPTION 'Reaper limiti geçersiz';
    END IF;

    FOR v_durum IN
        SELECT cd.* FROM calistirma_durumu cd
         WHERE cd.durum_kodu = 'MUTABAKAT'
           AND cd.kiralama_bitis_zamani <= v_simdi
         ORDER BY cd.kiralama_bitis_zamani, cd.id
         FOR UPDATE SKIP LOCKED
         LIMIT p_limit
    LOOP
        v_hedef := NULL;
        SELECT hk.* INTO v_hedef FROM hedef_kaynagi hk
         WHERE hk.id = v_durum.hedef_kaynagi_id
         FOR UPDATE;

        v_niyet := NULL;
        SELECT pyn.* INTO v_niyet FROM pilot_yayin_niyeti pyn
         WHERE pyn.proje_id = v_durum.proje_id
           AND pyn.calistirma_id = v_durum.calistirma_id;

        v_claim_turu := NULL;
        v_claim_veri := NULL;
        SELECT co.tur_kodu, co.veri
          INTO v_claim_turu, v_claim_veri
          FROM calistirma_olayi co
         WHERE co.proje_id = v_durum.proje_id
           AND co.calistirma_id = v_durum.calistirma_id
           AND co.olay_no = v_durum.son_olay_no;

        v_exact := COALESCE((
            v_hedef.id IS NOT NULL
            AND v_hedef.durum_kodu = 'ASKIDA'
            AND v_hedef.calistirma_id IS NULL
            AND v_hedef.kiralama_bitis_zamani IS NULL
            AND v_durum.hedef_kaynagi_id = v_hedef.id
            AND v_durum.hedef_nesil_no = v_hedef.nesil_no
            AND v_niyet.id IS NOT NULL
            AND v_niyet.hedef_kaynagi_id = v_hedef.id
            AND v_niyet.hedef_nesil_no + 1 = v_hedef.nesil_no
            AND v_niyet.hedef_fiziksel_ozeti = v_hedef.fiziksel_ozet
            AND v_niyet.hedef_kimlik_surumu = v_hedef.kimlik_surumu
            AND v_niyet.calistirma_nesil_no + 1 = v_durum.nesil_no
            AND EXISTS (
                SELECT 1 FROM calistirma c
                 WHERE c.proje_id = v_durum.proje_id
                   AND c.id = v_durum.calistirma_id
                   AND c.yayin_ozeti = v_niyet.yayin_ozeti
                   AND c.plan_ozeti = v_niyet.plan_ozeti)
            AND v_claim_turu = 'RECONCILIATION_CLAIMED'
            AND v_claim_veri ->> 'generation' = v_durum.nesil_no::TEXT
            AND v_claim_veri ->> 'publishTargetGeneration' =
                v_niyet.hedef_nesil_no::TEXT
            AND v_claim_veri ->> 'targetGeneration' = v_hedef.nesil_no::TEXT
            AND (NOT v_claim_veri ? 'reconciliationWorkerReference'
                 OR v_claim_veri ->> 'reconciliationWorkerReference' =
                    v_durum.isleyici_referansi)), FALSE);

        v_conflict := CASE
            WHEN v_hedef.id IS NULL
                 OR v_hedef.durum_kodu <> 'ASKIDA'
                 OR v_hedef.calistirma_id IS NOT NULL
                 OR v_hedef.kiralama_bitis_zamani IS NOT NULL
                THEN 'RECONCILIATION_TARGET_STATE_MISMATCH'
            WHEN v_niyet.id IS NULL
                THEN 'RECONCILIATION_WITHOUT_INTENT'
            WHEN v_niyet.calistirma_nesil_no + 1 <> v_durum.nesil_no
                 OR v_niyet.hedef_nesil_no + 1 <> v_hedef.nesil_no
                THEN 'RECONCILIATION_GENERATION_MISMATCH'
            WHEN v_niyet.hedef_kaynagi_id <> v_hedef.id
                 OR v_niyet.hedef_fiziksel_ozeti <> v_hedef.fiziksel_ozet
                 OR v_niyet.hedef_kimlik_surumu <> v_hedef.kimlik_surumu
                THEN 'RECONCILIATION_TARGET_IDENTITY_MISMATCH'
            WHEN v_claim_turu IS DISTINCT FROM 'RECONCILIATION_CLAIMED'
                 OR v_claim_veri ->> 'generation' IS DISTINCT FROM
                    v_durum.nesil_no::TEXT
                 OR v_claim_veri ->> 'publishTargetGeneration' IS DISTINCT FROM
                    v_niyet.hedef_nesil_no::TEXT
                 OR v_claim_veri ->> 'targetGeneration' IS DISTINCT FROM
                    v_hedef.nesil_no::TEXT
                 OR (v_claim_veri ? 'reconciliationWorkerReference'
                     AND v_claim_veri ->> 'reconciliationWorkerReference'
                         IS DISTINCT FROM v_durum.isleyici_referansi)
                THEN 'RECONCILIATION_CLAIM_EVENT_MISMATCH'
            WHEN NOT v_exact
                THEN 'RECONCILIATION_EVIDENCE_MISMATCH'
            ELSE NULL
        END;

        UPDATE calistirma_durumu
           SET durum_kodu = 'MUDAHALE_GEREKLI',
               son_olay_no = v_durum.son_olay_no + 1,
               kiralama_bitis_zamani = NULL,
               bitis_zamani = v_simdi,
               guncellenme_zamani = v_simdi,
               versiyon_no = v_durum.versiyon_no + 1
         WHERE id = v_durum.id;

        INSERT INTO calistirma_olayi(
            proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
        VALUES (
            v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1,
            'RECONCILIATION_LEASE_EXPIRED', v_simdi,
            jsonb_build_object(
                'attestationExact', v_exact,
                'invariantConflict', v_conflict,
                'publishRunGeneration', v_niyet.calistirma_nesil_no,
                'generation', v_durum.nesil_no,
                'targetResourceUuid', v_hedef.uuid,
                'publishTargetGeneration', v_niyet.hedef_nesil_no,
                'targetGeneration', v_durum.hedef_nesil_no,
                'publishWorkerReference', v_niyet.isleyici_referansi,
                'reconciliationWorkerReference', v_durum.isleyici_referansi,
                'canonicalTargetHash', v_niyet.hedef_fiziksel_ozeti,
                'targetIdentityVersion', v_niyet.hedef_kimlik_surumu,
                'releaseHash', v_niyet.yayin_ozeti,
                'planHash', v_niyet.plan_ozeti,
                'runtimePlanHash', v_niyet.runtime_plan_ozeti,
                'publishKeyHash', v_niyet.yayin_anahtari_ozeti,
                'payloadHash', v_niyet.payload_ozeti,
                'rowCount', v_niyet.satir_sayisi,
                'byteCount', v_niyet.bayt_sayisi,
                'requiresIntervention', TRUE));

        RETURN QUERY
        SELECT c.uuid, v_hedef.uuid, v_durum.hedef_nesil_no
          FROM calistirma c
         WHERE c.id = v_durum.calistirma_id;
    END LOOP;
END;
$$;

-- Retain the targetless deterministic failure branch while adding the complete
-- lease token to its terminal event evidence.
CREATE OR REPLACE FUNCTION suresi_dolan_hedefsiz_hazirliklari_sonlandir(
    p_limit INTEGER DEFAULT 100)
RETURNS TABLE (
    calistirma_uuid UUID,
    nesil_no BIGINT)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_simdi TIMESTAMPTZ := clock_timestamp();
    v_durum calistirma_durumu%ROWTYPE;
BEGIN
    IF p_limit < 1 OR p_limit > 1000 THEN
        RAISE EXCEPTION 'Reaper limiti 1 ile 1000 arasında olmalıdır';
    END IF;

    FOR v_durum IN
        SELECT cd.*
          FROM calistirma_durumu cd
         WHERE cd.durum_kodu = 'HAZIRLANIYOR'
           AND cd.hedef_kaynagi_id IS NULL
           AND cd.hedef_nesil_no IS NULL
           AND cd.kiralama_bitis_zamani <= v_simdi
         ORDER BY cd.kiralama_bitis_zamani, cd.id
         FOR UPDATE SKIP LOCKED
         LIMIT p_limit
    LOOP
        UPDATE calistirma_durumu
           SET durum_kodu = 'BASARISIZ',
               son_olay_no = v_durum.son_olay_no + 1,
               kiralama_bitis_zamani = NULL,
               bitis_zamani = v_simdi,
               guncellenme_zamani = v_simdi,
               versiyon_no = v_durum.versiyon_no + 1
         WHERE id = v_durum.id;

        INSERT INTO calistirma_olayi(
            proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
        VALUES (
            v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1, 'PREPARATION_LEASE_EXPIRED', v_simdi,
            jsonb_build_object(
                'generation', v_durum.nesil_no,
                'workerReference', v_durum.isleyici_referansi,
                'targetAcquired', FALSE,
                'requiresReconciliation', FALSE));

        RETURN QUERY
        SELECT c.uuid, v_durum.nesil_no
          FROM calistirma c
         WHERE c.id = v_durum.calistirma_id;
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION suresi_dolan_hedefleri_askiya_al(INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION suresi_dolan_hedefsiz_hazirliklari_sonlandir(INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_mutabakat_sahiplen(
    UUID, UUID, TEXT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION suresi_dolan_mutabakatlari_sonlandir(INTEGER) FROM PUBLIC;

COMMENT ON FUNCTION suresi_dolan_hedefleri_askiya_al(INTEGER) IS
    'Publish intent öncesi lease expiry için targetı güvenle bırakır; diğer targetlı expiry durumlarını mutabakata ayırır';
