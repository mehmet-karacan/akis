DO $$
DECLARE eksik INTEGER;
BEGIN
 SELECT count(*) INTO eksik FROM (VALUES
   ('proje'),('klasor'),('tanim'),('tanim_surumu'),('model'),('veri_nesnesi'),('calistirma'),('calistirma_durumu')) z(tablo)
 WHERE NOT EXISTS (SELECT 1 FROM information_schema.tables t WHERE t.table_schema='akis' AND t.table_name=z.tablo AND t.table_type='BASE TABLE');
 IF eksik<>0 THEN RAISE EXCEPTION '% temel çalışma tablosu eksik',eksik; END IF;
 SELECT count(*) INTO eksik FROM (VALUES
   ('proje'),('tanim'),('tanim_surumu'),('model'),('veri_nesnesi'),('calistirma'),('calistirma_durumu')) t(tablo)
 CROSS JOIN (VALUES('id'),('olusturulma_zamani'),('versiyon_no')) z(kolon)
 WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema='akis' AND c.table_name=t.tablo AND c.column_name=z.kolon);
 IF eksik<>0 THEN RAISE EXCEPTION '% zorunlu audit kolonu eksik',eksik; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='akis' AND p.proname='calistirma_sahiplen') THEN RAISE EXCEPTION 'Lease fonksiyonu eksik'; END IF;
END $$;
