-- Şema metadata sözlüğü (akis.sema_tanimlari ve ailesi) için sistem-seviyesi yetkiler.
-- Bu tablolar proje kapsamlı değil (bkz. V030), bu yüzden yetkiler de SISTEM kapsamında.
INSERT INTO akis.yetki(kapsam, kod, kaynak, eylem, aciklama) VALUES
    ('SISTEM', 'SEMA_METADATA_GORUNTULE', 'SEMA_METADATA', 'GORUNTULE', 'Şema metadata sözlüğünü (tablo/kolon/kısıt/ilişki/indeks/sequence) görüntüler.'),
    ('SISTEM', 'SEMA_METADATA_YONET', 'SEMA_METADATA', 'YONET', 'Şema metadata sözlüğünü düzenler.');

WITH atamalar(rol_kodu, yetki_kodu) AS (
    VALUES
        ('SISTEM_YONETICISI', 'SEMA_METADATA_GORUNTULE'),
        ('SISTEM_YONETICISI', 'SEMA_METADATA_YONET')
)
INSERT INTO akis.rol_yetki(rol_id, yetki_id, kapsam)
SELECT r.id, y.id, r.kapsam
  FROM atamalar a
  JOIN akis.rol r ON r.kod = a.rol_kodu
  JOIN akis.yetki y ON y.kod = a.yetki_kodu AND y.kapsam = r.kapsam;
