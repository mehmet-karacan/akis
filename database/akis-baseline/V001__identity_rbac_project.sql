CREATE SCHEMA akis;
SET search_path TO akis, public;

CREATE TABLE kullanici (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gorunen_ad VARCHAR(200) NOT NULL,
    eposta VARCHAR(320),
    devre_disi_birakilma_zamani TIMESTAMPTZ,
    olusturulma_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    guncellenme_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    surum BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_kullanici_gorunen_ad
        CHECK (btrim(gorunen_ad) <> ''),
    CONSTRAINT ck_kullanici_eposta
        CHECK (eposta IS NULL OR btrim(eposta) <> ''),
    CONSTRAINT ck_kullanici_guncellenme_zamani
        CHECK (guncellenme_zamani >= olusturulma_zamani),
    CONSTRAINT ck_kullanici_surum
        CHECK (surum >= 0)
);

CREATE UNIQUE INDEX uq_kullanici_eposta
    ON kullanici (lower(eposta))
    WHERE eposta IS NOT NULL;

CREATE TABLE harici_kimlik (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kullanici_id UUID NOT NULL REFERENCES kullanici(id) ON DELETE CASCADE,
    saglayici_turu VARCHAR(20) NOT NULL,
    yayinlayici VARCHAR(500),
    harici_kullanici_anahtari VARCHAR(500) NOT NULL,
    son_giris_zamani TIMESTAMPTZ,
    olusturulma_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_harici_kimlik_saglayici_turu
        CHECK (saglayici_turu IN ('OIDC', 'YEREL')),
    CONSTRAINT ck_harici_kimlik_kullanici_anahtari
        CHECK (btrim(harici_kullanici_anahtari) <> ''),
    CONSTRAINT ck_harici_kimlik_yayinlayici
        CHECK (
            (saglayici_turu = 'OIDC' AND yayinlayici IS NOT NULL AND btrim(yayinlayici) <> '')
            OR (saglayici_turu = 'YEREL' AND yayinlayici IS NULL)
        ),
    CONSTRAINT uq_harici_kimlik
        UNIQUE NULLS NOT DISTINCT (saglayici_turu, yayinlayici, harici_kullanici_anahtari)
);

