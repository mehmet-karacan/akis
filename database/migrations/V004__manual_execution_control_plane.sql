SET search_path TO entegrasyon, public;

INSERT INTO yetki(kod, kapsam_kodu, ad, aciklama) VALUES
    ('RUN_READ', 'URETIM', 'Çalıştırma okuma', 'Proje çalıştırma geçmişini ve güvenli olay özetlerini görüntüler'),
    ('RUN_START', 'URETIM', 'Çalıştırma başlatma', 'Aktif non-production yayın için manuel iş talebi oluşturur'),
    ('RUN_CANCEL', 'URETIM', 'Çalıştırma iptali', 'Başlamamış veya güvenli iptal destekleyen çalıştırmayı iptal eder'),
    ('PRODUCTION_RUN', 'URETIM', 'Üretim çalıştırma', 'Üretim risk sınıfındaki ortamda çalıştırma talebi oluşturur');

ALTER TABLE yayin
    DROP CONSTRAINT IF EXISTS ck_yayin_release_hash,
    ADD CONSTRAINT ck_yayin_release_hash
        CHECK (release_hash IS NOT NULL AND release_hash ~ '^[0-9a-f]{64}$');

CREATE OR REPLACE FUNCTION varsayilan_proje_rollerini_olustur() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO entegrasyon.proje_rolu(proje_id, kod, ad, aciklama) VALUES
        (NEW.id, 'PROJE_YONETICISI', 'Proje Yöneticisi', 'Projeyi yönetir; üretim çalıştırma yetkisi ayrıca verilir'),
        (NEW.id, 'GELISTIRICI', 'Geliştirici', 'ETL tasarımı ve discovery işlemlerini yürütür'),
        (NEW.id, 'CALISTIRICI', 'Çalıştırıcı', 'Non-production yayınları çalıştırır ve çalışma geçmişini izler'),
        (NEW.id, 'IZLEYICI', 'İzleyici', 'Proje içeriğini salt okunur görüntüler');

    INSERT INTO entegrasyon.proje_rolu_yetkisi(proje_id, proje_rolu_id, yetki_id)
    SELECT NEW.id, pr.id, y.id
      FROM entegrasyon.proje_rolu pr
      JOIN entegrasyon.yetki y ON y.kapsam_kodu IN ('PROJE', 'KAYNAK', 'URETIM')
     WHERE pr.proje_id = NEW.id
       AND (
           (pr.kod = 'PROJE_YONETICISI' AND y.kod <> 'PRODUCTION_RUN')
           OR (pr.kod = 'GELISTIRICI' AND y.kod NOT IN (
               'SECRET_READ', 'SECRET_WRITE', 'PUBLICATION_APPROVE',
               'PROJECT_MEMBERSHIP_MANAGE', 'RUN_START', 'RUN_CANCEL',
               'PRODUCTION_RUN'))
           OR (pr.kod = 'CALISTIRICI' AND y.kod IN (
               'PROJECT_READ', 'TOPOLOGY_READ', 'CATALOG_READ', 'DISCOVERY_READ',
               'SCENARIO_READ', 'PUBLICATION_READ', 'RUN_READ', 'RUN_START', 'RUN_CANCEL'))
           OR (pr.kod = 'IZLEYICI' AND y.kod IN (
               'PROJECT_READ', 'TOPOLOGY_READ', 'CATALOG_READ', 'DISCOVERY_READ',
               'SCENARIO_READ', 'PUBLICATION_READ', 'RUN_READ'))
       );
    RETURN NEW;
END;
$$;

INSERT INTO proje_rolu(proje_id, kod, ad, aciklama)
SELECT p.id, 'CALISTIRICI', 'Çalıştırıcı',
       'Non-production yayınları çalıştırır ve çalışma geçmişini izler'
  FROM proje p
ON CONFLICT (proje_id, kod) DO NOTHING;

INSERT INTO proje_rolu_yetkisi(proje_id, proje_rolu_id, yetki_id)
SELECT pr.proje_id, pr.id, y.id
  FROM proje_rolu pr
  JOIN yetki y ON y.kapsam_kodu IN ('PROJE', 'KAYNAK', 'URETIM')
 WHERE (
       (pr.kod = 'PROJE_YONETICISI' AND y.kod <> 'PRODUCTION_RUN')
       OR (pr.kod = 'GELISTIRICI' AND y.kod NOT IN (
           'SECRET_READ', 'SECRET_WRITE', 'PUBLICATION_APPROVE',
           'PROJECT_MEMBERSHIP_MANAGE', 'RUN_START', 'RUN_CANCEL',
           'PRODUCTION_RUN'))
       OR (pr.kod = 'CALISTIRICI' AND y.kod IN (
           'PROJECT_READ', 'TOPOLOGY_READ', 'CATALOG_READ', 'DISCOVERY_READ',
           'SCENARIO_READ', 'PUBLICATION_READ', 'RUN_READ', 'RUN_START', 'RUN_CANCEL'))
       OR (pr.kod = 'IZLEYICI' AND y.kod IN (
           'PROJECT_READ', 'TOPOLOGY_READ', 'CATALOG_READ', 'DISCOVERY_READ',
           'SCENARIO_READ', 'PUBLICATION_READ', 'RUN_READ'))
   )
