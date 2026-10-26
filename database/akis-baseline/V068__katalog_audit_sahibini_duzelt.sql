-- Reverse-engineering ile daha önce oluşturulmuş katalog kayıtlarında
-- audit sahibi boş bırakılmışsa kurumsal demo kullanıcısını tamamla.
-- Denetim/kanıt tabloları bilinçli olarak değiştirilmez; bunlar immutable
-- olay geçmişidir ve legacy principal bilgisini korumalıdır.
SET search_path TO akis, public;

DO $$
DECLARE
    metadata_user_id BIGINT;
BEGIN
    SELECT id INTO metadata_user_id
      FROM akis.kullanici
     WHERE lower(kullanici_kodu) = 'mkaracan'
     ORDER BY id
     LIMIT 1;

    IF metadata_user_id IS NULL THEN
        RAISE EXCEPTION 'mkaracan kullanıcısı bulunamadı';
    END IF;

    UPDATE akis.model
       SET olusturan_kullanici_id = coalesce(olusturan_kullanici_id, metadata_user_id),
           guncelleyen_kullanici_id = CASE
               WHEN guncellenme_zamani IS NOT NULL THEN coalesce(guncelleyen_kullanici_id, metadata_user_id)
               ELSE guncelleyen_kullanici_id
           END;

    UPDATE akis.alt_model
       SET olusturan_kullanici_id = coalesce(olusturan_kullanici_id, metadata_user_id),
           guncelleyen_kullanici_id = CASE
               WHEN guncellenme_zamani IS NOT NULL THEN coalesce(guncelleyen_kullanici_id, metadata_user_id)
               ELSE guncelleyen_kullanici_id
           END;

    UPDATE akis.klasor
       SET olusturan_kullanici_id = coalesce(olusturan_kullanici_id, metadata_user_id),
           guncelleyen_kullanici_id = CASE
               WHEN guncellenme_zamani IS NOT NULL THEN coalesce(guncelleyen_kullanici_id, metadata_user_id)
               ELSE guncelleyen_kullanici_id
           END;
END $$;
