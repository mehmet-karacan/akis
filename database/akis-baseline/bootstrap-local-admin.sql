\set ON_ERROR_STOP on
\o /dev/null
SELECT set_config('akis.bootstrap.local_user', :'local_user', false);
SELECT set_config('akis.bootstrap.display_name', :'display_name', false);
SELECT set_config('akis.bootstrap.email', :'email', false);
\o

SET search_path TO akis, public;

DO $$
BEGIN
 IF current_setting('akis.bootstrap.local_user', true) IS NULL
    OR btrim(current_setting('akis.bootstrap.local_user', true)) = '' THEN
  RAISE EXCEPTION 'Local bootstrap user is required.';
 END IF;
END $$;

WITH mevcut AS (
 SELECT DISTINCT k.id
 FROM kullanici k
 LEFT JOIN harici_kimlik h ON h.kullanici_id = k.id
 WHERE (h.saglayici_turu = 'YEREL'
        AND h.yayinlayici IS NULL
        AND h.harici_kullanici_anahtari = current_setting('akis.bootstrap.local_user'))
    OR (nullif(current_setting('akis.bootstrap.email', true), '') IS NOT NULL
        AND lower(k.eposta) = lower(current_setting('akis.bootstrap.email')))
 LIMIT 1
), yeni AS (
 INSERT INTO kullanici(gorunen_ad, eposta)
 SELECT current_setting('akis.bootstrap.display_name'),
        nullif(current_setting('akis.bootstrap.email', true), '')
 WHERE NOT EXISTS (SELECT 1 FROM mevcut)
 RETURNING id
), secili AS (
 SELECT id FROM mevcut UNION ALL SELECT id FROM yeni
 LIMIT 1
), kimlik AS (
 INSERT INTO harici_kimlik(
   kullanici_id, saglayici_turu, harici_kullanici_anahtari, olusturan_kullanici_id
 )
 SELECT id, 'YEREL', current_setting('akis.bootstrap.local_user'), id FROM secili
 ON CONFLICT DO NOTHING
 RETURNING kullanici_id
)
INSERT INTO kullanici_rol(kullanici_id,rol_id,rol_kapsami,atayan_kullanici_id,olusturan_kullanici_id)
SELECT s.id,r.id,'SISTEM',s.id,s.id FROM secili s
JOIN rol r ON r.kapsam='SISTEM' AND r.kod='SISTEM_YONETICISI'
ON CONFLICT DO NOTHING;