ON CONFLICT (proje_rolu_id, yetki_id) DO NOTHING;

ALTER TABLE zamanlama
    ADD CONSTRAINT uq_zamanlama_proje_id_id UNIQUE (proje_id, id);

ALTER TABLE is_talebi
    DROP CONSTRAINT is_talebi_zamanlama_id_fkey,
    ADD CONSTRAINT fk_is_talebi_zamanlama
        FOREIGN KEY (proje_id, zamanlama_id)
        REFERENCES zamanlama(proje_id, id);

ALTER TABLE calistirma_durumu
    DROP CONSTRAINT ck_calistirma_durumu_kod;

UPDATE calistirma_durumu
   SET durum_kodu = CASE durum_kodu
       WHEN 'SAHIPLENILDI' THEN 'HAZIRLANIYOR'
       WHEN 'SONUCU_BILINMIYOR' THEN 'SONUC_BELIRSIZ'
       ELSE durum_kodu
   END
 WHERE durum_kodu IN ('SAHIPLENILDI', 'SONUCU_BILINMIYOR');

ALTER TABLE calistirma_durumu
    ADD CONSTRAINT ck_calistirma_durumu_kod CHECK (durum_kodu IN (
        'BEKLIYOR', 'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR',
        'IPTAL_ISTENDI', 'SONUC_BELIRSIZ', 'MUTABAKAT',
        'YENIDEN_DENENEBILIR', 'MUDAHALE_GEREKLI',
        'BASARILI', 'BASARISIZ', 'IPTAL')),
    ADD CONSTRAINT ck_calistirma_durumu_lease_sekli CHECK (
        (durum_kodu = 'BEKLIYOR'
            AND isleyici_referansi IS NULL
            AND kiralama_bitis_zamani IS NULL
            AND yasam_sinyali_zamani IS NULL
            AND baslama_zamani IS NULL
            AND bitis_zamani IS NULL)
        OR (durum_kodu IN (
                'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR',
                'IPTAL_ISTENDI', 'MUTABAKAT')
            AND isleyici_referansi IS NOT NULL
            AND kiralama_bitis_zamani IS NOT NULL
            AND yasam_sinyali_zamani IS NOT NULL
            AND bitis_zamani IS NULL)
        OR (durum_kodu IN ('SONUC_BELIRSIZ', 'YENIDEN_DENENEBILIR',
                           'MUDAHALE_GEREKLI', 'BASARILI', 'BASARISIZ', 'IPTAL')
            AND kiralama_bitis_zamani IS NULL
            AND bitis_zamani IS NOT NULL));

