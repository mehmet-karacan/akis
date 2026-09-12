SET search_path TO akis, public;

ALTER TABLE baglanti
    DROP CONSTRAINT uq_baglanti_proje_kod;

CREATE UNIQUE INDEX uq_baglanti_proje_kod_aktif
    ON baglanti(proje_id, kod)
    WHERE arsivlenme_zamani IS NULL;

ALTER TABLE fiziksel_sema
    DROP CONSTRAINT uq_fiziksel_sema_kod;

CREATE UNIQUE INDEX uq_fiziksel_sema_kod_aktif
    ON fiziksel_sema(baglanti_id, kod)
    WHERE arsivlenme_zamani IS NULL;

ALTER TABLE fiziksel_sema
    DROP CONSTRAINT uq_fiziksel_sema_ad;

CREATE UNIQUE INDEX uq_fiziksel_sema_ad_aktif
    ON fiziksel_sema(baglanti_id, sema_adi)
    WHERE arsivlenme_zamani IS NULL;
