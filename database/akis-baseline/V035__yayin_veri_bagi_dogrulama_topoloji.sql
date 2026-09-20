-- V033 ile sema_eslemesi global oldu (proje_id yok) ve baglanti_surumu kaldırıldı; yayın veri bağı doğrulaması
-- hâlâ bu kolonlara bakıyordu ve her yayın hazırlığı "column proje_id does not exist" ile düşüyordu.
-- Zincir kontrolü korunur: eşleme ve görüntü aynı fiziksel şemaya, görüntü aynı veri nesnesine ait olmalıdır.
CREATE OR REPLACE FUNCTION akis.yayin_veri_bagini_dogrula() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE esleme_fiziksel BIGINT; goruntu_nesne BIGINT; bag_nesne BIGINT; goruntu_fiziksel BIGINT;
BEGIN
 SELECT fiziksel_sema_id INTO STRICT esleme_fiziksel FROM akis.sema_eslemesi WHERE id=NEW.sema_eslemesi_id;
 SELECT veri_nesnesi_id,fiziksel_sema_id INTO STRICT goruntu_nesne,goruntu_fiziksel FROM akis.sema_goruntusu WHERE id=NEW.sema_goruntusu_id;
 SELECT veri_nesnesi_id INTO STRICT bag_nesne FROM akis.tanim_veri_nesnesi WHERE id=NEW.tanim_veri_nesnesi_id;
 IF esleme_fiziksel<>NEW.fiziksel_sema_id OR goruntu_nesne<>bag_nesne OR goruntu_fiziksel<>NEW.fiziksel_sema_id THEN
  RAISE EXCEPTION 'Yayın veri bağı aynı eşleme ve görüntü zincirine ait olmalıdır.' USING ERRCODE='23514';
 END IF; RETURN NEW;
END $$;