CREATE TABLE rol (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kapsam VARCHAR(20) NOT NULL,
    kod VARCHAR(100) NOT NULL,
    ad VARCHAR(200) NOT NULL,
    aciklama VARCHAR(1000),
    sistem_tanimi_mi BOOLEAN NOT NULL DEFAULT FALSE,
    etkin_mi BOOLEAN NOT NULL DEFAULT TRUE,
    olusturulma_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rol_kapsam
        CHECK (kapsam IN ('SISTEM', 'PROJE')),
    CONSTRAINT ck_rol_kod
        CHECK (kod ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT ck_rol_ad
        CHECK (btrim(ad) <> ''),
    CONSTRAINT uq_rol_kapsam_kod
        UNIQUE (kapsam, kod),
    CONSTRAINT uq_rol_id_kapsam
        UNIQUE (id, kapsam)
);

CREATE TABLE yetki (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kapsam VARCHAR(20) NOT NULL,
    kod VARCHAR(100) NOT NULL,
    kaynak VARCHAR(100) NOT NULL,
    eylem VARCHAR(50) NOT NULL,
    aciklama VARCHAR(1000),
    CONSTRAINT ck_yetki_kapsam
        CHECK (kapsam IN ('SISTEM', 'PROJE')),
    CONSTRAINT ck_yetki_kod
        CHECK (kod ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT ck_yetki_kaynak
        CHECK (kaynak ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT ck_yetki_eylem
        CHECK (eylem ~ '^[A-Z][A-Z0-9_]{0,49}$'),
    CONSTRAINT uq_yetki_kod
        UNIQUE (kod)
);

CREATE TABLE rol_yetki (
    rol_id UUID NOT NULL REFERENCES rol(id) ON DELETE CASCADE,
    yetki_id UUID NOT NULL REFERENCES yetki(id) ON DELETE CASCADE,
    PRIMARY KEY (rol_id, yetki_id)
);

CREATE TABLE proje (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kod VARCHAR(100) NOT NULL,
    ad VARCHAR(200) NOT NULL,
    aciklama VARCHAR(2000),
    arsivlenme_zamani TIMESTAMPTZ,
    olusturulma_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    guncellenme_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    surum BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_proje_kod
        CHECK (kod ~ '^[A-Z][A-Z0-9_]{0,99}$'),
    CONSTRAINT ck_proje_ad
        CHECK (btrim(ad) <> ''),
    CONSTRAINT ck_proje_guncellenme_zamani
        CHECK (guncellenme_zamani >= olusturulma_zamani),
    CONSTRAINT ck_proje_surum
        CHECK (surum >= 0),
    CONSTRAINT uq_proje_kod
        UNIQUE (kod)
);

CREATE TABLE proje_uyeligi (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proje_id UUID NOT NULL REFERENCES proje(id) ON DELETE CASCADE,
    kullanici_id UUID NOT NULL REFERENCES kullanici(id) ON DELETE CASCADE,
    durum VARCHAR(20) NOT NULL DEFAULT 'AKTIF',
    katilma_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    askiya_alinma_zamani TIMESTAMPTZ,
    sona_erme_zamani TIMESTAMPTZ,
    olusturulma_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    surum BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_proje_uyeligi_durum
        CHECK (durum IN ('AKTIF', 'ASKIDA', 'SONLANDI')),
    CONSTRAINT ck_proje_uyeligi_tarihler
        CHECK (
            (durum = 'AKTIF' AND askiya_alinma_zamani IS NULL AND sona_erme_zamani IS NULL)
            OR (durum = 'ASKIDA' AND askiya_alinma_zamani IS NOT NULL AND sona_erme_zamani IS NULL)
            OR (durum = 'SONLANDI' AND sona_erme_zamani IS NOT NULL)
        ),
    CONSTRAINT ck_proje_uyeligi_surum
        CHECK (surum >= 0),
    CONSTRAINT uq_proje_uyeligi
        UNIQUE (proje_id, kullanici_id),
    CONSTRAINT uq_proje_uyeligi_id_proje_kullanici
        UNIQUE (id, proje_id, kullanici_id)
);

CREATE TABLE kullanici_rol (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kullanici_id UUID NOT NULL REFERENCES kullanici(id) ON DELETE CASCADE,
    rol_id UUID NOT NULL,
    rol_kapsami VARCHAR(20) NOT NULL,
    proje_id UUID,
    atanma_zamani TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atayan_kullanici_id UUID REFERENCES kullanici(id),
    iptal_zamani TIMESTAMPTZ,
    iptal_eden_kullanici_id UUID REFERENCES kullanici(id),
    CONSTRAINT fk_kullanici_rol_tanimi
        FOREIGN KEY (rol_id, rol_kapsami) REFERENCES rol(id, kapsam),
    CONSTRAINT fk_kullanici_rol_proje_uyeligi
        FOREIGN KEY (proje_id, kullanici_id)
        REFERENCES proje_uyeligi(proje_id, kullanici_id),
    CONSTRAINT ck_kullanici_rol_kapsami
        CHECK (
            (rol_kapsami = 'SISTEM' AND proje_id IS NULL)
            OR (rol_kapsami = 'PROJE' AND proje_id IS NOT NULL)
        ),
    CONSTRAINT ck_kullanici_rol_iptal
        CHECK (
            (iptal_zamani IS NULL AND iptal_eden_kullanici_id IS NULL)
            OR (iptal_zamani IS NOT NULL AND iptal_zamani >= atanma_zamani)
        )
);

CREATE UNIQUE INDEX uq_kullanici_rol_aktif_sistem
    ON kullanici_rol (kullanici_id, rol_id)
    WHERE proje_id IS NULL AND iptal_zamani IS NULL;

CREATE UNIQUE INDEX uq_kullanici_rol_aktif_proje
    ON kullanici_rol (kullanici_id, rol_id, proje_id)
    WHERE proje_id IS NOT NULL AND iptal_zamani IS NULL;

CREATE INDEX ix_harici_kimlik_kullanici ON harici_kimlik(kullanici_id);
CREATE INDEX ix_proje_uyeligi_kullanici ON proje_uyeligi(kullanici_id, durum);
CREATE INDEX ix_kullanici_rol_proje_kullanici ON kullanici_rol(proje_id, kullanici_id)
    WHERE proje_id IS NOT NULL AND iptal_zamani IS NULL;

INSERT INTO rol(kapsam, kod, ad, aciklama, sistem_tanimi_mi) VALUES
    ('SISTEM', 'SISTEM_YONETICISI', 'Sistem Yöneticisi',
        'Genel kullanıcıları, kimlikleri ve platform politikasını yönetir.', TRUE),
    ('PROJE', 'PROJE_YONETICISI', 'Proje Yöneticisi',
        'Proje ayarlarını, üyeleri, bağlantıları ve şemaları yönetir.', TRUE),
    ('PROJE', 'GELISTIRICI', 'Geliştirici',
        'Entegrasyon tanımlarını oluşturur ve doğrular.', TRUE),
    ('PROJE', 'OPERASYON', 'Operasyon',
        'Tanımları değiştirmeden çalıştırmaları ve zamanlamaları yönetir.', TRUE),
    ('PROJE', 'YAYIN_ONAYLAYICI', 'Yayın Onaylayıcı',
        'Çalıştırılabilir üretim sürümlerini inceler ve onaylar.', TRUE),
    ('PROJE', 'GORUNTULEYICI', 'Görüntüleyici',
        'Proje tanımlarını, kataloğu ve çalışma geçmişini görüntüler.', TRUE);

INSERT INTO yetki(kapsam, kod, kaynak, eylem, aciklama) VALUES
    ('SISTEM', 'KULLANICI_YONET', 'KULLANICI', 'YONET', 'Kullanıcıları ve kimlikleri oluşturur veya devre dışı bırakır.'),
    ('SISTEM', 'PROJE_OLUSTUR', 'PROJE', 'OLUSTUR', 'Proje oluşturur.'),
    ('SISTEM', 'SISTEM_POLITIKASI_YONET', 'SISTEM_POLITIKASI', 'YONET', 'Platform politikasını yönetir.'),
    ('PROJE', 'PROJE_GORUNTULE', 'PROJE', 'GORUNTULE', 'Proje ayrıntılarını görüntüler.'),
    ('PROJE', 'PROJE_YONET', 'PROJE', 'YONET', 'Projeyi günceller ve arşivler.'),
    ('PROJE', 'UYE_YONET', 'UYE', 'YONET', 'Proje üyeliklerini ve rollerini yönetir.'),
    ('PROJE', 'BAGLANTI_GORUNTULE', 'BAGLANTI', 'GORUNTULE', 'Bağlantıları ve şemaları görüntüler.'),
    ('PROJE', 'BAGLANTI_YONET', 'BAGLANTI', 'YONET', 'Bağlantı sürümlerini ve şema eşleştirmelerini yönetir.'),
    ('PROJE', 'BAGLANTI_TEST_ET', 'BAGLANTI', 'TEST_ET', 'Bağlantı sürümünü test eder.'),
    ('PROJE', 'KATALOG_GORUNTULE', 'KATALOG', 'GORUNTULE', 'Keşfedilmiş katalog bilgisini görüntüler.'),
    ('PROJE', 'KATALOG_KESFET', 'KATALOG', 'KESFET', 'Metadata keşfini çalıştırır.'),
    ('PROJE', 'TANIM_GORUNTULE', 'TANIM', 'GORUNTULE', 'Proje tanımlarını görüntüler.'),
    ('PROJE', 'TANIM_DUZENLE', 'TANIM', 'DUZENLE', 'Proje tanımlarını oluşturur ve düzenler.'),
    ('PROJE', 'TANIM_DOGRULA', 'TANIM', 'DOGRULA', 'Proje tanımlarını doğrular.'),
    ('PROJE', 'CALISTIRILABILIR_SURUM_GORUNTULE', 'CALISTIRILABILIR_SURUM', 'GORUNTULE', 'Çalıştırılabilir sürümleri görüntüler.'),
    ('PROJE', 'CALISTIRILABILIR_SURUM_OLUSTUR', 'CALISTIRILABILIR_SURUM', 'OLUSTUR', 'Değişmez çalıştırılabilir sürüm oluşturur.'),
    ('PROJE', 'CALISTIRILABILIR_SURUM_ONAYLA', 'CALISTIRILABILIR_SURUM', 'ONAYLA', 'Çalıştırılabilir üretim sürümünü onaylar.'),
    ('PROJE', 'CALISTIRMA_GORUNTULE', 'CALISTIRMA', 'GORUNTULE', 'Çalıştırmaları ve adım ayrıntılarını görüntüler.'),
    ('PROJE', 'CALISTIRMA_BASLAT', 'CALISTIRMA', 'BASLAT', 'Yetkili bir çalıştırmayı başlatır.'),
    ('PROJE', 'CALISTIRMA_IPTAL_ET', 'CALISTIRMA', 'IPTAL_ET', 'Çalıştırmanın iptalini ister.'),
    ('PROJE', 'CALISTIRMA_YENIDEN_DENE', 'CALISTIRMA', 'YENIDEN_DENE', 'Uygun bir çalıştırmayı yeniden dener.'),
    ('PROJE', 'ZAMANLAMA_YONET', 'ZAMANLAMA', 'YONET', 'Zamanlamaları oluşturur ve günceller.');

WITH atamalar(rol_kodu, yetki_kodu) AS (
    VALUES
        ('SISTEM_YONETICISI', 'KULLANICI_YONET'),
        ('SISTEM_YONETICISI', 'PROJE_OLUSTUR'),
        ('SISTEM_YONETICISI', 'SISTEM_POLITIKASI_YONET'),

        ('PROJE_YONETICISI', 'PROJE_GORUNTULE'),
        ('PROJE_YONETICISI', 'PROJE_YONET'),
        ('PROJE_YONETICISI', 'UYE_YONET'),
        ('PROJE_YONETICISI', 'BAGLANTI_GORUNTULE'),
        ('PROJE_YONETICISI', 'BAGLANTI_YONET'),
        ('PROJE_YONETICISI', 'BAGLANTI_TEST_ET'),
        ('PROJE_YONETICISI', 'KATALOG_GORUNTULE'),
        ('PROJE_YONETICISI', 'KATALOG_KESFET'),
        ('PROJE_YONETICISI', 'TANIM_GORUNTULE'),

        ('GELISTIRICI', 'PROJE_GORUNTULE'),
        ('GELISTIRICI', 'BAGLANTI_GORUNTULE'),
        ('GELISTIRICI', 'BAGLANTI_TEST_ET'),
        ('GELISTIRICI', 'KATALOG_GORUNTULE'),
        ('GELISTIRICI', 'KATALOG_KESFET'),
        ('GELISTIRICI', 'TANIM_GORUNTULE'),
        ('GELISTIRICI', 'TANIM_DUZENLE'),
        ('GELISTIRICI', 'TANIM_DOGRULA'),
        ('GELISTIRICI', 'CALISTIRILABILIR_SURUM_GORUNTULE'),
        ('GELISTIRICI', 'CALISTIRILABILIR_SURUM_OLUSTUR'),
        ('GELISTIRICI', 'CALISTIRMA_GORUNTULE'),

        ('OPERASYON', 'PROJE_GORUNTULE'),
        ('OPERASYON', 'BAGLANTI_GORUNTULE'),
        ('OPERASYON', 'KATALOG_GORUNTULE'),
        ('OPERASYON', 'TANIM_GORUNTULE'),
        ('OPERASYON', 'CALISTIRILABILIR_SURUM_GORUNTULE'),
        ('OPERASYON', 'CALISTIRMA_GORUNTULE'),
        ('OPERASYON', 'CALISTIRMA_BASLAT'),
        ('OPERASYON', 'CALISTIRMA_IPTAL_ET'),
        ('OPERASYON', 'CALISTIRMA_YENIDEN_DENE'),
        ('OPERASYON', 'ZAMANLAMA_YONET'),

        ('YAYIN_ONAYLAYICI', 'PROJE_GORUNTULE'),
        ('YAYIN_ONAYLAYICI', 'KATALOG_GORUNTULE'),
        ('YAYIN_ONAYLAYICI', 'TANIM_GORUNTULE'),
        ('YAYIN_ONAYLAYICI', 'CALISTIRILABILIR_SURUM_GORUNTULE'),
        ('YAYIN_ONAYLAYICI', 'CALISTIRILABILIR_SURUM_ONAYLA'),
        ('YAYIN_ONAYLAYICI', 'CALISTIRMA_GORUNTULE'),

        ('GORUNTULEYICI', 'PROJE_GORUNTULE'),
        ('GORUNTULEYICI', 'BAGLANTI_GORUNTULE'),
        ('GORUNTULEYICI', 'KATALOG_GORUNTULE'),
        ('GORUNTULEYICI', 'TANIM_GORUNTULE'),
        ('GORUNTULEYICI', 'CALISTIRILABILIR_SURUM_GORUNTULE'),
        ('GORUNTULEYICI', 'CALISTIRMA_GORUNTULE')
)
INSERT INTO rol_yetki(rol_id, yetki_id)
SELECT r.id, p.id
  FROM atamalar a
  JOIN rol r ON r.kod = a.rol_kodu
  JOIN yetki p ON p.kod = a.yetki_kodu;
