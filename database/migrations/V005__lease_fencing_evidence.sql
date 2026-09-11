SET search_path TO entegrasyon, public;

-- calistirma.plan_ozeti was populated with the publication release hash in the
-- manual control-plane slice. Preserve that evidence separately, then restore
-- plan_ozeti to the immutable Scenario plan hash.
ALTER TABLE calistirma
    ADD COLUMN yayin_ozeti TEXT;

ALTER TABLE calistirma DISABLE TRIGGER tr_calistirma_immutable;

UPDATE calistirma c
   SET yayin_ozeti = y.release_hash,
       plan_ozeti = s.plan_ozeti
  FROM is_talebi it
  JOIN yayin y ON y.proje_id = it.proje_id AND y.id = it.yayin_id
  JOIN senaryo s ON s.id = y.senaryo_id
 WHERE it.proje_id = c.proje_id
   AND it.id = c.is_talebi_id;

ALTER TABLE calistirma ENABLE TRIGGER tr_calistirma_immutable;

ALTER TABLE calistirma
    ADD CONSTRAINT ck_calistirma_yayin_ozeti
        CHECK (yayin_ozeti IS NULL OR yayin_ozeti ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_calistirma_plan_ozeti
        CHECK (plan_ozeti ~ '^[0-9a-f]{64}$');

ALTER TABLE hedef_kaynagi
    ADD COLUMN kimlik_surumu INTEGER NOT NULL DEFAULT 1,
    DROP CONSTRAINT ck_hedef_kaynagi_sahip,
    ADD CONSTRAINT ck_hedef_kaynagi_kimlik
        CHECK (kimlik_surumu > 0 AND fiziksel_ozet ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_hedef_kaynagi_sahip CHECK (
        (durum_kodu = 'SAHIPLENILDI'
            AND calistirma_id IS NOT NULL
            AND kiralama_bitis_zamani IS NOT NULL)
        OR (durum_kodu IN ('BOS', 'ASKIDA')
            AND calistirma_id IS NULL
            AND kiralama_bitis_zamani IS NULL));

CREATE FUNCTION hedef_kaynagi_degisikligini_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
BEGIN
    IF NEW.fiziksel_ozet IS DISTINCT FROM OLD.fiziksel_ozet
       OR NEW.kimlik_surumu IS DISTINCT FROM OLD.kimlik_surumu
       OR NEW.uuid IS DISTINCT FROM OLD.uuid
       OR NEW.olusturulma_zamani IS DISTINCT FROM OLD.olusturulma_zamani THEN
        RAISE EXCEPTION 'Hedef kaynağı kimlik alanları güncellenemez';
    END IF;

    IF NEW.versiyon_no <> OLD.versiyon_no + 1 THEN
        RAISE EXCEPTION 'Hedef kaynağı versiyonu tam olarak bir artırılmalıdır';
    END IF;

    IF NEW.nesil_no < OLD.nesil_no THEN
        RAISE EXCEPTION 'Hedef kaynağı nesli azaltılamaz';
    END IF;

    IF OLD.durum_kodu = 'BOS' AND NEW.durum_kodu = 'SAHIPLENILDI' THEN
        IF NEW.calistirma_id IS NULL
           OR NEW.nesil_no <> OLD.nesil_no + 1 THEN
            RAISE EXCEPTION 'Hedef sahiplenme yeni sahip ve tam bir nesil artışı gerektirir';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = 'SAHIPLENILDI' AND NEW.durum_kodu = 'SAHIPLENILDI' THEN
        IF NEW.calistirma_id IS DISTINCT FROM OLD.calistirma_id
           OR NEW.nesil_no <> OLD.nesil_no THEN
            RAISE EXCEPTION 'Aktif hedef sahibi veya nesli heartbeat sırasında değiştirilemez';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = 'SAHIPLENILDI'
       AND NEW.durum_kodu IN ('BOS', 'ASKIDA') THEN
        IF NEW.nesil_no <> OLD.nesil_no THEN
            RAISE EXCEPTION 'Hedef bırakılırken fencing nesli değiştirilemez';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = 'ASKIDA' AND NEW.durum_kodu = 'BOS' THEN
        IF NEW.nesil_no <> OLD.nesil_no THEN
            RAISE EXCEPTION 'Askıdan çıkarılırken fencing nesli değiştirilemez';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = 'BOS' AND NEW.durum_kodu = 'ASKIDA' THEN
        IF NEW.nesil_no <> OLD.nesil_no THEN
            RAISE EXCEPTION 'Boş hedef askıya alınırken fencing nesli değiştirilemez';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.durum_kodu = NEW.durum_kodu
       AND OLD.durum_kodu IN ('BOS', 'ASKIDA')
       AND NEW.nesil_no = OLD.nesil_no THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'Geçersiz hedef kaynağı geçişi: % -> %',
        OLD.durum_kodu, NEW.durum_kodu;
END;
$$;

CREATE TRIGGER tr_hedef_kaynagi_degisikligi
    BEFORE UPDATE ON hedef_kaynagi
    FOR EACH ROW EXECUTE FUNCTION hedef_kaynagi_degisikligini_dogrula();

CREATE INDEX ix_hedef_kaynagi_durum_lease
    ON hedef_kaynagi(durum_kodu, kiralama_bitis_zamani);
CREATE INDEX ix_hedef_kaynagi_calistirma
    ON hedef_kaynagi(calistirma_id)
    WHERE calistirma_id IS NOT NULL;

-- V004 canonically renamed the legacy claimed state. Legacy rows did not yet
-- have a generation counter, so establish the first generation before the
-- stricter lease shape becomes valid.
UPDATE calistirma_durumu
   SET nesil_no = 1,
       guncellenme_zamani = clock_timestamp(),
       versiyon_no = versiyon_no + 1
 WHERE durum_kodu = 'HAZIRLANIYOR'
   AND nesil_no = 0;

ALTER TABLE calistirma_durumu
    DROP CONSTRAINT ck_calistirma_durumu_lease_sekli,
    ADD CONSTRAINT ck_calistirma_durumu_hedef_cifti CHECK (
        (hedef_kaynagi_id IS NULL AND hedef_nesil_no IS NULL)
        OR (hedef_kaynagi_id IS NOT NULL AND hedef_nesil_no IS NOT NULL
            AND hedef_nesil_no > 0)),
    ADD CONSTRAINT ck_calistirma_durumu_lease_sekli CHECK (
        (durum_kodu = 'BEKLIYOR'
            AND nesil_no = 0
            AND hedef_kaynagi_id IS NULL
            AND hedef_nesil_no IS NULL
            AND worker_profili_id IS NULL
            AND isleyici_referansi IS NULL
            AND kiralama_bitis_zamani IS NULL
            AND yasam_sinyali_zamani IS NULL
            AND baslama_zamani IS NULL
            AND bitis_zamani IS NULL)
        OR (durum_kodu = 'HAZIRLANIYOR'
            AND nesil_no > 0
            AND worker_profili_id IS NOT NULL
            AND isleyici_referansi IS NOT NULL
            AND kiralama_bitis_zamani IS NOT NULL
            AND yasam_sinyali_zamani IS NOT NULL
            AND baslama_zamani IS NOT NULL
            AND bitis_zamani IS NULL)
        OR (durum_kodu IN (
                'CALISIYOR', 'YAYINLANIYOR', 'IPTAL_ISTENDI', 'MUTABAKAT')
            AND nesil_no > 0
            AND hedef_kaynagi_id IS NOT NULL
            AND hedef_nesil_no IS NOT NULL
            AND worker_profili_id IS NOT NULL
            AND isleyici_referansi IS NOT NULL
            AND kiralama_bitis_zamani IS NOT NULL
            AND yasam_sinyali_zamani IS NOT NULL
            AND baslama_zamani IS NOT NULL
            AND bitis_zamani IS NULL)
        OR (durum_kodu IN (
                'SONUC_BELIRSIZ', 'YENIDEN_DENENEBILIR',
                'MUDAHALE_GEREKLI', 'BASARILI', 'BASARISIZ', 'IPTAL')
            AND kiralama_bitis_zamani IS NULL
            AND bitis_zamani IS NOT NULL));

CREATE FUNCTION calistirma_lease_sahipligini_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    eski_aktif BOOLEAN := OLD.durum_kodu IN (
        'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR', 'IPTAL_ISTENDI', 'MUTABAKAT');
    yeni_aktif BOOLEAN := NEW.durum_kodu IN (
        'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR', 'IPTAL_ISTENDI', 'MUTABAKAT');
    sahiplenme BOOLEAN := (OLD.durum_kodu = 'BEKLIYOR' AND NEW.durum_kodu = 'HAZIRLANIYOR')
        OR (OLD.durum_kodu = 'SONUC_BELIRSIZ' AND NEW.durum_kodu = 'MUTABAKAT');
BEGIN
    IF sahiplenme THEN
        IF NEW.nesil_no <> OLD.nesil_no + 1 THEN
            RAISE EXCEPTION 'Çalıştırma sahiplenmesi tam bir nesil artışı gerektirir';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.nesil_no <> OLD.nesil_no THEN
        RAISE EXCEPTION 'Çalıştırma nesli yalnız sahiplenme sırasında artırılabilir';
    END IF;

    IF eski_aktif AND yeni_aktif
       AND (NEW.worker_profili_id IS DISTINCT FROM OLD.worker_profili_id
            OR NEW.isleyici_referansi IS DISTINCT FROM OLD.isleyici_referansi) THEN
        RAISE EXCEPTION 'Aktif lease sahibi nesil artırılmadan değiştirilemez';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_calistirma_lease_sahipligi
    BEFORE UPDATE ON calistirma_durumu
    FOR EACH ROW EXECUTE FUNCTION calistirma_lease_sahipligini_dogrula();

ALTER TABLE kontrol_noktasi
    ADD COLUMN hedef_kaynagi_id BIGINT REFERENCES hedef_kaynagi(id),
    ADD COLUMN hedef_nesil_no BIGINT,
    ADD COLUMN yayin_ozeti TEXT,
    ADD COLUMN plan_ozeti TEXT,
    ADD COLUMN payload_ozeti TEXT,
    ADD CONSTRAINT ck_kontrol_noktasi_hedef_kaniti CHECK (
        (hedef_kaynagi_id IS NULL
            AND hedef_nesil_no IS NULL
            AND yayin_ozeti IS NULL
            AND plan_ozeti IS NULL
            AND payload_ozeti IS NULL)
        OR (hedef_kaynagi_id IS NOT NULL
            AND hedef_nesil_no > 0
            AND yayin_ozeti ~ '^[0-9a-f]{64}$'
            AND plan_ozeti ~ '^[0-9a-f]{64}$'
            AND payload_ozeti ~ '^[0-9a-f]{64}$')),
    ADD CONSTRAINT uq_kontrol_noktasi_hedef_defter
        UNIQUE (hedef_kaynagi_id, hedef_defter_referansi);

CREATE FUNCTION kontrol_noktasi_kanitini_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
BEGIN
    IF NEW.hedef_kaynagi_id IS NULL
       OR NEW.hedef_nesil_no IS NULL
       OR NEW.yayin_ozeti IS NULL
       OR NEW.plan_ozeti IS NULL
       OR NEW.payload_ozeti IS NULL THEN
        RAISE EXCEPTION 'Yeni checkpoint doğrulanmış hedef defteri kanıtı gerektirir';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM entegrasyon.calistirma c
          JOIN entegrasyon.calistirma_durumu cd
            ON cd.proje_id = c.proje_id AND cd.calistirma_id = c.id
         WHERE c.proje_id = NEW.proje_id
           AND c.id = NEW.calistirma_id
           AND c.yayin_ozeti = NEW.yayin_ozeti
           AND c.plan_ozeti = NEW.plan_ozeti
           AND cd.hedef_kaynagi_id = NEW.hedef_kaynagi_id
           AND cd.hedef_nesil_no = NEW.hedef_nesil_no
    ) THEN
        RAISE EXCEPTION 'Checkpoint run, release, plan ve hedef fencing kanıtıyla eşleşmelidir';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM entegrasyon.calistirma_adimi ca
         WHERE ca.proje_id = NEW.proje_id
           AND ca.calistirma_id = NEW.calistirma_id
           AND ca.id = NEW.calistirma_adimi_id
    ) THEN
        RAISE EXCEPTION 'Checkpoint adımı aynı çalıştırmaya ait olmalıdır';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_kontrol_noktasi_kaniti
    BEFORE INSERT ON kontrol_noktasi
    FOR EACH ROW EXECUTE FUNCTION kontrol_noktasi_kanitini_dogrula();

