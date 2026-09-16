SET search_path TO akis, public;

CREATE OR REPLACE FUNCTION prosedur_adimini_basarili_tamamla(
 p_calistirma_uuid UUID,p_isleyici_referansi TEXT,p_calistirma_nesil_no BIGINT,
 p_hedef_kaynagi_uuid UUID,p_hedef_nesil_no BIGINT,p_adim_kodu TEXT,
 p_satir_sayisi BIGINT,p_bayt_sayisi BIGINT)
RETURNS BOOLEAN LANGUAGE plpgsql SET search_path=akis,public AS $$
DECLARE v_c calistirma%ROWTYPE;v_d calistirma_durumu%ROWTYPE;v_h hedef_kaynagi%ROWTYPE;
 v_a calistirma_adimi%ROWTYPE;v_sd prosedur_adim_durumu%ROWTYPE;v_ref TEXT;
BEGIN
 IF p_satir_sayisi<0 OR p_bayt_sayisi<0 THEN RETURN FALSE;END IF;
 SELECT * INTO v_c FROM calistirma WHERE uuid=p_calistirma_uuid;IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_d FROM calistirma_durumu WHERE calistirma_id=v_c.id FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_h FROM hedef_kaynagi WHERE id=v_d.hedef_kaynagi_id AND uuid=p_hedef_kaynagi_uuid FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_a FROM calistirma_adimi WHERE calistirma_id=v_c.id AND adim_kodu=p_adim_kodu;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_sd FROM prosedur_adim_durumu WHERE calistirma_adimi_id=v_a.id FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 v_ref='statement:'||v_c.uuid::text||':'||v_a.uuid::text;
 IF v_d.durum<>'CALISIYOR' OR v_d.nesil_no<>p_calistirma_nesil_no
    OR v_d.isleyici_referansi<>p_isleyici_referansi OR v_d.kiralama_bitis_zamani<=clock_timestamp()
    OR v_h.durum<>'SAHIPLENILDI' OR v_h.calistirma_id<>v_c.id
    OR v_h.nesil_no<>p_hedef_nesil_no OR v_h.kiralama_bitis_zamani<=clock_timestamp()
    THEN RETURN FALSE;END IF;
 IF v_sd.durum='BASARILI' THEN
   RETURN v_sd.satir_sayisi=p_satir_sayisi AND v_sd.bayt_sayisi=p_bayt_sayisi
      AND v_sd.transaction_outcome='COMMIT_CONFIRMED' AND v_sd.durable_commit_reference=v_ref;
 END IF;
 IF v_sd.durum<>'CALISIYOR' OR v_sd.transaction_outcome<>'NOT_ATTEMPTED'
    THEN RETURN FALSE;END IF;
 UPDATE calistirma_durumu SET son_olay_no=son_olay_no+1 WHERE id=v_d.id RETURNING * INTO v_d;
 UPDATE prosedur_adim_durumu SET durum='BASARILI',son_olay_no=v_d.son_olay_no,
   bitis_zamani=clock_timestamp(),satir_sayisi=p_satir_sayisi,bayt_sayisi=p_bayt_sayisi,
   transaction_outcome='COMMIT_CONFIRMED',durable_commit_reference=v_ref,
   guncellenme_zamani=clock_timestamp(),versiyon_no=versiyon_no+1 WHERE id=v_sd.id;
 INSERT INTO calistirma_olayi(proje_id,calistirma_id,calistirma_adimi_id,olay_no,tur,olay_zamani,veri)
 VALUES(v_c.proje_id,v_c.id,v_a.id,v_d.son_olay_no,'PROCEDURE_TASK_COMMIT_CONFIRMED',clock_timestamp(),
 jsonb_build_object('stepCode',p_adim_kodu,'rowCountExact',p_satir_sayisi::text,
 'byteCountExact',p_bayt_sayisi::text,'commitReference',v_ref));
 RETURN TRUE;
END $$;

CREATE FUNCTION prosedur_adimini_yurutuldu_isaretle(
 p_calistirma_uuid UUID,p_isleyici_referansi TEXT,p_calistirma_nesil_no BIGINT,
 p_hedef_kaynagi_uuid UUID,p_hedef_nesil_no BIGINT,p_adim_kodu TEXT,
 p_satir_sayisi BIGINT,p_bayt_sayisi BIGINT)
RETURNS BOOLEAN LANGUAGE plpgsql SET search_path=akis,public AS $$
DECLARE v_c calistirma%ROWTYPE;v_d calistirma_durumu%ROWTYPE;v_h hedef_kaynagi%ROWTYPE;
 v_a calistirma_adimi%ROWTYPE;v_sd prosedur_adim_durumu%ROWTYPE;
