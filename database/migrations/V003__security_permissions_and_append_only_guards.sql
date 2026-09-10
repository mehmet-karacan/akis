SET search_path TO entegrasyon, public;

INSERT INTO yetki(kod, kapsam_kodu, ad, aciklama) VALUES
    ('PROJECT_CREATE', 'SISTEM', 'Proje oluşturma', 'Yeni proje oluşturur'),
    ('GLOBAL_DEFINITION_READ', 'SISTEM', 'Global tanım okuma', 'Global tanımları görüntüler'),
    ('GLOBAL_DEFINITION_WRITE', 'SISTEM', 'Global tanım yazma', 'Global tanımları değiştirir ve sürümler'),
    ('PROJECT_READ', 'PROJE', 'Proje okuma', 'Proje metadata içeriğini görüntüler'),
    ('PROJECT_WRITE', 'PROJE', 'Proje yazma', 'Proje tanımlarını ve klasörlerini değiştirir'),
    ('TOPOLOGY_READ', 'PROJE', 'Topoloji okuma', 'Bağlantı ve şema topolojisini görüntüler'),
    ('TOPOLOGY_WRITE', 'PROJE', 'Topoloji yazma', 'Bağlantı ve şema topolojisini değiştirir'),
    ('SECRET_READ', 'KAYNAK', 'Secret referansı okuma', 'Secret değerini değil yalnız referansını görüntüler'),
    ('SECRET_WRITE', 'KAYNAK', 'Secret referansı yazma', 'Secret referanslarını yönetir'),
    ('CATALOG_READ', 'PROJE', 'Katalog okuma', 'Model ve veri nesnesi kataloğunu görüntüler'),
    ('CATALOG_WRITE', 'PROJE', 'Katalog yazma', 'Model ve veri nesnesi kataloğunu değiştirir'),
    ('DISCOVERY_READ', 'KAYNAK', 'Discovery okuma', 'Şema keşif sonuçlarını görüntüler'),
    ('DISCOVERY_WRITE', 'KAYNAK', 'Discovery yazma', 'Şema keşfi ve snapshot oluşturur'),
    ('SCENARIO_READ', 'URETIM', 'Scenario okuma', 'Derlenmiş çalıştırma planlarını görüntüler'),
    ('SCENARIO_COMPILE', 'URETIM', 'Scenario derleme', 'Doğrulanmış tanım sürümünü scenario olarak derler'),
    ('PUBLICATION_READ', 'URETIM', 'Yayın okuma', 'Context sabitlenmiş yayınları görüntüler'),
    ('PUBLICATION_CREATE', 'URETIM', 'Yayın oluşturma', 'Scenario için context sabitlenmiş yayın oluşturur'),
    ('PUBLICATION_APPROVE', 'URETIM', 'Yayın onaylama', 'Üretim yayınına onay veya ret kararı verir'),
    ('IDENTITY_USER_PROVISION', 'SISTEM', 'Kullanıcı tanımlama', 'OIDC kullanıcı kataloğunu yönetir'),
    ('PROJECT_MEMBERSHIP_MANAGE', 'PROJE', 'Proje üyeliği yönetme', 'Proje kullanıcı ve rol üyeliklerini yönetir');

INSERT INTO sistem_rolu(kod, ad, aciklama)
VALUES ('SISTEM_YONETICISI', 'Sistem Yöneticisi', 'Sistem kapsamlı yönetim rolü');

INSERT INTO sistem_rolu_yetkisi(sistem_rolu_id, yetki_id)
SELECT sr.id, y.id
  FROM sistem_rolu sr
  JOIN yetki y ON y.kapsam_kodu = 'SISTEM'
 WHERE sr.kod = 'SISTEM_YONETICISI';

CREATE FUNCTION varsayilan_proje_rollerini_olustur() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO entegrasyon.proje_rolu(proje_id, kod, ad, aciklama) VALUES
        (NEW.id, 'PROJE_YONETICISI', 'Proje Yöneticisi', 'Projedeki tüm işlemleri yönetir'),
        (NEW.id, 'GELISTIRICI', 'Geliştirici', 'ETL tasarımı ve discovery işlemlerini yürütür'),
        (NEW.id, 'IZLEYICI', 'İzleyici', 'Proje içeriğini salt okunur görüntüler');

    INSERT INTO entegrasyon.proje_rolu_yetkisi(proje_id, proje_rolu_id, yetki_id)
    SELECT NEW.id, pr.id, y.id
      FROM entegrasyon.proje_rolu pr
      JOIN entegrasyon.yetki y ON y.kapsam_kodu IN ('PROJE', 'KAYNAK', 'URETIM')
     WHERE pr.proje_id = NEW.id
       AND (
           pr.kod = 'PROJE_YONETICISI'
           OR (pr.kod = 'GELISTIRICI' AND y.kod NOT IN (
               'SECRET_READ', 'SECRET_WRITE', 'PUBLICATION_APPROVE',
               'PROJECT_MEMBERSHIP_MANAGE'))
           OR (pr.kod = 'IZLEYICI' AND y.kod IN (
               'PROJECT_READ', 'TOPOLOGY_READ', 'CATALOG_READ', 'DISCOVERY_READ',
               'SCENARIO_READ', 'PUBLICATION_READ'))
       );
    RETURN NEW;
END;
$$;

ALTER FUNCTION tanim_veri_nesnesi_proje_dogrula()
    SET search_path = entegrasyon, public;