ALTER TABLE calistirma
    ADD COLUMN devam_kontrol_noktasi_id BIGINT REFERENCES kontrol_noktasi(id),
    ADD CONSTRAINT ck_calistirma_devam_sekli CHECK (
        (baslatma_turu = 'RESUME' AND devam_kontrol_noktasi_id IS NOT NULL)
        OR (baslatma_turu IN ('ILK', 'RETRY') AND devam_kontrol_noktasi_id IS NULL));

CREATE FUNCTION calistirma_devam_kanitini_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
BEGIN
    IF NEW.baslatma_turu <> 'RESUME' THEN
        RETURN NEW;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM entegrasyon.kontrol_noktasi kn
          JOIN entegrasyon.calistirma onceki
            ON onceki.proje_id = kn.proje_id AND onceki.id = kn.calistirma_id
         WHERE kn.id = NEW.devam_kontrol_noktasi_id
           AND kn.proje_id = NEW.proje_id
           AND onceki.is_talebi_id = NEW.is_talebi_id
           AND onceki.deneme_no < NEW.deneme_no
           AND kn.yayin_ozeti = NEW.yayin_ozeti
           AND kn.plan_ozeti = NEW.plan_ozeti
    ) THEN
        RAISE EXCEPTION 'Resume checkpoint aynı işin önceki deneme ve planına ait olmalıdır';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_calistirma_devam_kaniti
    BEFORE INSERT ON calistirma
    FOR EACH ROW EXECUTE FUNCTION calistirma_devam_kanitini_dogrula();

