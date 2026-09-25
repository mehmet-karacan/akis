SET search_path TO akis, public;

-- A physical schema code identifies a schema within its connection.
-- The same Oracle/PostgreSQL schema name must be usable on different connections.
ALTER TABLE fiziksel_sema
    DROP CONSTRAINT IF EXISTS uq_fiziksel_sema_kod;

ALTER TABLE fiziksel_sema
    DROP CONSTRAINT IF EXISTS uq_fiziksel_sema_baglanti_kod;

ALTER TABLE fiziksel_sema
    ADD CONSTRAINT uq_fiziksel_sema_baglanti_kod UNIQUE (baglanti_id, kod);
