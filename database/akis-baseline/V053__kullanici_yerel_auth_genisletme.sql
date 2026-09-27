SET search_path TO akis, public;

ALTER TABLE kullanici
    ADD COLUMN kullanici_kodu VARCHAR(100),
    ADD COLUMN ad VARCHAR(100),
    ADD COLUMN soyad VARCHAR(100),
    ADD COLUMN sicil_numarasi VARCHAR(50),
    ADD COLUMN parola VARCHAR(255),
    ADD COLUMN parola_degistirilme_zamani TIMESTAMPTZ,
    ADD COLUMN durum VARCHAR(20),
    ADD COLUMN son_giris_zamani TIMESTAMPTZ,
    ADD COLUMN son_giris_ip_adresi INET,
    ADD COLUMN hatali_giris_sayisi INTEGER NOT NULL DEFAULT 0;

WITH yerel_kimlik AS (
    SELECT kullanici_id,
           harici_kullanici_anahtari,
           son_giris_zamani,
           row_number() OVER (
               PARTITION BY lower(harici_kullanici_anahtari)
               ORDER BY kullanici_id
           ) AS ayni_kod_sirasi
      FROM harici_kimlik
     WHERE saglayici_turu = 'YEREL'
       AND yayinlayici IS NULL
), esleme AS (
    SELECT k.id,
           CASE
               WHEN y.harici_kullanici_anahtari IS NULL THEN 'USER_' || k.id
               WHEN y.ayni_kod_sirasi = 1 THEN y.harici_kullanici_anahtari
               ELSE y.harici_kullanici_anahtari || '_' || k.id
           END AS kullanici_kodu,
           y.son_giris_zamani
      FROM kullanici k
      LEFT JOIN yerel_kimlik y ON y.kullanici_id = k.id
)
UPDATE kullanici k
   SET kullanici_kodu = e.kullanici_kodu,
       ad = left(k.gorunen_ad, 100),
       durum = CASE
           WHEN k.devre_disi_birakilma_zamani IS NULL THEN 'PAROLA_BEKLIYOR'
           ELSE 'PASIF'
       END,
       son_giris_zamani = e.son_giris_zamani
  FROM esleme e
 WHERE e.id = k.id;

ALTER TABLE kullanici
    ALTER COLUMN kullanici_kodu SET NOT NULL,
    ALTER COLUMN ad SET NOT NULL,
    ALTER COLUMN durum SET NOT NULL,
    ADD CONSTRAINT ck_kullanici_kodu
        CHECK (btrim(kullanici_kodu) <> '' AND kullanici_kodu = btrim(kullanici_kodu)),
    ADD CONSTRAINT ck_kullanici_ad
        CHECK (btrim(ad) <> ''),
    ADD CONSTRAINT ck_kullanici_soyad
        CHECK (soyad IS NULL OR btrim(soyad) <> ''),
    ADD CONSTRAINT ck_kullanici_sicil_numarasi
        CHECK (sicil_numarasi IS NULL OR btrim(sicil_numarasi) <> ''),
    ADD CONSTRAINT ck_kullanici_durum
        CHECK (durum IN ('AKTIF', 'PASIF', 'PAROLA_BEKLIYOR')),
    ADD CONSTRAINT ck_kullanici_hatali_giris_sayisi
        CHECK (hatali_giris_sayisi >= 0),
    ADD CONSTRAINT ck_kullanici_parola_durumu
        CHECK (
            (durum = 'AKTIF' AND parola IS NOT NULL AND parola_degistirilme_zamani IS NOT NULL)
            OR durum IN ('PASIF', 'PAROLA_BEKLIYOR')
        ),
    ADD CONSTRAINT ck_kullanici_parola_formati
        CHECK (parola IS NULL OR parola LIKE '{argon2}%');

CREATE UNIQUE INDEX uq_kullanici_kodu
    ON kullanici (lower(kullanici_kodu));

CREATE INDEX ix_kullanici_durum
    ON kullanici (durum, id);

COMMENT ON COLUMN kullanici.kullanici_kodu IS
    'Uygulama içi benzersiz giriş kodu; harici kimlik kaldırma geçişinin kanonik anahtarıdır.';
COMMENT ON COLUMN kullanici.parola IS
    'DelegatingPasswordEncoder biçiminde Argon2id özeti; düz veya geri çözülebilir parola içermez.';

