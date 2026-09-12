SET search_path TO akis, public;

ALTER TABLE fiziksel_sema
    DROP CONSTRAINT uq_fiziksel_sema_kod;

ALTER TABLE fiziksel_sema
    ADD CONSTRAINT uq_fiziksel_sema_kod UNIQUE (baglanti_id, kod);
