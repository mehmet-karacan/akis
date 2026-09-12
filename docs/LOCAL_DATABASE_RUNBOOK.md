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
- İlk yerel yönetici parolası PostgreSQL'e yazılmaz. `harici_kimlik` kaydı,
  HTTP Basic principal adını uygulama kullanıcısına bağlar.

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

`database/akis-baseline/bootstrap-local-admin.sql` dosyasını `.env` içindeki
`AKIS_DEV_USERNAME` ile çalıştırın. Betik idempotenttir; kullanıcı, yerel kimlik
ve etkin `SISTEM_YONETICISI` rolü birer kez oluşur. Parola parametre olarak dahi
verilmez.

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
