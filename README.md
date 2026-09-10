# Akış

Akış, ilk aşamada Oracle'dan Oracle'a güvenilir veri taşıma hedefiyle geliştirilen
bir veri entegrasyon platformudur. Metadata ve kontrol verisi PostgreSQL'de tutulur;
taşınan iş verisi PostgreSQL üzerinden geçirilmez.

Bu repository şu anda Faz 0 teknik spike ve mimari doğrulama aşamasındadır.
Üretim kullanımı için hazır değildir.

## Teknoloji tabanı

- Java 21
- Spring Boot 4.1.1
- Maven 3.9.16 Wrapper
- PostgreSQL 18.6
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

Backend çalıştırma:

    .\scripts\run-backend.ps1

Sağlık uç noktası: http://localhost:8080/actuator/health

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

## Sonraki teknik kapılar

1. Oracle JDBC tip, batch, LOB ve cancel spike'ı
2. Target ledger, unknown commit ve fencing spike'ı
3. Kanonik IR ve deterministik compiler spike'ı
4. 500 kolonlu grid-first mapping UX spike'ı

Her spike'ın ortamı, ölçümleri, başarısız senaryoları ve karar kaydı docs/spikes
altında tutulacaktır.