CREATE TRIGGER tr_proje_varsayilan_roller
    AFTER INSERT ON proje
    FOR EACH ROW EXECUTE FUNCTION varsayilan_proje_rollerini_olustur();

INSERT INTO proje_rolu(proje_id, kod, ad, aciklama)
SELECT p.id, r.kod, r.ad, r.aciklama
  FROM proje p
 CROSS JOIN (VALUES
    ('PROJE_YONETICISI', 'Proje Yöneticisi', 'Projedeki tüm işlemleri yönetir'),
    ('GELISTIRICI', 'Geliştirici', 'ETL tasarımı ve discovery işlemlerini yürütür'),
    ('IZLEYICI', 'İzleyici', 'Proje içeriğini salt okunur görüntüler')
 ) AS r(kod, ad, aciklama)
ON CONFLICT (proje_id, kod) DO NOTHING;

INSERT INTO proje_rolu_yetkisi(proje_id, proje_rolu_id, yetki_id)
SELECT pr.proje_id, pr.id, y.id
  FROM proje_rolu pr
  JOIN yetki y ON y.kapsam_kodu IN ('PROJE', 'KAYNAK', 'URETIM')
 WHERE (
       pr.kod = 'PROJE_YONETICISI'
       OR (pr.kod = 'GELISTIRICI' AND y.kod NOT IN (
           'SECRET_READ', 'SECRET_WRITE', 'PUBLICATION_APPROVE',
           'PROJECT_MEMBERSHIP_MANAGE'))
       OR (pr.kod = 'IZLEYICI' AND y.kod IN (
           'PROJECT_READ', 'TOPOLOGY_READ', 'CATALOG_READ', 'DISCOVERY_READ',
           'SCENARIO_READ', 'PUBLICATION_READ'))
   )
ON CONFLICT (proje_rolu_id, yetki_id) DO NOTHING;

CREATE TRIGGER tr_tanim_surumu_immutable_delete BEFORE DELETE ON tanim_surumu
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_dogrulama_immutable_delete BEFORE DELETE ON dogrulama
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_senaryo_immutable_delete BEFORE DELETE ON senaryo
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_calistirma_immutable_delete BEFORE DELETE ON calistirma
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_calistirma_adimi_immutable_delete BEFORE DELETE ON calistirma_adimi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_calistirma_gorevi_immutable_delete BEFORE DELETE ON calistirma_gorevi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_calistirma_olayi_immutable_delete BEFORE DELETE ON calistirma_olayi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_degisken_deger_immutable_delete BEFORE DELETE ON degisken_deger_olayi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_sekans_deger_immutable_delete BEFORE DELETE ON sekans_deger_olayi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_kontrol_noktasi_immutable_delete BEFORE DELETE ON kontrol_noktasi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_denetim_olayi_immutable_delete BEFORE DELETE ON denetim_olayi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_veri_soyu_immutable_delete BEFORE DELETE ON veri_soyu_olayi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_tanim_veri_nesnesi_immutable_delete BEFORE DELETE ON tanim_veri_nesnesi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_yayin_veri_bagi_immutable_delete BEFORE DELETE ON yayin_veri_bagi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();

CREATE TRIGGER tr_sema_goruntusu_immutable_update
    BEFORE UPDATE OR DELETE ON sema_goruntusu
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_kolon_goruntusu_immutable_update
    BEFORE UPDATE OR DELETE ON kolon_goruntusu
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_kisit_goruntusu_immutable_update
    BEFORE UPDATE OR DELETE ON kisit_goruntusu
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_kisit_kolonu_immutable_update
    BEFORE UPDATE OR DELETE ON kisit_kolonu
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();

ALTER TABLE yayin
    ADD COLUMN release_hash TEXT GENERATED ALWAYS AS
        (fiziksel_manifesto ->> 'releaseHash') STORED,
    ADD CONSTRAINT ck_yayin_release_hash CHECK (release_hash ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX uq_yayin_release_hash
    ON yayin(senaryo_id, ortam_id, release_hash);

CREATE FUNCTION yayin_cekirdek_alanlarini_koru() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.proje_id IS DISTINCT FROM OLD.proje_id
       OR NEW.senaryo_id IS DISTINCT FROM OLD.senaryo_id
       OR NEW.ortam_id IS DISTINCT FROM OLD.ortam_id
       OR NEW.yayin_no IS DISTINCT FROM OLD.yayin_no
       OR NEW.bagimlilik_ozeti IS DISTINCT FROM OLD.bagimlilik_ozeti
       OR NEW.fiziksel_manifesto IS DISTINCT FROM OLD.fiziksel_manifesto
       OR NEW.uuid IS DISTINCT FROM OLD.uuid
       OR NEW.olusturulma_zamani IS DISTINCT FROM OLD.olusturulma_zamani THEN
        RAISE EXCEPTION 'yayin tablosundaki sabitlenmiş çekirdek alanlar güncellenemez';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_yayin_cekirdek_immutable
    BEFORE UPDATE ON yayin
    FOR EACH ROW EXECUTE FUNCTION yayin_cekirdek_alanlarini_koru();
CREATE TRIGGER tr_yayin_immutable_delete BEFORE DELETE ON yayin
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
CREATE TRIGGER tr_yayin_onayi_immutable
    BEFORE UPDATE OR DELETE ON yayin_onayi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();
