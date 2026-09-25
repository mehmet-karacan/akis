-- AKIS PostgreSQL target ledger API. Same semantics as Oracle ETL_KANIT_PKG; error SQLSTATEs are AK0nn where nn matches
-- the Oracle -200nn code the Java adapter already understands.
--   fence: cit_al (acquire), cit_oku (read)
--   data:  parti_hazirla / parti_kaydet, yayin_hazirla / yayin_kaydet   (prepare/record must share one transaction)
--   read:  parti_dogrula / yayin_dogrula                                 (reconciliation on a fresh connection)
-- Transaction guards live in transaction-local settings (set_config(..., true)), so a guard can never outlive the
-- transaction that prepared it; both the guard and the evidence signature are re-checked at record time.
SET search_path TO akis, public;

CREATE OR REPLACE FUNCTION ozet_dogrula(p_deger TEXT, p_ad TEXT) RETURNS VOID LANGUAGE plpgsql IMMUTABLE SET search_path = akis, pg_catalog AS $$
BEGIN
    IF p_deger IS NULL OR p_deger !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION '% must be lowercase SHA-256', p_ad USING ERRCODE = 'AK001';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION kod_dogrula(p_deger TEXT, p_ad TEXT) RETURNS VOID LANGUAGE plpgsql IMMUTABLE SET search_path = akis, pg_catalog AS $$
BEGIN
    IF p_deger IS NULL OR length(p_deger) > 128 OR p_deger !~ '^[A-Za-z0-9_.:-]+$' THEN
        RAISE EXCEPTION '% is not a canonical code', p_ad USING ERRCODE = 'AK003';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION sahip_dogrula(
    p_hedef TEXT, p_cit BIGINT, p_is UUID, p_calistirma UUID, p_deneme INTEGER, p_surum TEXT, p_plan TEXT)
RETURNS VOID LANGUAGE plpgsql IMMUTABLE SET search_path = akis, pg_catalog AS $$
BEGIN
    PERFORM ozet_dogrula(p_hedef, 'target key hash');
    IF p_cit IS NULL OR p_cit < 1 THEN RAISE EXCEPTION 'fence token must be a positive integer' USING ERRCODE = 'AK006'; END IF;
    IF p_is IS NULL THEN RAISE EXCEPTION 'job UUID is required' USING ERRCODE = 'AK002'; END IF;
    IF p_calistirma IS NULL THEN RAISE EXCEPTION 'run UUID is required' USING ERRCODE = 'AK002'; END IF;
    IF p_deneme IS NULL OR p_deneme < 1 THEN RAISE EXCEPTION 'attempt number must be a positive integer' USING ERRCODE = 'AK004'; END IF;
    PERFORM ozet_dogrula(p_surum, 'release hash');
    PERFORM ozet_dogrula(p_plan, 'plan hash');
END $$;

-- Locks the fence row and verifies that the caller still owns it.
CREATE OR REPLACE FUNCTION cit_kilitle_ve_dogrula(
    p_hedef TEXT, p_cit BIGINT, p_is UUID, p_calistirma UUID, p_deneme INTEGER, p_surum TEXT, p_plan TEXT)
RETURNS VOID LANGUAGE plpgsql SET search_path = akis, pg_catalog AS $$
DECLARE v hedef_citi%ROWTYPE;
BEGIN
    PERFORM sahip_dogrula(p_hedef, p_cit, p_is, p_calistirma, p_deneme, p_surum, p_plan);
    SELECT * INTO v FROM hedef_citi WHERE hedef_anahtar_ozeti = p_hedef FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'target fence does not exist' USING ERRCODE = 'AK010'; END IF;
    IF v.cit_belirteci <> p_cit OR v.sahip_is_uuid <> p_is OR v.sahip_calistirma_uuid <> p_calistirma
       OR v.sahip_deneme_no <> p_deneme OR v.surum_ozeti <> p_surum OR v.plan_ozeti <> p_plan THEN
        RAISE EXCEPTION 'target fence ownership does not match' USING ERRCODE = 'AK011';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION cit_al(
    p_hedef TEXT, p_cit BIGINT, p_is UUID, p_calistirma UUID, p_deneme INTEGER, p_surum TEXT, p_plan TEXT)
