# Akış backend

Backend Java 21 ve Spring Boot 4.1 üzerinde çalışır. Uygulama açılışında
`database/migrations` altındaki Flyway migrasyonlarını classpath üzerinden uygular.
Taşınan iş verisi bu API veya PostgreSQL metadata deposundan geçirilmez.

## Metadata API v1

Temel yüzeyler:

- `GET /api/v1/definition-types`
- `POST|GET /api/v1/projects`
- `GET /api/v1/projects/{projectUuid}`
- `POST|GET /api/v1/projects/{projectUuid}/folders`
- `POST|GET /api/v1/projects/{projectUuid}/definitions`
- `GET /api/v1/projects/{projectUuid}/definitions/{definitionUuid}`
- `GET|PUT /api/v1/projects/{projectUuid}/definitions/{definitionUuid}/draft`
- `POST|GET /api/v1/projects/{projectUuid}/definitions/{definitionUuid}/versions`
- `POST|GET /api/v1/global-definitions`
- `GET /api/v1/global-definitions/{definitionUuid}`
- `GET|PUT /api/v1/global-definitions/{definitionUuid}/draft`
- `POST|GET /api/v1/global-definitions/{definitionUuid}/versions`

Proje kapsamında Mapping, Yeniden Kullanılabilir Mapping, Paket, Prosedür,
Değişken, Sequence, Kullanıcı Fonksiyonu, Knowledge Module ve Load Plan bulunur.
Global kapsam yalnız Yeniden Kullanılabilir Mapping, Değişken, Sequence, Kullanıcı
Fonksiyonu ve Knowledge Module için açıktır.

Taslak ilk kez `expectedVersion: 0` ile oluşturulur. Sonraki yazmalarda mevcut
`version` değeri gönderilir; eski değer `412 STALE_VERSION` üretir. Immutable sürüm
yalnız tür sözleşmesi geçerli bir taslaktan oluşturulur. İçerik nesne anahtarlarına
göre kanonikleştirilir ve SHA-256 özetiyle saklanır. İç veritabanı ID'leri API'ye
çıkarılmaz; dış kimlik her zaman UUID'dir.

Hatalar RFC 9457 `application/problem+json` biçimindedir ve makine tarafından
okunabilir `code` alanı taşır. Şifre veya secret değeri hiçbir metadata isteğinin
parçası değildir.

## Çalıştırma ve test

Repository kökünde:

    .\scripts\dev-up.ps1
    .\mvnw.cmd -pl backend test
    .\backend\test-api.ps1
    .\scripts\run-backend.ps1

`test-api.ps1` geçici ve yalıtılmış bir PostgreSQL veritabanı oluşturur; dokuz proje
türünü, beş global türü, taslak optimistic lock davranışını, immutable sürüm
sözleşmesini ve temel hata yanıtlarını gerçek HTTP üzerinden sınar. Test sonunda
uygulamayı durdurur ve geçici veritabanını siler.

## Kapsam sınırı

Bu teslimat metadata tasarım çekirdeğidir. Topology/connection discovery, OIDC ve
proje yetkilendirmesi, dependency çözümleme, Scenario derleme/yayın ve run/worker
API'leri backend kapısının sonraki dilimleridir. UI ve Oracle DML entegrasyonu bu
kapı tamamlanmadan başlatılmaz.
