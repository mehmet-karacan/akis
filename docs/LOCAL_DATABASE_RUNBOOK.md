# Yerel metadata veritabanı işletim notu

Akış'ın yerel PostgreSQL veritabanı Docker içindeki `akis_metadata`, uygulama
şeması ise yalnız `akis` adını kullanır. Oracle kaynak ve hedef sistemleri bu
veritabanı işlemlerinin kapsamında değildir.

## Güvenlik varsayılanları

- `.env` Git dışında kalır ve `.env.example` yalnız örnek değer taşır.
- `AKIS_EXECUTION_ACCEPT_MANUAL_REQUESTS=false`
- `AKIS_EXECUTION_WORKER_ENABLED=false`
- `AKIS_EXECUTION_PROCEDURE_RUNTIME_ENABLED=false`
- Flyway yalnız `classpath:db/akis` temiz baseline zincirini çalıştırır;
  `flyway_schema_history` tablosu da `akis` şemasındadır.
- Kimlik doğrulama uygulama kullanıcısı, Argon2id parola özeti ve sunucu taraflı
  oturumla yapılır. Düz parola ve `harici_kimlik` kaydı tutulmaz.

## Başlatma

```powershell
docker compose up -d metadata-db
```

Backend ve frontend geliştirme süreçleri Git dışındaki `.env` değerlerini
devralarak başlatılır. Sağlık kontrolü:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

## İlk yönetici

Etkileşimli terminalde `scripts/bootstrap-admin.ps1` komutunu çalıştırın. Komut
kullanıcı bilgilerini ve parolayı terminalden alır; parola süreç argümanına,
ortam değişkenine veya Git'e yazılmaz. Etkin bir sistem yöneticisi varsa işlem
güvenli biçimde reddedilir.

## Yedekleme

Container içinde custom-format `pg_dump` oluşturun, `pg_restore --list` ile
kataloğunu doğrulayın ve ardından `.data/backups` altına kopyalayın. `.data`
Git dışındadır. Dosya adı `akis-clean-YYYYMMDD-HHMMSS.dump` biçimindedir.

## Geri yükleme provası

Yedeği hiçbir zaman çalışan `akis_metadata` üzerine doğrudan açmayın. Benzersiz
adlı geçici bir veritabanı oluşturun, `pg_restore --exit-on-error` ile yükleyin,
şema/tablo sayılarını kontrol edin ve yalnız bu geçici veritabanını silin.
Kesinti senaryosunda önce uygulamayı durdurun, hedef veritabanının yeni bir
yedeğini alın ve geri dönüş kararını kayıt altına alın.

## Temiz başlangıç kabulü

Kalıcı yerel veritabanında beklenenler:

- `akis` altında 50 uygulama tablosu
- `akis` altında Flyway geçmiş tablosu
- başarılı 12 Flyway migration
- `entegrasyon` şemasının bulunmaması
- yalnız referans rol/yetkileri ve bootstrap yerel yöneticisi
- proje ve iş tanımlarının boş olması

On bir ayrı geçici-veritabanı kabul betiği `database/akis-baseline/test-*.ps1`
altındadır. İmzalı PowerShell politikası betik çağrısını engellerse içerik,
mutlak baseline dizini atanarak geçici bir scriptblock içinde yürütülür.
