# Akış

Akış, ilk aşamada Oracle'dan Oracle'a güvenilir veri taşıma hedefiyle geliştirilen
bir veri entegrasyon platformudur. Metadata ve kontrol verisi PostgreSQL'de tutulur;
taşınan iş verisi PostgreSQL üzerinden geçirilmez.

Bu repository'de Faz 0 teknik spike, Faz 1 metadata veritabanı, backend domain/API,
ilk uçtan uca tanım yönetimi UI kapıları ve Faz 2 güvenli manuel run kontrol düzlemi
tamamlanmıştır. Manuel istek yalnız kalıcı `BEKLIYOR` run üretir; Oracle DML/worker
kapısı henüz başlamamıştır ve ürün üretim kullanımı için hazır değildir.

## Teknoloji tabanı

- Java 21
- Spring Boot 4.1.1
- Maven 3.9.16 Wrapper
- PostgreSQL 18.6
- Oracle JDBC 23.26.3.0.0 (Oracle 19c bağlantı/discovery)
- React 19, TypeScript 6 ve Vite 8
- Docker Compose ile yerel geliştirme ortamı

## Yerel kurulum

Gereksinimler:

- Java 21
- Docker Desktop veya Docker Engine + Compose

Repository içinde gerçek yerel değerleri taşıyan bir .env bulunur ve Git tarafından
yok sayılır. Yeni bir checkout için:

    Copy-Item .env.example .env
    # .env içindeki change-me değerlerini güçlü, yalnız yerelde kullanılan değerlerle değiştirin.
    .\scripts\dev-up.ps1

PostgreSQL durumunu kontrol etmek için:

    docker compose --env-file .env ps

Backend derleme ve test:

    .\mvnw.cmd test
    .\backend\test-api.ps1

Backend çalıştırma:

    .\scripts\run-backend.ps1

UI çalıştırma (ayrı terminalde):

    Set-Location frontend
    npm ci
    npm run dev

UI varsayılan olarak İngilizce açılır; Türkçe ile açık, koyu ve sistem temaları
uygulama içinden seçilebilir. Yerel giriş parolası browser storage alanına yazılmaz.

Proje taşınabilir tasarım paketi; proje, klasörler, JSON tanım taslakları ve
değişmez tanım sürümlerini tek bir checksum korumalı JSON dosyasıyla export/import
eder. Import önce bağımsız validation ve zorunlu dry-run çalıştırır. V1 fiziksel
topoloji, secret, kullanıcı/yetki, Scenario, publication ve runtime kayıtlarını
bilinçli olarak taşımaz. Sözleşme:
`docs/architecture/PROJECT_BUNDLE_FORMAT.md`.

Sağlık uç noktası: http://localhost:8080/actuator/health

Manuel run oluşturma varsayılan olarak kapalıdır. Yalnız kontrol düzlemi testi için
`.env` içinde `AKIS_EXECUTION_ACCEPT_MANUAL_REQUESTS=true` yapılabilir. Worker
bayrağı target ledger ve fencing tamamlanana kadar `false` kalmalıdır; uygulama
`true` değerinde fail-closed açılmaz. Sözleşme:
`docs/architecture/MANUAL_RUN_CONTROL_PLANE.md`.

Oracle 19c bağlantı ve discovery probe'u için gerçek değerleri yalnız .env
dosyasına girin ve çalıştırın:

    .\scripts\run-oracle-probe.ps1

Script, EZConnect URL içindeki host ve portu önce TCP düzeyinde sınar. Host
erişilemiyorsa kurumsal VPN'in açılması gerektiğini açıkça bildirir. Probe kaynak
ve hedefte yalnız bağlantı, sürüm ve current-user nesne sayısı sorguları çalıştırır;
DDL veya DML yapmaz.

Container'ları durdurmak için:

    .\scripts\dev-down.ps1

Bu komut PostgreSQL volume'unu silmez.

## Güvenlik sınırı

- .env ve .env.* dosyaları Git'e alınmaz; yalnız .env.example takip edilir.
- Gerçek secret değerleri README, Compose veya Spring yapılandırmasına yazılmaz.
- Konuşma dışa aktarımları codex-session-*.md deseniyle Git dışında tutulur.
- PostgreSQL portu yalnız 127.0.0.1 adresine açılır ve container yalnız yerel
  geliştirme içindir.

## CI/CD

GitHub Actions ve diğer CI/CD workflow'ları bu aşamada bilinçli olarak kapalıdır.
.github/workflows/ dizini ignore edilir ve repository'de workflow bulunmaz.

## Geliştirme kapıları

1. Nesne kataloğunu kesinleştirme
2. PostgreSQL metadata baseline ve migrasyon testleri
3. Backend domain/API
4. UI, uçtan uca tanım yönetimi ve portable JSON proje bundle'ı
5. Kalıcı manuel run isteği, idempotency, izleme ve queued cancel
6. Target ledger, lease/fencing ve Oracle worker
7. Retry/resume, scheduler ve production operasyonları

Paket, Prosedür, Değişken, Sequence, Scenario ve Load Plan dahil tam kavram
sözleşmesi `docs/architecture/NESNE_KATALOGU.md` içindedir. Bir kapı tamamlanmadan
sonraki katman ürün koduna eklenmez.
