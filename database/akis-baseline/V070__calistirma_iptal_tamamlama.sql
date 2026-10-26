SET search_path TO akis, public;

-- Called only after the owning runtime has unwound its sessions. This acknowledgement
-- never renews a lease or grants permission to execute work, including after lease expiry.
CREATE FUNCTION calistirma_iptal_tamamla(
    p_calistirma_uuid UUID, p_isleyici_referansi TEXT, p_nesil_no BIGINT)
RETURNS BOOLEAN LANGUAGE plpgsql SET search_path=akis,public AS $$
DECLARE
    v_c calistirma%ROWTYPE;
    v_d calistirma_durumu%ROWTYPE;
    v_h hedef_kaynagi%ROWTYPE;
    v_belirsiz BOOLEAN;
    v_durum VARCHAR(30);
    v_hedef_degisti BOOLEAN := FALSE;
    v_zaman TIMESTAMPTZ;
BEGIN
    IF p_calistirma_uuid IS NULL OR p_isleyici_referansi IS NULL
       OR length(btrim(p_isleyici_referansi))=0 OR length(p_isleyici_referansi)>200
       OR p_nesil_no IS NULL OR p_nesil_no<=0 THEN RETURN FALSE; END IF;

    -- Do not lock immutable run metadata: cancellation holds the state row while its
    -- event insert takes FK KEY SHARE on the run. An exclusive run lock would invert that order.
    SELECT * INTO v_c FROM calistirma WHERE uuid=p_calistirma_uuid;
    IF NOT FOUND THEN RETURN FALSE; END IF;
    -- ExecutionService.cancel locks this same state row before appending its request event.
    SELECT * INTO v_d FROM calistirma_durumu
     WHERE proje_id=v_c.proje_id AND calistirma_id=v_c.id FOR UPDATE;
    IF NOT FOUND OR v_d.nesil_no IS DISTINCT FROM p_nesil_no
       OR v_d.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi THEN RETURN FALSE; END IF;

    IF v_d.durum IN ('IPTAL','SONUC_BELIRSIZ') THEN
        RETURN v_d.kiralama_bitis_zamani IS NULL AND v_d.bitis_zamani IS NOT NULL AND EXISTS (
            SELECT 1 FROM calistirma_olayi e
             WHERE e.proje_id=v_c.proje_id AND e.calistirma_id=v_c.id
               AND e.olay_no=v_d.son_olay_no AND e.tur='RUN_CANCELLATION_COMPLETED'
               AND e.veri->>'generation'=p_nesil_no::text
               AND e.veri->>'workerReference'=p_isleyici_referansi
               AND e.veri->>'status'=v_d.durum);
    END IF;
    IF v_d.durum<>'IPTAL_ISTENDI' THEN RETURN FALSE; END IF;

    SELECT EXISTS (
        SELECT 1 FROM pilot_yayin_niyeti p
         WHERE p.proje_id=v_c.proje_id AND p.calistirma_id=v_c.id)
        -- Procedure writes also have durable intent; an unconfirmed write cannot be called clean cancellation.
        OR EXISTS (
            SELECT 1 FROM prosedur_adim_niyeti n
            JOIN prosedur_adim_durumu s ON s.proje_id=n.proje_id
             AND s.calistirma_id=n.calistirma_id AND s.calistirma_adimi_id=n.calistirma_adimi_id
             WHERE n.proje_id=v_c.proje_id AND n.calistirma_id=v_c.id
               AND n.calistirma_nesil_no=p_nesil_no AND n.isleyici_referansi=p_isleyici_referansi
               AND s.transaction_outcome NOT IN ('COMMIT_CONFIRMED','ROLLBACK_CONFIRMED')
               AND (s.durum IN ('CALISIYOR','SONUC_BELIRSIZ')
                    OR s.transaction_outcome IN ('EXECUTED_UNCOMMITTED','OUTCOME_UNKNOWN')))
        OR EXISTS (
            SELECT 1 FROM islem_grubu_kaniti k JOIN islem_grubu_durumu s ON s.islem_grubu_kaniti_id=k.id
             WHERE k.proje_id=v_c.proje_id AND k.calistirma_id=v_c.id
               AND s.outcome IN ('EXECUTED_UNCOMMITTED','OUTCOME_UNKNOWN'))
      INTO v_belirsiz;
    v_durum := CASE WHEN v_belirsiz THEN 'SONUC_BELIRSIZ' ELSE 'IPTAL' END;
    v_zaman := clock_timestamp();

    IF v_d.hedef_kaynagi_id IS NOT NULL THEN
        SELECT * INTO v_h FROM hedef_kaynagi WHERE id=v_d.hedef_kaynagi_id FOR UPDATE;
        IF FOUND AND v_h.durum='SAHIPLENILDI' AND v_h.calistirma_id=v_c.id
           AND v_h.nesil_no=v_d.hedef_nesil_no THEN
            UPDATE hedef_kaynagi SET durum=CASE WHEN v_belirsiz THEN 'ASKIDA' ELSE 'BOS' END,
                calistirma_id=NULL, kiralama_bitis_zamani=NULL,
                guncellenme_zamani=v_zaman, versiyon_no=versiyon_no+1
             WHERE id=v_h.id RETURNING * INTO v_h;
            v_hedef_degisti := TRUE;
        END IF;
    END IF;

    UPDATE km_step_journal SET
        state=CASE WHEN v_belirsiz AND operation='ATOMIC_REPLACE' THEN 'UNKNOWN' ELSE 'FAILED' END,
        error_code=CASE WHEN v_belirsiz AND operation='ATOMIC_REPLACE'
                       THEN 'KM_RECONCILIATION_REQUIRED' ELSE 'USER_CANCEL' END,
        completed_at=v_zaman
     WHERE proje_id=v_c.proje_id AND calistirma_id=v_c.id AND generation=p_nesil_no
       AND worker_reference=p_isleyici_referansi AND state='RUNNING';

    UPDATE prosedur_adim_durumu SET
        durum=CASE WHEN v_belirsiz THEN 'SONUC_BELIRSIZ' ELSE 'BASARISIZ' END,
        transaction_outcome=CASE WHEN v_belirsiz AND transaction_outcome NOT IN ('COMMIT_CONFIRMED','ROLLBACK_CONFIRMED')
                                 THEN 'OUTCOME_UNKNOWN' ELSE transaction_outcome END,
        bitis_zamani=v_zaman, hata_kodu='USER_CANCEL', son_olay_no=v_d.son_olay_no+1,
        guncellenme_zamani=v_zaman, versiyon_no=versiyon_no+1
     WHERE proje_id=v_c.proje_id AND calistirma_id=v_c.id AND durum='CALISIYOR';

    UPDATE islem_grubu_durumu s SET outcome='OUTCOME_UNKNOWN', sonuc_zamani=v_zaman,
        hata_kodu='USER_CANCEL', guncellenme_zamani=v_zaman, versiyon_no=s.versiyon_no+1
      FROM islem_grubu_kaniti k WHERE k.id=s.islem_grubu_kaniti_id
       AND k.proje_id=v_c.proje_id AND k.calistirma_id=v_c.id AND s.outcome='EXECUTED_UNCOMMITTED';

    UPDATE calistirma_durumu SET durum=v_durum, kiralama_bitis_zamani=NULL,
        bitis_zamani=v_zaman, son_olay_no=son_olay_no+1,
        guncellenme_zamani=v_zaman, versiyon_no=versiyon_no+1
     WHERE id=v_d.id RETURNING * INTO v_d;
    INSERT INTO calistirma_olayi(proje_id,calistirma_id,olay_no,tur,olay_zamani,veri)
    VALUES(v_c.proje_id,v_c.id,v_d.son_olay_no,'RUN_CANCELLATION_COMPLETED',v_zaman,
        jsonb_build_object('generation',p_nesil_no,'workerReference',p_isleyici_referansi,
            'status',v_durum,'errorCode','USER_CANCEL','requiresReconciliation',v_belirsiz,
            'targetResourceUuid',v_h.uuid,'targetGeneration',v_d.hedef_nesil_no,
            'targetStatus',v_h.durum,'targetChanged',v_hedef_degisti));
    RETURN TRUE;
