SET search_path TO akis, public;
DO $$ DECLARE actual BIGINT; required_column TEXT; BEGIN
 SELECT COUNT(*) INTO actual FROM information_schema.tables WHERE table_schema='akis' AND table_type='BASE TABLE';
 IF actual<>35 THEN RAISE EXCEPTION 'Altı grup sonunda 35 tablo bekleniyordu; bulunan %.',actual; END IF;
 FOREACH required_column IN ARRAY ARRAY['id','uuid','olusturulma_zamani','olusturan_kullanici_id','guncellenme_zamani','guncelleyen_kullanici_id','versiyon_no'] LOOP
  SELECT COUNT(*) INTO actual FROM information_schema.columns WHERE table_schema='akis' AND column_name=required_column;
  IF actual<>35 THEN RAISE EXCEPTION 'Ortak kolon % yalnız % tabloda.',required_column,actual; END IF;
 END LOOP;
END $$;
SELECT 'AKIS_RELEASES_OK' AS verification_result;
