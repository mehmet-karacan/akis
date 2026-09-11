# Akış temiz başlangıç şeması

Bu dizin yerel PostgreSQL kontrol veritabanının temiz başlangıç şemasını içerir.
Nihai veritabanı şeması `akis` adındadır ve ayrı bir sürüm adlandırması kullanılmaz.

İlk migration yalnız kimlik, rol/yetki ve proje üyeliği yapılarını içerir. İş
tabloları boş başlar. Hazır roller ve yetkiler referans verisi olarak eklenir.

## İlk grup

```text
kullanici
harici_kimlik
rol
yetki
rol_yetki
proje
proje_uyeligi
kullanici_rol
```

`rol`, yeniden kullanılabilir sistem/proje rol tanımıdır. `proje_uyeligi`,
kullanıcı ile proje arasındaki ilişkidir. `kullanici_rol`, sistem rolünü doğrudan
kullanıcıya veya proje rolünü proje içindeki kullanıcıya atar. Birleşik foreign
key, proje kapsamlı rol atamasının ilgili proje üyeliğine sahip olmasını zorunlu
kılar.

Mevcut `entegrasyon` şeması, uygulama repository'leri grup grup taşınırken yalnız
geçici geri dönüş kaynağı olarak korunur. Buraya yeni şema özelliği eklenmez. Tüm
gruplar temiz veritabanı kabul testlerini geçince bu dosyalar eski Flyway zincirinin
yerini alır ve eski şema kaldırılır.

Migration içinde başlangıç kullanıcısı veya parola bulunmaz. İlk yönetici ayrı ve
açık bir yerel başlangıç akışıyla oluşturulur.

## Doğrulama

Yerel PostgreSQL container'ı çalışırken şu komut yürütülür:

```powershell
.\database\akis-baseline\test-baseline.ps1
```

Betik benzersiz adlı geçici bir veritabanı oluşturur, temiz migration'ı uygular,
olumlu ve olumsuz bütünlük kontrollerini çalıştırır ve yalnız bu geçici veritabanını
siler. Geliştirme veritabanını değiştirmez ve Oracle sistemlerine bağlanmaz.
