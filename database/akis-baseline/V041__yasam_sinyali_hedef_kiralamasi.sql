SET search_path TO akis, public;

-- The worker heartbeat only extended the run lease; the target resource lease (hedef_kaynagi) kept the deadline
-- from acquisition, so any run longer than one lease period lost its target ("KM yayın niyeti bulunamadı"
-- at publish time) even though the worker was alive. The heartbeat now extends both together.
CREATE OR REPLACE FUNCTION calistirma_yasam_sinyali(p_calistirma_uuid UUID,p_isleyici_referansi TEXT,p_nesil_no BIGINT,p_kiralama_saniyesi INTEGER)
RETURNS BOOLEAN LANGUAGE plpgsql SET search_path=akis,public AS $$
DECLARE v_sayi INTEGER; v_d calistirma_durumu%ROWTYPE;
BEGIN
 IF p_kiralama_saniyesi NOT BETWEEN 30 AND 300 THEN RETURN FALSE; END IF;
 UPDATE calistirma_durumu cd SET kiralama_bitis_zamani=clock_timestamp()+make_interval(secs=>p_kiralama_saniyesi),yasam_sinyali_zamani=clock_timestamp(),guncellenme_zamani=clock_timestamp(),versiyon_no=cd.versiyon_no+1
 FROM calistirma c WHERE c.id=cd.calistirma_id AND c.uuid=p_calistirma_uuid AND cd.durum IN('SAHIPLENILDI','CALISIYOR','YAYINLANIYOR')
 AND cd.isleyici_referansi=p_isleyici_referansi AND cd.nesil_no=p_nesil_no AND cd.kiralama_bitis_zamani>clock_timestamp()
 RETURNING cd.* INTO v_d;
 GET DIAGNOSTICS v_sayi=ROW_COUNT;
 IF v_sayi<>1 THEN RETURN FALSE; END IF;
 IF v_d.hedef_kaynagi_id IS NOT NULL THEN
  UPDATE hedef_kaynagi hk SET kiralama_bitis_zamani=v_d.kiralama_bitis_zamani,guncellenme_zamani=clock_timestamp(),versiyon_no=hk.versiyon_no+1
   WHERE hk.id=v_d.hedef_kaynagi_id AND hk.durum='SAHIPLENILDI' AND hk.calistirma_id=v_d.calistirma_id AND hk.nesil_no=v_d.hedef_nesil_no;
 END IF;
 RETURN TRUE;
END $$;