CREATE FUNCTION calistirma_hash_kanitini_dogrula() RETURNS trigger
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_is_turu TEXT;
    v_release_hash TEXT;
    v_plan_hash TEXT;
BEGIN
    SELECT it.is_turu, y.release_hash, s.plan_ozeti
      INTO v_is_turu, v_release_hash, v_plan_hash
      FROM is_talebi it
      LEFT JOIN yayin y ON y.proje_id = it.proje_id AND y.id = it.yayin_id
      LEFT JOIN senaryo s ON s.id = y.senaryo_id
     WHERE it.proje_id = NEW.proje_id AND it.id = NEW.is_talebi_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Çalıştırma iş talebi bulunamadı';
    END IF;

    IF v_is_turu = 'RUN' AND NEW.yayin_ozeti IS NULL THEN
        RAISE EXCEPTION 'RUN çalıştırması publication release özeti gerektirir';
    END IF;

    IF v_release_hash IS NULL THEN
        IF NEW.yayin_ozeti IS NOT NULL THEN
            RAISE EXCEPTION 'Yayınsız iş release özeti taşıyamaz';
        END IF;
    ELSIF NEW.yayin_ozeti IS DISTINCT FROM v_release_hash
          OR NEW.plan_ozeti IS DISTINCT FROM v_plan_hash THEN
        RAISE EXCEPTION 'Çalıştırma hash kanıtı sabitlenmiş yayın ve Scenario ile eşleşmelidir';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_calistirma_hash_kaniti
    BEFORE INSERT ON calistirma
    FOR EACH ROW EXECUTE FUNCTION calistirma_hash_kanitini_dogrula();

