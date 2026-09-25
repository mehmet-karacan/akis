SET search_path TO akis, public;

-- PostgreSQL physical schema references are unquoted identifiers and therefore lower-case.
UPDATE fiziksel_sema fs
   SET sema_adi = lower(fs.sema_adi),
       calisma_sema_adi = lower(fs.calisma_sema_adi),
       guncellenme_zamani = clock_timestamp()
  FROM baglanti b
 WHERE b.id = fs.baglanti_id
   AND b.saglayici_turu = 'POSTGRESQL'
   AND fs.sema_adi IS NOT NULL
   AND fs.calisma_sema_adi IS NOT NULL;