CREATE FUNCTION calistirma_durumu_gecisini_dogrula() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.proje_id IS DISTINCT FROM OLD.proje_id
       OR NEW.calistirma_id IS DISTINCT FROM OLD.calistirma_id
       OR NEW.uuid IS DISTINCT FROM OLD.uuid
       OR NEW.olusturulma_zamani IS DISTINCT FROM OLD.olusturulma_zamani THEN
        RAISE EXCEPTION 'Çalıştırma durumu kimlik alanları güncellenemez';
    END IF;

    IF NEW.nesil_no < OLD.nesil_no OR NEW.son_olay_no < OLD.son_olay_no THEN
        RAISE EXCEPTION 'Çalıştırma nesli ve son olay numarası azaltılamaz';
    END IF;

    IF NEW.versiyon_no <> OLD.versiyon_no + 1 THEN
        RAISE EXCEPTION 'Çalıştırma durumu versiyonu tam olarak bir artırılmalıdır';
    END IF;

    IF OLD.durum_kodu = NEW.durum_kodu THEN
        IF OLD.durum_kodu IN (
            'YENIDEN_DENENEBILIR', 'MUDAHALE_GEREKLI',
            'BASARILI', 'BASARISIZ', 'IPTAL') THEN
            RAISE EXCEPTION 'Terminal çalıştırma durumu güncellenemez';
        END IF;
        RETURN NEW;
    END IF;

    IF (OLD.durum_kodu, NEW.durum_kodu) IN (
        ('BEKLIYOR', 'HAZIRLANIYOR'),
        ('BEKLIYOR', 'IPTAL'),
        ('HAZIRLANIYOR', 'IPTAL_ISTENDI'),
        ('HAZIRLANIYOR', 'CALISIYOR'),
        ('HAZIRLANIYOR', 'BASARISIZ'),
        ('HAZIRLANIYOR', 'SONUC_BELIRSIZ'),
        ('CALISIYOR', 'YAYINLANIYOR'),
        ('CALISIYOR', 'BASARILI'),
        ('CALISIYOR', 'BASARISIZ'),
        ('CALISIYOR', 'IPTAL_ISTENDI'),
        ('CALISIYOR', 'SONUC_BELIRSIZ'),
        ('YAYINLANIYOR', 'BASARILI'),
        ('YAYINLANIYOR', 'BASARISIZ'),
        ('YAYINLANIYOR', 'IPTAL_ISTENDI'),
        ('YAYINLANIYOR', 'SONUC_BELIRSIZ'),
        ('IPTAL_ISTENDI', 'IPTAL'),
        ('IPTAL_ISTENDI', 'SONUC_BELIRSIZ'),
        ('IPTAL_ISTENDI', 'BASARILI'),
        ('SONUC_BELIRSIZ', 'MUTABAKAT'),
        ('MUTABAKAT', 'BASARILI'),
        ('MUTABAKAT', 'IPTAL'),
        ('MUTABAKAT', 'YENIDEN_DENENEBILIR'),
        ('MUTABAKAT', 'MUDAHALE_GEREKLI')) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Geçersiz çalıştırma durumu geçişi: % -> %',
        OLD.durum_kodu, NEW.durum_kodu;
END;
$$;

CREATE TRIGGER tr_calistirma_durumu_gecisi
    BEFORE UPDATE ON calistirma_durumu
    FOR EACH ROW EXECUTE FUNCTION calistirma_durumu_gecisini_dogrula();

CREATE TRIGGER tr_is_talebi_immutable
    BEFORE UPDATE OR DELETE ON is_talebi
    FOR EACH ROW EXECUTE FUNCTION immutable_update_engelle();

CREATE FUNCTION calistirma_olayi_hiyerarsisini_dogrula() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.calistirma_gorevi_id IS NOT NULL AND NEW.calistirma_adimi_id IS NULL THEN
        RAISE EXCEPTION 'Çalıştırma görevi olayı bir adım referansı gerektirir';
    END IF;

    IF NEW.calistirma_adimi_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM entegrasyon.calistirma_adimi ca
         WHERE ca.proje_id = NEW.proje_id
           AND ca.calistirma_id = NEW.calistirma_id
           AND ca.id = NEW.calistirma_adimi_id
    ) THEN
        RAISE EXCEPTION 'Olay adımı aynı proje ve çalıştırmaya ait olmalıdır';
    END IF;

    IF NEW.calistirma_gorevi_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
          FROM entegrasyon.calistirma_gorevi cg
          JOIN entegrasyon.calistirma_adimi ca
            ON ca.proje_id = cg.proje_id
           AND ca.id = cg.calistirma_adimi_id
         WHERE cg.proje_id = NEW.proje_id
           AND cg.id = NEW.calistirma_gorevi_id
           AND ca.id = NEW.calistirma_adimi_id
           AND ca.calistirma_id = NEW.calistirma_id
    ) THEN
        RAISE EXCEPTION 'Olay görevi aynı adım ve çalıştırmaya ait olmalıdır';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_calistirma_olayi_hiyerarsisi
    BEFORE INSERT OR UPDATE ON calistirma_olayi
    FOR EACH ROW EXECUTE FUNCTION calistirma_olayi_hiyerarsisini_dogrula();

CREATE INDEX ix_is_talebi_proje_olusturma
    ON is_talebi(proje_id, olusturulma_zamani DESC, id DESC);
CREATE INDEX ix_is_talebi_claim_sirasi
    ON is_talebi(
        oncelik DESC,
        (COALESCE(planlanan_zamani, olusturulma_zamani)),
        id);
CREATE INDEX ix_calistirma_proje_is
    ON calistirma(proje_id, is_talebi_id, deneme_no DESC);
CREATE INDEX ix_calistirma_durumu_proje_durum
    ON calistirma_durumu(proje_id, durum_kodu, guncellenme_zamani DESC NULLS LAST);
CREATE INDEX ix_calistirma_adimi_run_sira
    ON calistirma_adimi(proje_id, calistirma_id, sira_no);
CREATE INDEX ix_calistirma_gorevi_adim_sira
    ON calistirma_gorevi(proje_id, calistirma_adimi_id, sira_no);