RETURNS VOID LANGUAGE plpgsql SET search_path = akis, pg_catalog AS $$
DECLARE v hedef_citi%ROWTYPE;
BEGIN
    PERFORM sahip_dogrula(p_hedef, p_cit, p_is, p_calistirma, p_deneme, p_surum, p_plan);
    INSERT INTO hedef_citi(hedef_anahtar_ozeti, cit_belirteci, sahip_is_uuid, sahip_calistirma_uuid, sahip_deneme_no, surum_ozeti, plan_ozeti)
    VALUES (p_hedef, p_cit, p_is, p_calistirma, p_deneme, p_surum, p_plan)
    ON CONFLICT (hedef_anahtar_ozeti) DO NOTHING;
    IF FOUND THEN RETURN; END IF;
    SELECT * INTO v FROM hedef_citi WHERE hedef_anahtar_ozeti = p_hedef FOR UPDATE;
    IF p_cit < v.cit_belirteci THEN
        RAISE EXCEPTION 'stale target fence token' USING ERRCODE = 'AK012';
    ELSIF p_cit = v.cit_belirteci THEN
        IF v.sahip_is_uuid <> p_is OR v.sahip_calistirma_uuid <> p_calistirma OR v.sahip_deneme_no <> p_deneme
           OR v.surum_ozeti <> p_surum OR v.plan_ozeti <> p_plan THEN
            RAISE EXCEPTION 'same fence token has a different owner' USING ERRCODE = 'AK013';
        END IF;
        RETURN;
    END IF;
    UPDATE hedef_citi
       SET cit_belirteci = p_cit, sahip_is_uuid = p_is, sahip_calistirma_uuid = p_calistirma, sahip_deneme_no = p_deneme,
           surum_ozeti = p_surum, plan_ozeti = p_plan, guncellenme_zamani = clock_timestamp()
     WHERE hedef_anahtar_ozeti = p_hedef;
END $$;

CREATE OR REPLACE FUNCTION cit_oku(p_hedef TEXT)
RETURNS TABLE(bulundu BOOLEAN, cit_belirteci BIGINT, is_uuid UUID, calistirma_uuid UUID, deneme_no INTEGER,
              surum_ozeti TEXT, plan_ozeti TEXT, guncellenme_zamani TIMESTAMPTZ)
LANGUAGE plpgsql STABLE SET search_path = akis, pg_catalog AS $$
BEGIN
    PERFORM ozet_dogrula(p_hedef, 'target key hash');
    RETURN QUERY
    SELECT TRUE, h.cit_belirteci, h.sahip_is_uuid, h.sahip_calistirma_uuid, h.sahip_deneme_no,
           h.surum_ozeti::TEXT, h.plan_ozeti::TEXT, h.guncellenme_zamani
      FROM hedef_citi h WHERE h.hedef_anahtar_ozeti = p_hedef;
    IF NOT FOUND THEN RETURN QUERY SELECT FALSE, NULL::BIGINT, NULL::UUID, NULL::UUID, NULL::INTEGER, NULL::TEXT, NULL::TEXT, NULL::TIMESTAMPTZ; END IF;
END $$;

-- ---------------------------------------------------------------------------------------------------------------
-- Publish evidence
-- ---------------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION yayin_girdi_dogrula(
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_yayin_anahtar TEXT, p_calistirma UUID, p_deneme INTEGER, p_cit BIGINT,
    p_surum TEXT, p_plan TEXT, p_asama TEXT, p_asama_satir BIGINT, p_yayinlanan BIGINT, p_reddedilen BIGINT,
    p_alt TEXT, p_ust TEXT)