CREATE FUNCTION calistirma_sahiplen(
    p_worker_profili_uuid UUID,
    p_isleyici_referansi TEXT,
    p_lease_saniyesi INTEGER DEFAULT 60)
RETURNS TABLE (
    calistirma_uuid UUID,
    nesil_no BIGINT,
    yayin_ozeti TEXT,
    plan_ozeti TEXT,
    kiralama_bitis_zamani TIMESTAMPTZ)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_worker_profili_id BIGINT;
    v_durum calistirma_durumu%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_isleyici_referansi IS NULL OR btrim(p_isleyici_referansi) = '' THEN
        RAISE EXCEPTION 'İşleyici referansı zorunludur';
    END IF;
    IF p_lease_saniyesi < 30 OR p_lease_saniyesi > 300 THEN
        RAISE EXCEPTION 'Lease süresi 30 ile 300 saniye arasında olmalıdır';
    END IF;

    SELECT id INTO v_worker_profili_id
      FROM worker_profili
     WHERE uuid = p_worker_profili_uuid AND durum_kodu = 'AKTIF';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Aktif worker profili bulunamadı';
    END IF;

    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
      JOIN is_talebi it ON it.proje_id = c.proje_id AND it.id = c.is_talebi_id
      JOIN yayin y ON y.proje_id = it.proje_id AND y.id = it.yayin_id
      JOIN senaryo s ON s.id = y.senaryo_id
     WHERE cd.durum_kodu = 'BEKLIYOR'
       AND it.is_turu = 'RUN'
       AND y.durum_kodu = 'AKTIF'
       AND c.yayin_ozeti = y.release_hash
       AND c.plan_ozeti = s.plan_ozeti
       AND (it.planlanan_zamani IS NULL OR it.planlanan_zamani <= v_simdi)
     ORDER BY it.oncelik DESC,
              COALESCE(it.planlanan_zamani, it.olusturulma_zamani),
              it.id
     FOR UPDATE OF cd SKIP LOCKED
     LIMIT 1;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    UPDATE calistirma_durumu
       SET worker_profili_id = v_worker_profili_id,
           isleyici_referansi = p_isleyici_referansi,
           durum_kodu = 'HAZIRLANIYOR',
           nesil_no = v_durum.nesil_no + 1,
           son_olay_no = v_durum.son_olay_no + 1,
           kiralama_bitis_zamani = v_simdi + make_interval(secs => p_lease_saniyesi),
           yasam_sinyali_zamani = v_simdi,
           baslama_zamani = v_simdi,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;

    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (
        v_durum.proje_id, v_durum.calistirma_id, v_durum.son_olay_no + 1,
        'RUN_CLAIMED', v_simdi,
        jsonb_build_object('generation', v_durum.nesil_no + 1));

    RETURN QUERY
    SELECT c.uuid, cd.nesil_no, c.yayin_ozeti, c.plan_ozeti,
           cd.kiralama_bitis_zamani
      FROM calistirma c
      JOIN calistirma_durumu cd
        ON cd.proje_id = c.proje_id AND cd.calistirma_id = c.id
     WHERE c.id = v_durum.calistirma_id;
