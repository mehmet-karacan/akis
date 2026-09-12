DO $$
DECLARE eksik INTEGER;
BEGIN
 SELECT count(*) INTO eksik FROM information_schema.tables t
 CROSS JOIN (VALUES('id'),('uuid'),('olusturulma_zamani'),('olusturan_kullanici_id'),('guncellenme_zamani'),('guncelleyen_kullanici_id'),('versiyon_no')) z(kolon)
 WHERE t.table_schema='akis' AND t.table_type='BASE TABLE'
 AND NOT EXISTS(SELECT 1 FROM information_schema.columns c WHERE c.table_schema=t.table_schema AND c.table_name=t.table_name AND c.column_name=z.kolon);
 IF eksik<>0 THEN RAISE EXCEPTION '% zorunlu audit kolonu eksik',eksik; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='akis' AND p.proname='calistirma_sahiplen') THEN RAISE EXCEPTION 'Lease fonksiyonu eksik'; END IF;
END $$;