RETURNS VOID LANGUAGE plpgsql IMMUTABLE SET search_path = akis, pg_catalog AS $$
BEGIN
    PERFORM sahip_dogrula(p_hedef, p_cit, p_is, p_calistirma, p_deneme, p_surum, p_plan);
    PERFORM kod_dogrula(p_adim, 'step code');
    PERFORM ozet_dogrula(p_yayin_anahtar, 'publish key hash');
    PERFORM ozet_dogrula(p_asama, 'stage hash');
    IF p_asama_satir IS NULL OR p_asama_satir < 0 OR p_yayinlanan IS NULL OR p_yayinlanan < 0 OR p_reddedilen IS NULL OR p_reddedilen < 0 THEN
        RAISE EXCEPTION 'row counts must be nonnegative integers' USING ERRCODE = 'AK005';
    END IF;
    IF (p_alt IS NULL) <> (p_ust IS NULL) OR octet_length(coalesce(p_alt, '')) > 1000 OR octet_length(coalesce(p_ust, '')) > 1000 THEN
        RAISE EXCEPTION 'watermarks must be a bounded pair' USING ERRCODE = 'AK020';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION yayin_imzasi(
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_yayin_anahtar TEXT, p_calistirma UUID, p_deneme INTEGER, p_cit BIGINT,
    p_surum TEXT, p_plan TEXT, p_asama TEXT, p_asama_satir BIGINT, p_yayinlanan BIGINT, p_reddedilen BIGINT,
    p_alt TEXT, p_ust TEXT)
RETURNS TEXT LANGUAGE sql IMMUTABLE SET search_path = akis, pg_catalog AS $$
    SELECT encode(sha256(convert_to(concat_ws('|', 'YAYIN', p_hedef, p_is::text, p_adim, p_yayin_anahtar, p_calistirma::text, p_deneme::text,
        p_cit::text, p_surum, p_plan, p_asama, p_asama_satir::text, p_yayinlanan::text, p_reddedilen::text,
        coalesce(p_alt, '<null>'), coalesce(p_ust, '<null>')), 'UTF8')), 'hex')
$$;

-- Returns (koruma, zaten_kayitli). koruma is NULL when the exact marker already exists; the caller then skips record.
CREATE OR REPLACE FUNCTION yayin_hazirla(
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_yayin_anahtar TEXT, p_calistirma UUID, p_deneme INTEGER, p_cit BIGINT,
    p_surum TEXT, p_plan TEXT, p_asama TEXT, p_asama_satir BIGINT, p_yayinlanan BIGINT, p_reddedilen BIGINT,
    p_alt TEXT, p_ust TEXT)
RETURNS TABLE(koruma TEXT, zaten_kayitli BOOLEAN) LANGUAGE plpgsql SET search_path = akis, pg_catalog AS $$
DECLARE v yayin_defteri%ROWTYPE; v_koruma TEXT;
BEGIN
    IF coalesce(current_setting('akis_defter.yayin_koruma', true), '') <> '' THEN
        RAISE EXCEPTION 'a publish guard is already active in this transaction' USING ERRCODE = 'AK021';
    END IF;
    PERFORM yayin_girdi_dogrula(p_hedef, p_is, p_adim, p_yayin_anahtar, p_calistirma, p_deneme, p_cit, p_surum, p_plan, p_asama,
        p_asama_satir, p_yayinlanan, p_reddedilen, p_alt, p_ust);
    PERFORM cit_kilitle_ve_dogrula(p_hedef, p_cit, p_is, p_calistirma, p_deneme, p_surum, p_plan);
    SELECT * INTO v FROM yayin_defteri
     WHERE hedef_anahtar_ozeti = p_hedef AND is_uuid = p_is AND adim_kodu = p_adim AND yayin_anahtar_ozeti = p_yayin_anahtar;
    IF FOUND THEN
        IF v.surum_ozeti <> p_surum OR v.plan_ozeti <> p_plan OR v.asama_ozeti <> p_asama OR v.asama_satir_sayisi <> p_asama_satir
           OR v.yayinlanan_satir_sayisi <> p_yayinlanan OR v.reddedilen_satir_sayisi <> p_reddedilen
           OR v.alt_isaret IS DISTINCT FROM p_alt OR v.ust_isaret IS DISTINCT FROM p_ust THEN
            RAISE EXCEPTION 'publish evidence conflicts with existing marker' USING ERRCODE = 'AK022';
        END IF;
        RETURN QUERY SELECT NULL::TEXT, TRUE;
        RETURN;
    END IF;
    v_koruma := replace(gen_random_uuid()::text, '-', '');
    PERFORM set_config('akis_defter.yayin_koruma', v_koruma, true);
    PERFORM set_config('akis_defter.yayin_imzasi', yayin_imzasi(p_hedef, p_is, p_adim, p_yayin_anahtar, p_calistirma, p_deneme, p_cit,
        p_surum, p_plan, p_asama, p_asama_satir, p_yayinlanan, p_reddedilen, p_alt, p_ust), true);
    RETURN QUERY SELECT v_koruma, FALSE;
