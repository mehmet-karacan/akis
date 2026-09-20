-- Tests never participate in execution value retention or the latest runtime value.
CREATE TABLE akis.degisken_test_gecmisi (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    proje_uuid uuid NOT NULL REFERENCES akis.proje(uuid),
    tanim_uuid uuid NOT NULL REFERENCES akis.tanim(uuid),
    ortam_uuid uuid NOT NULL REFERENCES akis.ortam(uuid),
    mantiksal_sema_uuid uuid NOT NULL REFERENCES akis.mantiksal_sema(uuid),
    baglanti_surumu_uuid uuid NOT NULL REFERENCES akis.baglanti_surumu(uuid),
    sorgu_ozeti varchar(64) NOT NULL,
    veri_turu varchar(20) NOT NULL,
    deger text,
    basarili boolean NOT NULL,
    hata_kodu varchar(80),
    sure_ms bigint NOT NULL CHECK (sure_ms >= 0),
    olusturulma_zamani timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((basarili AND deger IS NOT NULL AND hata_kodu IS NULL) OR
           (NOT basarili AND deger IS NULL AND hata_kodu IS NOT NULL))
);
CREATE INDEX ix_degisken_test_gecmisi ON akis.degisken_test_gecmisi(proje_uuid, tanim_uuid, id DESC);
