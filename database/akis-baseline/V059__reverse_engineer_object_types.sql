SET search_path TO akis, public;

-- Reverse engineering supports table-like objects without changing existing rows.
ALTER TABLE veri_nesnesi DROP CONSTRAINT ck_veri_nesnesi_tur;

ALTER TABLE veri_nesnesi
    ADD CONSTRAINT ck_veri_nesnesi_tur
    CHECK (tur IN ('TABLO', 'GORUNUM', 'SORGU', 'MATERIALIZED_VIEW', 'SYNONYM'));
