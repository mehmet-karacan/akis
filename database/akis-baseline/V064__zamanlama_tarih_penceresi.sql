SET search_path TO akis, public;

-- Optional absolute run window. Existing schedules remain unbounded.
ALTER TABLE zamanlama
    ADD COLUMN baslangic_zamani TIMESTAMPTZ,
    ADD COLUMN bitis_zamani TIMESTAMPTZ,
    ADD CONSTRAINT ck_zamanlama_tarih_penceresi
        CHECK (baslangic_zamani IS NULL OR bitis_zamani IS NULL OR bitis_zamani > baslangic_zamani);

-- A portable schedule recipe carries the same window, but remains inactive
-- until a real target publication is validated and explicitly activated.
ALTER TABLE ithal_zamanlama_tarifi
    ADD COLUMN baslangic_zamani TIMESTAMPTZ,
    ADD COLUMN bitis_zamani TIMESTAMPTZ,
    ADD CONSTRAINT ck_ithal_zamanlama_tarih_penceresi
        CHECK (baslangic_zamani IS NULL OR bitis_zamani IS NULL OR bitis_zamani > baslangic_zamani);