END;
$$;

CREATE FUNCTION calistirma_yasam_sinyali(
    p_calistirma_uuid UUID,
    p_isleyici_referansi TEXT,
    p_nesil_no BIGINT,
    p_lease_saniyesi INTEGER DEFAULT 60)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_lease_saniyesi < 30 OR p_lease_saniyesi > 300 THEN
        RAISE EXCEPTION 'Lease süresi 30 ile 300 saniye arasında olmalıdır';
    END IF;

    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
     WHERE c.uuid = p_calistirma_uuid
     FOR UPDATE OF cd;

    IF NOT FOUND
       OR v_durum.durum_kodu NOT IN (
           'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR', 'IPTAL_ISTENDI', 'MUTABAKAT')
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi THEN
        RETURN FALSE;
    END IF;

    IF v_durum.hedef_kaynagi_id IS NOT NULL THEN
        PERFORM 1
          FROM hedef_kaynagi hk
         WHERE hk.id = v_durum.hedef_kaynagi_id
           AND hk.calistirma_id = v_durum.calistirma_id
           AND hk.nesil_no = v_durum.hedef_nesil_no
           AND hk.durum_kodu = 'SAHIPLENILDI'
           AND hk.kiralama_bitis_zamani >= v_simdi
         FOR UPDATE;
        IF NOT FOUND THEN
            RETURN FALSE;
        END IF;

        UPDATE hedef_kaynagi
           SET kiralama_bitis_zamani = v_simdi + make_interval(secs => p_lease_saniyesi),
               guncellenme_zamani = v_simdi,
               versiyon_no = versiyon_no + 1
         WHERE id = v_durum.hedef_kaynagi_id;
    END IF;

    UPDATE calistirma_durumu
       SET kiralama_bitis_zamani = v_simdi + make_interval(secs => p_lease_saniyesi),
           yasam_sinyali_zamani = v_simdi,
           guncellenme_zamani = v_simdi,
           versiyon_no = versiyon_no + 1
     WHERE id = v_durum.id;

    RETURN TRUE;
