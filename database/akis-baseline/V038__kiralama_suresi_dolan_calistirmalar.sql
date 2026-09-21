SET search_path TO akis, public;

-- Lease expiry reaper. A worker that loses its heartbeat (crash, network, long stall) leaves the run in
-- SAHIPLENILDI/CALISIYOR/YAYINLANIYOR with an expired lease and keeps the target resource claimed, which
-- blocks every later run on that target. The worker calls this before each claim:
--   * SAHIPLENILDI / CALISIYOR  -> BASARISIZ (LEASE_EXPIRED); target released (BOS). Sealed work tables stay
--                                  registered so "Devam Et" can adopt them.
--   * YAYINLANIYOR              -> SONUC_BELIRSIZ; target quarantined (ASKIDA) until reconciliation proves
--                                  whether the publication reached the target.
CREATE OR REPLACE FUNCTION kiralama_suresi_dolan_calistirmalari_kapat() RETURNS INTEGER
LANGUAGE plpgsql SET search_path=akis,public AS $$
DECLARE v_d calistirma_durumu%ROWTYPE; v_c calistirma%ROWTYPE; v_sayi INTEGER := 0; v_yeni VARCHAR(30); v_hedef VARCHAR(20);
BEGIN
 FOR v_d IN SELECT cd.* FROM calistirma_durumu cd
             WHERE cd.durum IN ('SAHIPLENILDI','CALISIYOR','YAYINLANIYOR')
               AND cd.kiralama_bitis_zamani IS NOT NULL AND cd.kiralama_bitis_zamani <= clock_timestamp()
             FOR UPDATE SKIP LOCKED
 LOOP
  SELECT * INTO v_c FROM calistirma WHERE id=v_d.calistirma_id;
  IF v_d.durum='YAYINLANIYOR' THEN v_yeni:='SONUC_BELIRSIZ'; v_hedef:='ASKIDA'; ELSE v_yeni:='BASARISIZ'; v_hedef:='BOS'; END IF;
  IF v_d.hedef_kaynagi_id IS NOT NULL THEN
   UPDATE hedef_kaynagi SET durum=v_hedef,calistirma_id=NULL,kiralama_bitis_zamani=NULL,guncellenme_zamani=clock_timestamp(),versiyon_no=versiyon_no+1
    WHERE id=v_d.hedef_kaynagi_id AND calistirma_id=v_d.calistirma_id;
  END IF;
  UPDATE calistirma_durumu SET durum=v_yeni,kiralama_bitis_zamani=NULL,bitis_zamani=clock_timestamp(),son_olay_no=son_olay_no+1,
         guncellenme_zamani=clock_timestamp(),versiyon_no=versiyon_no+1
   WHERE id=v_d.id RETURNING * INTO v_d;
  INSERT INTO calistirma_olayi(proje_id,calistirma_id,olay_no,tur,olay_zamani,veri)
  VALUES(v_c.proje_id,v_c.id,v_d.son_olay_no,'RUN_LEASE_EXPIRED',clock_timestamp(),
         jsonb_build_object('generation',v_d.nesil_no,'workerReference',v_d.isleyici_referansi,'errorCode','LEASE_EXPIRED','status',v_yeni));
  v_sayi:=v_sayi+1;
 END LOOP;
 RETURN v_sayi;
END $$;
REVOKE ALL ON FUNCTION kiralama_suresi_dolan_calistirmalari_kapat() FROM PUBLIC;