END $$;

CREATE OR REPLACE FUNCTION yayin_kaydet(
    p_koruma TEXT,
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_yayin_anahtar TEXT, p_calistirma UUID, p_deneme INTEGER, p_cit BIGINT,
    p_surum TEXT, p_plan TEXT, p_asama TEXT, p_asama_satir BIGINT, p_yayinlanan BIGINT, p_reddedilen BIGINT,
    p_alt TEXT, p_ust TEXT)
RETURNS VOID LANGUAGE plpgsql SET search_path = akis, pg_catalog AS $$
BEGIN
    PERFORM yayin_girdi_dogrula(p_hedef, p_is, p_adim, p_yayin_anahtar, p_calistirma, p_deneme, p_cit, p_surum, p_plan, p_asama,
        p_asama_satir, p_yayinlanan, p_reddedilen, p_alt, p_ust);
    IF p_koruma IS NULL OR coalesce(current_setting('akis_defter.yayin_koruma', true), '') = '' OR p_koruma <> current_setting('akis_defter.yayin_koruma', true)
       OR current_setting('akis_defter.yayin_imzasi', true) IS DISTINCT FROM yayin_imzasi(p_hedef, p_is, p_adim, p_yayin_anahtar, p_calistirma,
            p_deneme, p_cit, p_surum, p_plan, p_asama, p_asama_satir, p_yayinlanan, p_reddedilen, p_alt, p_ust) THEN
        RAISE EXCEPTION 'publish record is not owned by its preparation transaction' USING ERRCODE = 'AK024';
    END IF;
    PERFORM cit_kilitle_ve_dogrula(p_hedef, p_cit, p_is, p_calistirma, p_deneme, p_surum, p_plan);
    BEGIN
        INSERT INTO yayin_defteri(hedef_anahtar_ozeti, is_uuid, adim_kodu, yayin_anahtar_ozeti, calistirma_uuid, deneme_no, cit_belirteci,
            surum_ozeti, plan_ozeti, asama_ozeti, asama_satir_sayisi, yayinlanan_satir_sayisi, reddedilen_satir_sayisi, alt_isaret, ust_isaret)
        VALUES (p_hedef, p_is, p_adim, p_yayin_anahtar, p_calistirma, p_deneme, p_cit, p_surum, p_plan, p_asama, p_asama_satir,
            p_yayinlanan, p_reddedilen, p_alt, p_ust);
    EXCEPTION WHEN unique_violation THEN
        RAISE EXCEPTION 'publish marker uniqueness changed after preparation' USING ERRCODE = 'AK025';
    END;
    PERFORM set_config('akis_defter.yayin_koruma', '', true);
    PERFORM set_config('akis_defter.yayin_imzasi', '', true);
END $$;

CREATE OR REPLACE FUNCTION yayin_dogrula(
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_yayin_anahtar TEXT, p_surum TEXT, p_plan TEXT, p_asama TEXT,
    p_asama_satir BIGINT, p_yayinlanan BIGINT, p_reddedilen BIGINT, p_alt TEXT, p_ust TEXT)