BEGIN
 IF p_satir_sayisi<0 OR p_bayt_sayisi<0 THEN RETURN FALSE;END IF;
 SELECT * INTO v_c FROM calistirma WHERE uuid=p_calistirma_uuid;IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_d FROM calistirma_durumu WHERE calistirma_id=v_c.id FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_h FROM hedef_kaynagi WHERE id=v_d.hedef_kaynagi_id AND uuid=p_hedef_kaynagi_uuid FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_a FROM calistirma_adimi WHERE calistirma_id=v_c.id AND adim_kodu=p_adim_kodu;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_sd FROM prosedur_adim_durumu WHERE calistirma_adimi_id=v_a.id FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 IF v_d.durum<>'CALISIYOR' OR v_d.nesil_no<>p_calistirma_nesil_no
    OR v_d.isleyici_referansi<>p_isleyici_referansi OR v_d.kiralama_bitis_zamani<=clock_timestamp()
    OR v_h.durum<>'SAHIPLENILDI' OR v_h.calistirma_id<>v_c.id
    OR v_h.nesil_no<>p_hedef_nesil_no OR v_h.kiralama_bitis_zamani<=clock_timestamp()
    OR v_sd.durum<>'CALISIYOR' THEN RETURN FALSE;END IF;
 IF v_sd.transaction_outcome='EXECUTED_UNCOMMITTED' THEN
   RETURN v_sd.satir_sayisi=p_satir_sayisi AND v_sd.bayt_sayisi=p_bayt_sayisi;
 END IF;
 IF v_sd.transaction_outcome<>'NOT_ATTEMPTED' THEN RETURN FALSE;END IF;
 UPDATE prosedur_adim_durumu SET transaction_outcome='EXECUTED_UNCOMMITTED',
   satir_sayisi=p_satir_sayisi,bayt_sayisi=p_bayt_sayisi,
   guncellenme_zamani=clock_timestamp(),versiyon_no=versiyon_no+1 WHERE id=v_sd.id;
 RETURN TRUE;
END $$;

