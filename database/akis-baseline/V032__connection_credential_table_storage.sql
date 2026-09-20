-- Allow connection credentials to be stored (encrypted) directly on baglanti_kimligi,
-- instead of requiring an OS environment variable per connection. The 'TABLO' provider
-- reuses gizli_deger_konumu to hold the encrypted {"username":...,"password":...} payload.
ALTER TABLE baglanti_kimligi
    DROP CONSTRAINT ck_baglanti_kimligi_saglayici;

ALTER TABLE baglanti_kimligi
    ADD CONSTRAINT ck_baglanti_kimligi_saglayici CHECK (
        gizli_deger_saglayicisi IN ('ENV', 'VAULT', 'TABLO')
    );
