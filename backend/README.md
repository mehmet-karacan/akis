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
- `POST|GET /api/v1/projects/{projectUuid}/secret-references`
- `POST|GET /api/v1/projects/{projectUuid}/connections`
- `POST|GET /api/v1/projects/{projectUuid}/connections/{connectionUuid}/versions`
- `POST|GET /api/v1/projects/{projectUuid}/physical-schemas`
- `POST|GET /api/v1/projects/{projectUuid}/logical-schemas`
- `POST|GET /api/v1/projects/{projectUuid}/environments`
- `POST|GET /api/v1/projects/{projectUuid}/schema-bindings`
- `POST|GET /api/v1/projects/{projectUuid}/models`
- `POST|GET /api/v1/projects/{projectUuid}/models/{modelUuid}/submodels`
- `POST|GET /api/v1/projects/{projectUuid}/models/{modelUuid}/data-objects`
- `POST|GET /api/v1/identity/users`
- `POST|GET /api/v1/projects/{projectUuid}/memberships`
- `POST|GET /api/v1/projects/{projectUuid}/data-objects/{dataObjectUuid}/schema-snapshots`
- `POST|GET /api/v1/projects/{projectUuid}/definitions/{definitionUuid}/versions/{versionUuid}/data-bindings`
- `POST /api/v1/projects/{projectUuid}/definitions/{definitionUuid}/versions/{versionUuid}/scenarios/compile`
- `GET /api/v1/projects/{projectUuid}/definitions/{definitionUuid}/versions/{versionUuid}/scenarios`
- `POST|GET /api/v1/projects/{projectUuid}/publications`
- `POST /api/v1/projects/{projectUuid}/publications/{publicationUuid}/approvals`
- `POST /api/v1/projects/{projectUuid}/connections/{connectionUuid}/versions/{versionUuid}/test`
- `POST /api/v1/projects/{projectUuid}/connections/{connectionUuid}/versions/{versionUuid}/physical-schemas/{schemaUuid}/discover`

Proje kapsamında Mapping, Yeniden Kullanılabilir Mapping, Paket, Prosedür,
Değişken, Sequence, Kullanıcı Fonksiyonu, Knowledge Module ve Load Plan bulunur.
Global kapsam yalnız Yeniden Kullanılabilir Mapping, Değişken, Sequence, Kullanıcı
Fonksiyonu ve Knowledge Module için açıktır.

Taslak ilk kez `expectedVersion: 0` ile oluşturulur. Sonraki yazmalarda mevcut
`version` değeri gönderilir; eski değer `412 STALE_VERSION` üretir. Immutable sürüm
yalnız tür sözleşmesi geçerli bir taslaktan oluşturulur. İçerik nesne anahtarlarına
göre kanonikleştirilir ve SHA-256 özetiyle saklanır. İç veritabanı ID'leri API'ye
çıkarılmaz; dış kimlik her zaman UUID'dir.

Sürüm sınırında Mapping, Paket, Prosedür ve Load Plan graf/seçim kuralları derin
olarak doğrulanır. Tanım sürümü veri nesnesiyle ve belirli immutable şema
snapshot'ıyla bağlanır. Scenario aynı tanım sürümünden deterministik ve idempotent
derlenir. Yayın; Scenario, ortam, fiziksel şema, bağlantı sürümü ve snapshot'ları
bir `releaseHash` altında sabitler. Üretim riskli ortam yayınları onay bekler.

Hatalar RFC 9457 `application/problem+json` biçimindedir ve makine tarafından
okunabilir `code` alanı taşır. Şifre veya secret değeri hiçbir metadata isteğinin
parçası değildir.

Bağlantı kimliği değişebilir kayıttır; host/port/service/SID/TLS/policy bilgileri
immutable bağlantı sürümü olarak eklenir. Oracle için `serviceName` veya `sid`
alanlarından tam biri, PostgreSQL/MySQL için yalnız `databaseName` zorunludur.
Credential değeri yerine yalnız `ENV`, `VAULT` veya `KUBERNETES` secret referansı
bağlanır. Policy JSON'unda password, token, secret veya credential alanları
reddedilir. Ortam-şema bağı, fiziksel şema ile aynı bağlantıya ait belirli bir
bağlantı sürümünü sabitler.

