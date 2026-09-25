-- Dev-only cleanup after the reconciliation-key bug (fixed in code):
-- 1) release the quarantined target resource so new runs can fence it again,
-- 2) close the run that was left claimed when the fence could not be acquired.
UPDATE akis.hedef_kaynagi
   SET durum = 'BOS', calistirma_id = NULL, kiralama_bitis_zamani = NULL,
       guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
 WHERE id = 1 AND durum = 'ASKIDA';

UPDATE akis.calistirma_durumu
   SET durum = 'BASARISIZ', kiralama_bitis_zamani = NULL, bitis_zamani = clock_timestamp(),
       guncellenme_zamani = clock_timestamp(), versiyon_no = versiyon_no + 1
 WHERE calistirma_id = 11 AND durum = 'SAHIPLENILDI';
