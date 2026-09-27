SET search_path TO akis, public;
DO $$ DECLARE actual BIGINT; required_table TEXT; BEGIN
 SELECT COUNT(*) INTO actual FROM information_schema.tables WHERE table_schema='akis' AND table_type='BASE TABLE';
 IF actual < 65 THEN RAISE EXCEPTION 'Aktif baseline en az 65 tablo bekliyordu; bulunan %.',actual; END IF;
 FOREACH required_table IN ARRAY ARRAY['proje','klasor','tanim','tanim_surumu','model','veri_nesnesi','senaryo','yayin'] LOOP
  IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='akis' AND table_name=required_table AND table_type='BASE TABLE') THEN
   RAISE EXCEPTION 'Yayın doğrulaması için temel tablo eksik: %', required_table;
  END IF;
 END LOOP;
END $$;
SELECT 'AKIS_RELEASES_OK' AS verification_result;
