SET search_path TO entegrasyon, public;

-- Tighten the acknowledgement-loss retry path added in V007. An identical
-- intent is acknowledged only while the exact target UUID and both leases are
-- still current; no state is changed by this idempotent branch.
CREATE OR REPLACE FUNCTION calistirma_yayina_gec(
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
    v_calisma calistirma%ROWTYPE;
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_adim_id BIGINT;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_runtime_plan_ozeti !~ '^[0-9a-f]{64}$'
       OR p_yayin_anahtari_ozeti !~ '^[0-9a-f]{64}$'
       OR p_payload_ozeti !~ '^[0-9a-f]{64}$'
       OR p_satir_sayisi < 0 OR p_bayt_sayisi < 0 THEN
        RAISE EXCEPTION 'Pilot publish intent kanıtı geçersiz';
    END IF;
    SELECT c.* INTO v_calisma FROM calistirma c
     WHERE c.uuid = p_calistirma_uuid;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    SELECT cd.* INTO v_durum FROM calistirma_durumu cd
     WHERE cd.proje_id = v_calisma.proje_id
       AND cd.calistirma_id = v_calisma.id FOR UPDATE;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    IF v_durum.durum_kodu = 'YAYINLANIYOR' THEN
        RETURN EXISTS (
            SELECT 1 FROM pilot_yayin_niyeti pyn
            JOIN hedef_kaynagi hk ON hk.id = pyn.hedef_kaynagi_id
             WHERE pyn.calistirma_id = v_calisma.id
               AND pyn.calistirma_nesil_no = p_calistirma_nesil_no
               AND pyn.isleyici_referansi = p_isleyici_referansi
               AND pyn.hedef_kaynagi_id = v_durum.hedef_kaynagi_id
               AND pyn.hedef_nesil_no = p_hedef_nesil_no
               AND pyn.runtime_plan_ozeti = p_runtime_plan_ozeti
               AND pyn.yayin_anahtari_ozeti = p_yayin_anahtari_ozeti
               AND pyn.payload_ozeti = p_payload_ozeti
               AND pyn.satir_sayisi = p_satir_sayisi
               AND pyn.bayt_sayisi = p_bayt_sayisi
               AND v_durum.nesil_no = p_calistirma_nesil_no
               AND v_durum.isleyici_referansi = p_isleyici_referansi
               AND v_durum.kiralama_bitis_zamani > v_simdi
               AND v_durum.hedef_nesil_no = p_hedef_nesil_no
               AND hk.uuid = p_hedef_kaynagi_uuid
               AND hk.durum_kodu = 'SAHIPLENILDI'
               AND hk.calistirma_id = v_calisma.id
               AND hk.nesil_no = p_hedef_nesil_no
               AND hk.kiralama_bitis_zamani > v_simdi);
    END IF;
    IF v_durum.durum_kodu <> 'CALISIYOR'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_durum.hedef_nesil_no <> p_hedef_nesil_no THEN RETURN FALSE; END IF;
    SELECT hk.* INTO v_hedef FROM hedef_kaynagi hk
     WHERE hk.id = v_durum.hedef_kaynagi_id
       AND hk.uuid = p_hedef_kaynagi_uuid FOR UPDATE;
    IF NOT FOUND OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
       OR v_hedef.calistirma_id <> v_calisma.id
       OR v_hedef.nesil_no <> p_hedef_nesil_no
       OR v_hedef.kiralama_bitis_zamani <= v_simdi THEN RETURN FALSE; END IF;
    SELECT ca.id INTO v_adim_id FROM calistirma_adimi ca
     WHERE ca.calistirma_id = v_calisma.id AND ca.adim_kodu = 'PILOT_PUBLISH';
    IF NOT FOUND THEN RETURN FALSE; END IF;

    INSERT INTO pilot_yayin_niyeti(
        proje_id, is_talebi_id, calistirma_id, calistirma_adimi_id,
        calistirma_nesil_no, isleyici_referansi, deneme_no,
        hedef_kaynagi_id, hedef_nesil_no, hedef_fiziksel_ozeti,
        hedef_kimlik_surumu, yayin_ozeti, plan_ozeti, runtime_plan_ozeti,
        yayin_anahtari_ozeti, payload_ozeti, satir_sayisi, bayt_sayisi)
    VALUES (
        v_calisma.proje_id, v_calisma.is_talebi_id, v_calisma.id, v_adim_id,
        p_calistirma_nesil_no, p_isleyici_referansi, v_calisma.deneme_no,
        v_hedef.id, p_hedef_nesil_no, v_hedef.fiziksel_ozet,
        v_hedef.kimlik_surumu, v_calisma.yayin_ozeti, v_calisma.plan_ozeti,
        p_runtime_plan_ozeti, p_yayin_anahtari_ozeti, p_payload_ozeti,
        p_satir_sayisi, p_bayt_sayisi);

    RETURN pilot_calistirma_asamasina_gec(
        p_calistirma_uuid, p_isleyici_referansi, p_calistirma_nesil_no,
        p_hedef_kaynagi_uuid, p_hedef_nesil_no,
        'CALISIYOR', 'YAYINLANIYOR', 'PUBLISH_STARTED');
END;
$$;

REVOKE ALL ON FUNCTION calistirma_yayina_gec(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) FROM PUBLIC;

COMMENT ON FUNCTION calistirma_yayina_gec(
    UUID, TEXT, BIGINT, UUID, BIGINT, TEXT, TEXT, TEXT, BIGINT, BIGINT) IS
    'Publish intent ekler; idempotent yanıtta exact aktif run ve target fence UUID kanıtını tekrar doğrular';
