CREATE TABLE akis.degisken_deger_gecmisi (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proje_uuid uuid NOT NULL REFERENCES akis.proje(uuid),
    tanim_uuid uuid NOT NULL REFERENCES akis.tanim(uuid),
    calistirma_uuid uuid NOT NULL,
    ortam_uuid uuid NOT NULL REFERENCES akis.ortam(uuid),
    mantiksal_sema_uuid uuid NOT NULL REFERENCES akis.mantiksal_sema(uuid),
    baglanti_surumu_uuid uuid NOT NULL REFERENCES akis.baglanti_surumu(uuid),
    plan_ozeti varchar(64) NOT NULL,
    veri_turu varchar(20) NOT NULL,
    deger text NOT NULL,
    gecmis_modu varchar(10) NOT NULL CHECK (gecmis_modu IN ('LATEST', 'ALL')),
    uuid uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    olusturulma_zamani timestamptz NOT NULL DEFAULT clock_timestamp(),
    olusturan_kullanici_id bigint REFERENCES akis.kullanici(id),
    guncellenme_zamani timestamptz,
    guncelleyen_kullanici_id bigint REFERENCES akis.kullanici(id),
    versiyon_no bigint NOT NULL DEFAULT 1,
    UNIQUE (calistirma_uuid, tanim_uuid, plan_ozeti)
);
CREATE INDEX ix_degisken_gecmisi ON akis.degisken_deger_gecmisi(proje_uuid, tanim_uuid, id DESC);