RETURNS TABLE(eslesti BOOLEAN, calistirma_uuid UUID, deneme_no INTEGER, cit_belirteci BIGINT, kanit_zamani TIMESTAMPTZ)
LANGUAGE plpgsql STABLE SET search_path = akis, pg_catalog AS $$
DECLARE v yayin_defteri%ROWTYPE;
BEGIN
    PERFORM ozet_dogrula(p_hedef, 'target key hash');
    PERFORM kod_dogrula(p_adim, 'step code');
    PERFORM ozet_dogrula(p_yayin_anahtar, 'publish key hash');
    PERFORM ozet_dogrula(p_surum, 'release hash');
    PERFORM ozet_dogrula(p_plan, 'plan hash');
    PERFORM ozet_dogrula(p_asama, 'stage hash');
    IF (p_alt IS NULL) <> (p_ust IS NULL) THEN RAISE EXCEPTION 'watermarks must be a bounded pair' USING ERRCODE = 'AK020'; END IF;
    SELECT * INTO v FROM yayin_defteri d
     WHERE d.hedef_anahtar_ozeti = p_hedef AND d.is_uuid = p_is AND d.adim_kodu = p_adim AND d.yayin_anahtar_ozeti = p_yayin_anahtar;
    IF NOT FOUND THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, NULL::INTEGER, NULL::BIGINT, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    IF v.surum_ozeti <> p_surum OR v.plan_ozeti <> p_plan OR v.asama_ozeti <> p_asama OR v.asama_satir_sayisi <> p_asama_satir
       OR v.yayinlanan_satir_sayisi <> p_yayinlanan OR v.reddedilen_satir_sayisi <> p_reddedilen
       OR v.alt_isaret IS DISTINCT FROM p_alt OR v.ust_isaret IS DISTINCT FROM p_ust THEN
        RAISE EXCEPTION 'publish evidence conflicts with existing marker' USING ERRCODE = 'AK022';
    END IF;
    RETURN QUERY SELECT TRUE, v.calistirma_uuid, v.deneme_no, v.cit_belirteci, v.kanit_zamani;
END $$;

-- ---------------------------------------------------------------------------------------------------------------
-- Batch evidence
-- ---------------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION parti_girdi_dogrula(
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_bolum TEXT, p_parti_anahtar TEXT, p_parti_no BIGINT, p_calistirma UUID, p_deneme INTEGER,
    p_cit BIGINT, p_surum TEXT, p_plan TEXT, p_yuk TEXT, p_satir BIGINT, p_bayt BIGINT)
RETURNS VOID LANGUAGE plpgsql IMMUTABLE SET search_path = akis, pg_catalog AS $$
BEGIN
    PERFORM sahip_dogrula(p_hedef, p_cit, p_is, p_calistirma, p_deneme, p_surum, p_plan);
    PERFORM kod_dogrula(p_adim, 'step code');
    PERFORM kod_dogrula(p_bolum, 'partition code');
    PERFORM ozet_dogrula(p_parti_anahtar, 'batch key hash');
    PERFORM ozet_dogrula(p_yuk, 'payload hash');
    IF p_parti_no IS NULL OR p_parti_no < 0 OR p_satir IS NULL OR p_satir < 0 OR p_bayt IS NULL OR p_bayt < 0 THEN
        RAISE EXCEPTION 'batch counters must be nonnegative integers' USING ERRCODE = 'AK005';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION parti_imzasi(
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_bolum TEXT, p_parti_anahtar TEXT, p_parti_no BIGINT, p_calistirma UUID, p_deneme INTEGER,
    p_cit BIGINT, p_surum TEXT, p_plan TEXT, p_yuk TEXT, p_satir BIGINT, p_bayt BIGINT)
RETURNS TEXT LANGUAGE sql IMMUTABLE SET search_path = akis, pg_catalog AS $$
    SELECT encode(sha256(convert_to(concat_ws('|', 'PARTI', p_hedef, p_is::text, p_adim, p_bolum, p_parti_anahtar, p_parti_no::text,
        p_calistirma::text, p_deneme::text, p_cit::text, p_surum, p_plan, p_yuk, p_satir::text, p_bayt::text), 'UTF8')), 'hex')