END $$;

REVOKE ALL ON FUNCTION calistirma_iptal_tamamla(UUID,TEXT,BIGINT) FROM PUBLIC;

-- Persisted IPTAL_ISTENDI is the durable retry source, including after worker restart.
-- Only expired work authority is eligible. Status already fences new publish/dispatch;
-- existing write intents retain the conservative unknown/quarantined result above.
-- Called before each worker claim; bounded rows, SKIP LOCKED and transaction timeout
-- avoid an unbounded queue, dedicated retry threads, or blocking behind another request.
CREATE FUNCTION calistirma_suresi_dolan_iptalleri_tamamla(p_limit INTEGER)
RETURNS INTEGER LANGUAGE plpgsql SET search_path=akis,public AS $$
DECLARE v_r RECORD; v_sayi INTEGER := 0;
BEGIN
    IF p_limit IS NULL OR p_limit<1 OR p_limit>100 THEN RETURN 0; END IF;
    FOR v_r IN
        SELECT c.uuid, d.isleyici_referansi, d.nesil_no
          FROM calistirma_durumu d JOIN calistirma c ON c.id=d.calistirma_id AND c.proje_id=d.proje_id
         WHERE d.durum='IPTAL_ISTENDI' AND d.kiralama_bitis_zamani<=clock_timestamp()
           AND d.isleyici_referansi IS NOT NULL AND d.nesil_no>0
         ORDER BY d.kiralama_bitis_zamani,d.id LIMIT p_limit
         FOR UPDATE OF d SKIP LOCKED
    LOOP
        IF calistirma_iptal_tamamla(v_r.uuid,v_r.isleyici_referansi,v_r.nesil_no) THEN
            v_sayi := v_sayi+1;
        END IF;
    END LOOP;
    RETURN v_sayi;
END $$;

REVOKE ALL ON FUNCTION calistirma_suresi_dolan_iptalleri_tamamla(INTEGER) FROM PUBLIC;