CREATE FUNCTION prosedur_adimini_commit_ile_tamamla(
 p_calistirma_uuid UUID,p_isleyici_referansi TEXT,p_calistirma_nesil_no BIGINT,
 p_hedef_kaynagi_uuid UUID,p_hedef_nesil_no BIGINT,p_adim_kodu TEXT,
 p_satir_sayisi BIGINT,p_bayt_sayisi BIGINT,p_commit_reference TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql SET search_path=akis,public AS $$
DECLARE v_c calistirma%ROWTYPE;v_d calistirma_durumu%ROWTYPE;v_h hedef_kaynagi%ROWTYPE;
 v_a calistirma_adimi%ROWTYPE;v_sd prosedur_adim_durumu%ROWTYPE;
BEGIN
 IF p_commit_reference IS NULL OR length(p_commit_reference) NOT BETWEEN 1 AND 200 THEN RETURN FALSE;END IF;
 SELECT * INTO v_c FROM calistirma WHERE uuid=p_calistirma_uuid;IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_d FROM calistirma_durumu WHERE calistirma_id=v_c.id FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_h FROM hedef_kaynagi WHERE id=v_d.hedef_kaynagi_id AND uuid=p_hedef_kaynagi_uuid FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_a FROM calistirma_adimi WHERE calistirma_id=v_c.id AND adim_kodu=p_adim_kodu;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_sd FROM prosedur_adim_durumu WHERE calistirma_adimi_id=v_a.id FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 IF v_d.durum<>'CALISIYOR' OR v_d.nesil_no<>p_calistirma_nesil_no
    OR v_d.isleyici_referansi<>p_isleyici_referansi OR v_d.kiralama_bitis_zamani<=clock_timestamp()
    OR v_h.durum<>'SAHIPLENILDI' OR v_h.calistirma_id<>v_c.id
    OR v_h.nesil_no<>p_hedef_nesil_no OR v_h.kiralama_bitis_zamani<=clock_timestamp()
    THEN RETURN FALSE;END IF;
 IF v_sd.durum='BASARILI' THEN RETURN v_sd.transaction_outcome='COMMIT_CONFIRMED'
   AND v_sd.durable_commit_reference=p_commit_reference AND v_sd.satir_sayisi=p_satir_sayisi
   AND v_sd.bayt_sayisi=p_bayt_sayisi;END IF;
 IF v_sd.durum<>'CALISIYOR' OR v_sd.transaction_outcome<>'EXECUTED_UNCOMMITTED'
    OR v_sd.satir_sayisi<>p_satir_sayisi OR v_sd.bayt_sayisi<>p_bayt_sayisi THEN RETURN FALSE;END IF;
 UPDATE calistirma_durumu SET son_olay_no=son_olay_no+1 WHERE id=v_d.id RETURNING * INTO v_d;
 UPDATE prosedur_adim_durumu SET durum='BASARILI',son_olay_no=v_d.son_olay_no,
   bitis_zamani=clock_timestamp(),transaction_outcome='COMMIT_CONFIRMED',
   durable_commit_reference=p_commit_reference,guncellenme_zamani=clock_timestamp(),
   versiyon_no=versiyon_no+1 WHERE id=v_sd.id;
 INSERT INTO calistirma_olayi(proje_id,calistirma_id,calistirma_adimi_id,olay_no,tur,olay_zamani,veri)
 VALUES(v_c.proje_id,v_c.id,v_a.id,v_d.son_olay_no,'PROCEDURE_TASK_COMMIT_CONFIRMED',clock_timestamp(),
 jsonb_build_object('stepCode',p_adim_kodu,'rowCountExact',p_satir_sayisi::text,
 'byteCountExact',p_bayt_sayisi::text,'commitReference',p_commit_reference));RETURN TRUE;
END $$;

CREATE FUNCTION prosedur_adimini_rollback_ile_tamamla(
 p_calistirma_uuid UUID,p_isleyici_referansi TEXT,p_calistirma_nesil_no BIGINT,
 p_hedef_kaynagi_uuid UUID,p_hedef_nesil_no BIGINT,p_adim_kodu TEXT,p_hata_kodu TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql SET search_path=akis,public AS $$
DECLARE v_c calistirma%ROWTYPE;v_d calistirma_durumu%ROWTYPE;v_h hedef_kaynagi%ROWTYPE;
 v_a calistirma_adimi%ROWTYPE;v_sd prosedur_adim_durumu%ROWTYPE;
BEGIN
 SELECT * INTO v_c FROM calistirma WHERE uuid=p_calistirma_uuid;IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_d FROM calistirma_durumu WHERE calistirma_id=v_c.id FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_h FROM hedef_kaynagi WHERE id=v_d.hedef_kaynagi_id AND uuid=p_hedef_kaynagi_uuid FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_a FROM calistirma_adimi WHERE calistirma_id=v_c.id AND adim_kodu=p_adim_kodu;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 SELECT * INTO v_sd FROM prosedur_adim_durumu WHERE calistirma_adimi_id=v_a.id FOR UPDATE;
 IF NOT FOUND THEN RETURN FALSE;END IF;
 IF v_d.durum<>'CALISIYOR' OR v_d.nesil_no<>p_calistirma_nesil_no
    OR v_d.isleyici_referansi<>p_isleyici_referansi
    OR v_d.kiralama_bitis_zamani<=clock_timestamp()
    OR v_h.durum<>'SAHIPLENILDI' OR v_h.calistirma_id<>v_c.id
    OR v_h.nesil_no<>p_hedef_nesil_no OR v_h.kiralama_bitis_zamani<=clock_timestamp()
    THEN RETURN FALSE;END IF;
 IF v_sd.transaction_outcome='ROLLBACK_CONFIRMED' THEN RETURN v_sd.hata_kodu=p_hata_kodu;END IF;
 IF v_sd.durum<>'CALISIYOR' OR v_sd.transaction_outcome<>'EXECUTED_UNCOMMITTED'
    THEN RETURN FALSE;END IF;
 UPDATE calistirma_durumu SET son_olay_no=son_olay_no+1 WHERE id=v_d.id RETURNING * INTO v_d;
 UPDATE prosedur_adim_durumu SET durum='BASARISIZ',son_olay_no=v_d.son_olay_no,
   bitis_zamani=clock_timestamp(),transaction_outcome='ROLLBACK_CONFIRMED',
   durable_commit_reference=NULL,hata_kodu=p_hata_kodu,guncellenme_zamani=clock_timestamp(),
   versiyon_no=versiyon_no+1 WHERE id=v_sd.id;
 INSERT INTO calistirma_olayi(proje_id,calistirma_id,calistirma_adimi_id,olay_no,tur,olay_zamani,veri)
 VALUES(v_c.proje_id,v_c.id,v_a.id,v_d.son_olay_no,'PROCEDURE_TASK_ROLLBACK_CONFIRMED',clock_timestamp(),
 jsonb_build_object('stepCode',p_adim_kodu,'errorCode',p_hata_kodu));RETURN TRUE;
END $$;

REVOKE ALL ON FUNCTION prosedur_adimini_yurutuldu_isaretle(UUID,TEXT,BIGINT,UUID,BIGINT,TEXT,BIGINT,BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_adimini_commit_ile_tamamla(UUID,TEXT,BIGINT,UUID,BIGINT,TEXT,BIGINT,BIGINT,TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION prosedur_adimini_rollback_ile_tamamla(UUID,TEXT,BIGINT,UUID,BIGINT,TEXT,TEXT) FROM PUBLIC;