$$;

CREATE OR REPLACE FUNCTION parti_hazirla(
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_bolum TEXT, p_parti_anahtar TEXT, p_parti_no BIGINT, p_calistirma UUID, p_deneme INTEGER,
    p_cit BIGINT, p_surum TEXT, p_plan TEXT, p_yuk TEXT, p_satir BIGINT, p_bayt BIGINT)
RETURNS TABLE(koruma TEXT, zaten_kayitli BOOLEAN) LANGUAGE plpgsql SET search_path = akis, pg_catalog AS $$
DECLARE v yukleme_defteri%ROWTYPE; v_koruma TEXT;
BEGIN
    IF coalesce(current_setting('akis_defter.parti_koruma', true), '') <> '' THEN
        RAISE EXCEPTION 'a batch guard is already active in this transaction' USING ERRCODE = 'AK014';
    END IF;
    PERFORM parti_girdi_dogrula(p_hedef, p_is, p_adim, p_bolum, p_parti_anahtar, p_parti_no, p_calistirma, p_deneme, p_cit, p_surum, p_plan, p_yuk, p_satir, p_bayt);
    PERFORM cit_kilitle_ve_dogrula(p_hedef, p_cit, p_is, p_calistirma, p_deneme, p_surum, p_plan);
    SELECT * INTO v FROM yukleme_defteri
     WHERE hedef_anahtar_ozeti = p_hedef AND is_uuid = p_is AND adim_kodu = p_adim AND bolum_kodu = p_bolum AND parti_anahtar_ozeti = p_parti_anahtar;
    IF FOUND THEN
        IF v.parti_no <> p_parti_no OR v.surum_ozeti <> p_surum OR v.plan_ozeti <> p_plan OR v.yuk_ozeti <> p_yuk
           OR v.satir_sayisi <> p_satir OR v.bayt_sayisi <> p_bayt THEN
            RAISE EXCEPTION 'batch evidence conflicts with existing marker' USING ERRCODE = 'AK015';
        END IF;
        RETURN QUERY SELECT NULL::TEXT, TRUE; RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM yukleme_defteri WHERE hedef_anahtar_ozeti = p_hedef AND is_uuid = p_is AND adim_kodu = p_adim
                  AND bolum_kodu = p_bolum AND parti_no = p_parti_no) THEN
        RAISE EXCEPTION 'batch number maps to a different batch key' USING ERRCODE = 'AK016';
    END IF;
    v_koruma := replace(gen_random_uuid()::text, '-', '');
    PERFORM set_config('akis_defter.parti_koruma', v_koruma, true);
    PERFORM set_config('akis_defter.parti_imzasi', parti_imzasi(p_hedef, p_is, p_adim, p_bolum, p_parti_anahtar, p_parti_no, p_calistirma, p_deneme,
        p_cit, p_surum, p_plan, p_yuk, p_satir, p_bayt), true);
    RETURN QUERY SELECT v_koruma, FALSE;
END $$;

CREATE OR REPLACE FUNCTION parti_kaydet(
    p_koruma TEXT,
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_bolum TEXT, p_parti_anahtar TEXT, p_parti_no BIGINT, p_calistirma UUID, p_deneme INTEGER,
    p_cit BIGINT, p_surum TEXT, p_plan TEXT, p_yuk TEXT, p_satir BIGINT, p_bayt BIGINT)
