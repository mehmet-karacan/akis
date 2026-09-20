SET search_path TO akis, public;

ALTER TABLE baglanti
    ADD COLUMN calisma_fiziksel_sema_id BIGINT,
    ADD COLUMN calisma_semasi_surumu BIGINT NOT NULL DEFAULT 1;

ALTER TABLE baglanti
    ADD CONSTRAINT fk_baglanti_calisma_semasi
    FOREIGN KEY (proje_id, calisma_fiziksel_sema_id)
    REFERENCES fiziksel_sema(proje_id, id);

UPDATE baglanti b
   SET calisma_fiziksel_sema_id = tek.fiziksel_sema_id
  FROM (
      SELECT proje_id, baglanti_id, min(id) fiziksel_sema_id
        FROM fiziksel_sema
       WHERE arsivlenme_zamani IS NULL
       GROUP BY proje_id, baglanti_id
      HAVING count(*) = 1
  ) tek
 WHERE tek.proje_id = b.proje_id
   AND tek.baglanti_id = b.id
   AND b.calisma_fiziksel_sema_id IS NULL;

CREATE INDEX ix_baglanti_calisma_semasi
    ON baglanti(proje_id, calisma_fiziksel_sema_id)
    WHERE calisma_fiziksel_sema_id IS NOT NULL;