END;
$$;

CREATE FUNCTION hedef_kaynagi_sahiplen(
    p_calistirma_uuid UUID,
    p_isleyici_referansi TEXT,
    p_calistirma_nesil_no BIGINT,
    p_fiziksel_ozet TEXT,
    p_kimlik_surumu INTEGER DEFAULT 1)
RETURNS TABLE (
    hedef_kaynagi_uuid UUID,
    hedef_nesil_no BIGINT,
    kiralama_bitis_zamani TIMESTAMPTZ)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_simdi TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF p_fiziksel_ozet !~ '^[0-9a-f]{64}$' OR p_kimlik_surumu <= 0 THEN
        RAISE EXCEPTION 'Kanonik hedef kimliği geçersiz';
    END IF;

    SELECT cd.* INTO v_durum
      FROM calistirma_durumu cd
      JOIN calistirma c ON c.proje_id = cd.proje_id AND c.id = cd.calistirma_id
     WHERE c.uuid = p_calistirma_uuid
     FOR UPDATE OF cd;

    IF NOT FOUND
       OR v_durum.durum_kodu <> 'HAZIRLANIYOR'
       OR v_durum.isleyici_referansi IS DISTINCT FROM p_isleyici_referansi
       OR v_durum.nesil_no <> p_calistirma_nesil_no
       OR v_durum.kiralama_bitis_zamani <= v_simdi
       OR v_durum.hedef_kaynagi_id IS NOT NULL THEN
        RAISE EXCEPTION 'Çalıştırma geçerli bir preflight lease sahibi değildir';
    END IF;

    INSERT INTO hedef_kaynagi(fiziksel_ozet, kimlik_surumu)
    VALUES (p_fiziksel_ozet, p_kimlik_surumu)
    ON CONFLICT (fiziksel_ozet) DO NOTHING;

    SELECT * INTO v_hedef
      FROM hedef_kaynagi
     WHERE fiziksel_ozet = p_fiziksel_ozet
     FOR UPDATE;

    IF v_hedef.kimlik_surumu <> p_kimlik_surumu THEN
        RAISE EXCEPTION 'Hedef kimlik şeması sürümü eşleşmiyor';
    END IF;

    IF v_hedef.durum_kodu = 'SAHIPLENILDI' THEN
        IF v_hedef.kiralama_bitis_zamani <= v_simdi THEN
            RAISE EXCEPTION 'Süresi dolmuş hedef sahibi mutabakat gerektirir';
        END IF;
        RAISE EXCEPTION 'Hedef başka bir çalıştırma tarafından sahiplenildi';
    END IF;
    IF v_hedef.durum_kodu = 'ASKIDA' THEN
        RAISE EXCEPTION 'Hedef mutabakat veya operasyon nedeniyle askıdadır';
    END IF;

    UPDATE hedef_kaynagi
       SET calistirma_id = v_durum.calistirma_id,
           durum_kodu = 'SAHIPLENILDI',
           nesil_no = v_hedef.nesil_no + 1,
           kiralama_bitis_zamani = v_durum.kiralama_bitis_zamani,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_hedef.versiyon_no + 1
     WHERE id = v_hedef.id;

    UPDATE calistirma_durumu
       SET hedef_kaynagi_id = v_hedef.id,
           hedef_nesil_no = v_hedef.nesil_no + 1,
           son_olay_no = v_durum.son_olay_no + 1,
           guncellenme_zamani = v_simdi,
           versiyon_no = v_durum.versiyon_no + 1
     WHERE id = v_durum.id;

    INSERT INTO calistirma_olayi(
        proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
    VALUES (
        v_durum.proje_id, v_durum.calistirma_id, v_durum.son_olay_no + 1,
        'TARGET_ACQUIRED', v_simdi,
        jsonb_build_object(
            'targetGeneration', v_hedef.nesil_no + 1,
            'targetIdentityVersion', p_kimlik_surumu));

    RETURN QUERY
    SELECT hk.uuid, hk.nesil_no, hk.kiralama_bitis_zamani
      FROM hedef_kaynagi hk
     WHERE hk.id = v_hedef.id;
END;
$$;

CREATE FUNCTION suresi_dolan_hedefleri_askiya_al(p_limit INTEGER DEFAULT 100)
RETURNS TABLE (
    calistirma_uuid UUID,
    hedef_kaynagi_uuid UUID,
    hedef_nesil_no BIGINT)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_simdi TIMESTAMPTZ := clock_timestamp();
    v_durum calistirma_durumu%ROWTYPE;
    v_hedef hedef_kaynagi%ROWTYPE;
    v_yeni_durum TEXT;
BEGIN
    IF p_limit < 1 OR p_limit > 1000 THEN
        RAISE EXCEPTION 'Reaper limiti 1 ile 1000 arasında olmalıdır';
    END IF;

    FOR v_durum IN
        SELECT cd.*
          FROM calistirma_durumu cd
         WHERE cd.durum_kodu IN (
                   'HAZIRLANIYOR', 'CALISIYOR', 'YAYINLANIYOR',
                   'IPTAL_ISTENDI', 'MUTABAKAT')
           AND cd.hedef_kaynagi_id IS NOT NULL
           AND cd.kiralama_bitis_zamani <= v_simdi
         ORDER BY cd.kiralama_bitis_zamani, cd.id
         FOR UPDATE SKIP LOCKED
         LIMIT p_limit
    LOOP
        SELECT * INTO v_hedef
          FROM hedef_kaynagi
         WHERE id = v_durum.hedef_kaynagi_id
         FOR UPDATE;

        IF NOT FOUND
           OR v_hedef.durum_kodu <> 'SAHIPLENILDI'
           OR v_hedef.calistirma_id <> v_durum.calistirma_id
           OR v_hedef.nesil_no <> v_durum.hedef_nesil_no
           OR v_hedef.kiralama_bitis_zamani > v_simdi THEN
            CONTINUE;
        END IF;

        v_yeni_durum := CASE
            WHEN v_durum.durum_kodu = 'MUTABAKAT' THEN 'MUDAHALE_GEREKLI'
            ELSE 'SONUC_BELIRSIZ'
        END;

        UPDATE hedef_kaynagi
           SET calistirma_id = NULL,
               durum_kodu = 'ASKIDA',
               kiralama_bitis_zamani = NULL,
               guncellenme_zamani = v_simdi,
               versiyon_no = v_hedef.versiyon_no + 1
         WHERE id = v_hedef.id;

        UPDATE calistirma_durumu
           SET durum_kodu = v_yeni_durum,
               son_olay_no = v_durum.son_olay_no + 1,
               kiralama_bitis_zamani = NULL,
               bitis_zamani = v_simdi,
               guncellenme_zamani = v_simdi,
               versiyon_no = v_durum.versiyon_no + 1
         WHERE id = v_durum.id;

        INSERT INTO calistirma_olayi(
            proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
        VALUES (
            v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1, 'LEASE_EXPIRED', v_simdi,
            jsonb_build_object(
                'targetGeneration', v_durum.hedef_nesil_no,
                'requiresReconciliation', TRUE));

        RETURN QUERY
        SELECT c.uuid, v_hedef.uuid, v_hedef.nesil_no
          FROM calistirma c
         WHERE c.id = v_durum.calistirma_id;
    END LOOP;
END;
$$;

-- A worker can die after claiming a run but before acquiring the Oracle target.
-- No target-side DML can have started in that shape, so this is a deterministic
-- failure rather than an uncertain result that would require reconciliation.
CREATE FUNCTION suresi_dolan_hedefsiz_hazirliklari_sonlandir(p_limit INTEGER DEFAULT 100)
RETURNS TABLE (
    calistirma_uuid UUID,
    nesil_no BIGINT)
LANGUAGE plpgsql
SET search_path = entegrasyon, public
AS $$
DECLARE
    v_simdi TIMESTAMPTZ := clock_timestamp();
    v_durum calistirma_durumu%ROWTYPE;
BEGIN
    IF p_limit < 1 OR p_limit > 1000 THEN
        RAISE EXCEPTION 'Reaper limiti 1 ile 1000 arasında olmalıdır';
    END IF;

    FOR v_durum IN
        SELECT cd.*
          FROM calistirma_durumu cd
         WHERE cd.durum_kodu = 'HAZIRLANIYOR'
           AND cd.hedef_kaynagi_id IS NULL
           AND cd.hedef_nesil_no IS NULL
           AND cd.kiralama_bitis_zamani <= v_simdi
         ORDER BY cd.kiralama_bitis_zamani, cd.id
         FOR UPDATE SKIP LOCKED
         LIMIT p_limit
    LOOP
        UPDATE calistirma_durumu
           SET durum_kodu = 'BASARISIZ',
               son_olay_no = v_durum.son_olay_no + 1,
               kiralama_bitis_zamani = NULL,
               bitis_zamani = v_simdi,
               guncellenme_zamani = v_simdi,
               versiyon_no = v_durum.versiyon_no + 1
         WHERE id = v_durum.id;

        INSERT INTO calistirma_olayi(
            proje_id, calistirma_id, olay_no, tur_kodu, olay_zamani, veri)
        VALUES (
            v_durum.proje_id, v_durum.calistirma_id,
            v_durum.son_olay_no + 1, 'PREPARATION_LEASE_EXPIRED', v_simdi,
            jsonb_build_object(
                'generation', v_durum.nesil_no,
                'targetAcquired', FALSE,
                'requiresReconciliation', FALSE));

        RETURN QUERY
        SELECT c.uuid, v_durum.nesil_no
          FROM calistirma c
         WHERE c.id = v_durum.calistirma_id;
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION calistirma_sahiplen(UUID, TEXT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION calistirma_yasam_sinyali(UUID, TEXT, BIGINT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION hedef_kaynagi_sahiplen(UUID, TEXT, BIGINT, TEXT, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION suresi_dolan_hedefleri_askiya_al(INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION suresi_dolan_hedefsiz_hazirliklari_sonlandir(INTEGER) FROM PUBLIC;

COMMENT ON COLUMN hedef_kaynagi.fiziksel_ozet IS
    'DB/PDB, owner ve nesne kimliğinin şema sürümlü kanonik SHA-256 özeti; host alias içermez';
COMMENT ON COLUMN calistirma.yayin_ozeti IS
    'Çalıştırmanın sabitlediği immutable publication release SHA-256 özeti';
COMMENT ON COLUMN calistirma.plan_ozeti IS
    'Çalıştırmanın sabitlediği immutable Scenario plan SHA-256 özeti';
COMMENT ON FUNCTION calistirma_sahiplen(UUID, TEXT, INTEGER) IS
    'Yalnız PostgreSQL kısa transaction claim işlemi; Oracle veya ağ erişimi yapmaz';