RETURNS VOID LANGUAGE plpgsql SET search_path = akis, pg_catalog AS $$
BEGIN
    PERFORM parti_girdi_dogrula(p_hedef, p_is, p_adim, p_bolum, p_parti_anahtar, p_parti_no, p_calistirma, p_deneme, p_cit, p_surum, p_plan, p_yuk, p_satir, p_bayt);
    IF p_koruma IS NULL OR coalesce(current_setting('akis_defter.parti_koruma', true), '') = '' OR p_koruma <> current_setting('akis_defter.parti_koruma', true)
       OR current_setting('akis_defter.parti_imzasi', true) IS DISTINCT FROM parti_imzasi(p_hedef, p_is, p_adim, p_bolum, p_parti_anahtar, p_parti_no,
            p_calistirma, p_deneme, p_cit, p_surum, p_plan, p_yuk, p_satir, p_bayt) THEN
        RAISE EXCEPTION 'batch record is not owned by its preparation transaction' USING ERRCODE = 'AK018';
    END IF;
    PERFORM cit_kilitle_ve_dogrula(p_hedef, p_cit, p_is, p_calistirma, p_deneme, p_surum, p_plan);
    BEGIN
        INSERT INTO yukleme_defteri(hedef_anahtar_ozeti, is_uuid, adim_kodu, bolum_kodu, parti_anahtar_ozeti, parti_no, calistirma_uuid, deneme_no,
            cit_belirteci, surum_ozeti, plan_ozeti, yuk_ozeti, satir_sayisi, bayt_sayisi)
        VALUES (p_hedef, p_is, p_adim, p_bolum, p_parti_anahtar, p_parti_no, p_calistirma, p_deneme, p_cit, p_surum, p_plan, p_yuk, p_satir, p_bayt);
    EXCEPTION WHEN unique_violation THEN
        RAISE EXCEPTION 'batch marker uniqueness changed after preparation' USING ERRCODE = 'AK019';
    END;
    PERFORM set_config('akis_defter.parti_koruma', '', true);
    PERFORM set_config('akis_defter.parti_imzasi', '', true);
END $$;

CREATE OR REPLACE FUNCTION parti_dogrula(
    p_hedef TEXT, p_is UUID, p_adim TEXT, p_bolum TEXT, p_parti_anahtar TEXT, p_parti_no BIGINT,
    p_surum TEXT, p_plan TEXT, p_yuk TEXT, p_satir BIGINT, p_bayt BIGINT)
RETURNS TABLE(eslesti BOOLEAN, calistirma_uuid UUID, deneme_no INTEGER, cit_belirteci BIGINT, kanit_zamani TIMESTAMPTZ)
LANGUAGE plpgsql STABLE SET search_path = akis, pg_catalog AS $$
DECLARE v yukleme_defteri%ROWTYPE;
BEGIN
    PERFORM ozet_dogrula(p_hedef, 'target key hash');
    PERFORM kod_dogrula(p_adim, 'step code');
    PERFORM kod_dogrula(p_bolum, 'partition code');
    PERFORM ozet_dogrula(p_parti_anahtar, 'batch key hash');
    PERFORM ozet_dogrula(p_surum, 'release hash');
    PERFORM ozet_dogrula(p_plan, 'plan hash');
    PERFORM ozet_dogrula(p_yuk, 'payload hash');
    SELECT * INTO v FROM yukleme_defteri d
     WHERE d.hedef_anahtar_ozeti = p_hedef AND d.is_uuid = p_is AND d.adim_kodu = p_adim AND d.bolum_kodu = p_bolum AND d.parti_anahtar_ozeti = p_parti_anahtar;
    IF NOT FOUND THEN
        RETURN QUERY SELECT FALSE, NULL::UUID, NULL::INTEGER, NULL::BIGINT, NULL::TIMESTAMPTZ; RETURN;
    END IF;
    IF v.parti_no <> p_parti_no OR v.surum_ozeti <> p_surum OR v.plan_ozeti <> p_plan OR v.yuk_ozeti <> p_yuk
       OR v.satir_sayisi <> p_satir OR v.bayt_sayisi <> p_bayt THEN
        RAISE EXCEPTION 'batch evidence conflicts with existing marker' USING ERRCODE = 'AK015';
    END IF;
    RETURN QUERY SELECT TRUE, v.calistirma_uuid, v.deneme_no, v.cit_belirteci, v.kanit_zamani;
END $$;