Model bir mantıksal şemaya bağlıdır; Alt Model hiyerarşisi ve veri nesneleri model
sınırından çıkamaz. Veri nesnesi `TABLO`, `VIEW` veya `SORGU` olabilir. Kontrollü
Sorgu pozitif bir sözleşme sürümü ve yalnız `SELECT`/`WITH` ile başlayan bir SQL
tanımı ister; bu kayıt canlı veritabanında otomatik çalıştırılmaz.

## Güvenlik ve Oracle discovery

Varsayılan güvenlik modu `fail-closed` olup health dışında API çağrılarını reddeder.
Yerel geliştirmede yalnız loopback adresinde HTTP Basic, kurumsal kullanımda issuer
ve audience doğrulamalı OIDC resource server seçilir. Her endpoint merkezi sistem
veya proje RBAC kontrolünden geçer. Varsayılan proje rolleri Proje Yöneticisi,
Geliştirici, Çalıştırıcı ve İzleyici'dir. Mutating çağrılar correlation ID ve
kullanıcı adıyla append-only audit olayı üretir; istek gövdesi veya secret değeri
kaydedilmez.

Oracle 19c bağlantı testi ve metadata discovery salt okunur JDBC bağlantısı açar.
Secret referansı `ENV` sağlayıcısında, değeri `username` ve `password` alanlarını
taşıyan yerel JSON ortam değişkenine işaret eder. Değer metadata veritabanına,
yanıta veya loga yazılmaz. Discovery yalnız tablo/view, kolon, PK/UK/FK metadata'sı
okur; DDL veya DML çalıştırmaz.

## Çalıştırma ve test

Repository kökünde:

    .\scripts\dev-up.ps1
    .\mvnw.cmd -pl backend test
    .\backend\test-api.ps1
    .\backend\test-worker-lease.ps1
    .\scripts\run-oracle-ledger-it.ps1
    .\scripts\run-backend.ps1

`test-api.ps1` geçici ve yalıtılmış bir PostgreSQL veritabanı oluşturur; auth/RBAC,
audit, topology, katalog, immutable snapshot, dokuz proje türü, beş global tür,
optimistic lock, data binding, Scenario derleme ve context-pinned yayın akışlarını
gerçek HTTP üzerinden sınar. Test sonunda uygulamayı durdurur ve geçici
veritabanını siler.

`test-worker-lease.ps1` ikinci bir geçici PostgreSQL veritabanında gerçek
`JdbcRunLeaseStore` ile claim, target generation, DB-time heartbeat deadline
yenilemesi ve stale generation reddini sınar; worker poller veya Oracle bağlantısı
başlatmaz.

`run-oracle-ledger-it.ps1`, gerçek değerleri yalnız Git dışındaki `.env`
dosyasından alır. Hedef Oracle 19c üzerinde production JDBC ledger adaptörüyle
fence/read, batch ve publish prepare-record-verify, rollback sonrası marker yokluğu
ve stale guard/token reddini sınar. Test business/staging tablosuna dokunmaz;
kalıcı marker bırakmaz ve kendine ait fence satırını temizler.

## Kapsam sınırı

Faz 3B backend'i manuel run API'sini, PostgreSQL V005 üzerindeki DB-time claim,
heartbeat ve global hedef generation portlarını ve Oracle target-local ledger
JDBC adaptörünü içerir. Her run publication `releaseHash` ile gerçek Scenario
`planHash` değerlerini ayrı sabitler. Heartbeat reddi worker yetkisini koşulsuz
kaybettirir; run lease, PostgreSQL target fence ve Oracle ledger kanıtları ayrı
modellerdir.

Bu katman worker poller başlatmaz ve Oracle business/staging DML çalıştırmaz.
Target-local Oracle package yalnız caller-owned transaction içinde çağrılır;
adaptör commit veya rollback yapmaz. Runtime plan resolver, target identity
canonicalization, kontrollü full-refresh writer, reconciliation, retry/resume ve
scheduler henüz ürün kodu değildir. Worker flag'i bu kapılar ve crash testleri
tamamlanana kadar açık değerde fail-closed kalır.
