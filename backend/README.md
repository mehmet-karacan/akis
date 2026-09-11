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
`JdbcRunLeaseStore` ile claim, target generation, DB-time heartbeat deadline,
publish-intent/checkpoint tamamlaması ve reconciliation durumlarını sınar. Aynı
koşuda runtime plana ait immutable source/target snapshot gövdelerinin exact yayın
bağlarından, runtime Oracle bağlantı metadata'sının exact connection sürümünden ve
append-only publish intent'in exact run kaydından yüklendiğini de doğrular; worker
poller veya Oracle bağlantısı başlatmaz. V010 run/target claim, preflight ve terminal
geçiş ACK kaybı tekrarlarını; target başka run tarafından yeniden sahiplenildikten
sonra eski başarı ACK'sinin immutable kanıttan doğrulanmasını da gerçek PostgreSQL
üzerinde sınar. V011 testleri intent öncesi target lease kaybını güvenli
`BASARISIZ/BOS`, intent sonrası kaybı `SONUC_BELIRSIZ/ASKIDA` olarak ayırır;
reconciliation expiry olayında original `R/T` ile bariyer `R+1/T+1` kanıtını korur.

`run-oracle-ledger-it.ps1`, gerçek değerleri yalnız Git dışındaki `.env`
dosyasından alır. Hedef Oracle 19c üzerinde production JDBC ledger adaptörüyle
fence/read, batch ve publish prepare-record-verify, rollback sonrası marker yokluğu
ve stale guard/token reddini sınar. Ayrıca gerçek DB unique name/container ve pilot
hedef tablo metadata'sından kanonik target identity üretir. Test business/staging
tablosuna DML yapmaz; kalıcı marker bırakmaz ve kendine ait fence satırını temizler.

## Kapsam sınırı

Faz 3C backend'i manuel run API'sini, PostgreSQL V005 üzerindeki DB-time claim,
heartbeat ve global hedef generation portlarını, Oracle target-local ledger JDBC
adaptörünü, kanonik Oracle target identity V1'i ve typed pilot runtime plan V1'i
içerir. Mapping schema V1 dondurulmuştur; `ATOMIC_DELETE_INSERT` yalnız schema V2
ile kabul edilir. Manifest V2 ve Scenario plan V2 hash zinciri worker'a verilmeden
önce yeniden doğrulanır. Heartbeat reddi worker yetkisini koşulsuz kaybettirir;
run lease, PostgreSQL target fence ve Oracle ledger kanıtları ayrı modellerdir.
Yalnız tam pilot sözleşmesine uyan Mapping V2 yayınları
`ORACLE_TABLE_COPY_V1` capability ve `runtimePlanHash` taşır. Schema V1,
Package, Procedure, Load Plan ve pilot dışı geçerli Mapping V2 tanımları geriye
dönük yayınlanabilir; manifestte `DEFINITION_ONLY` olarak işaretlenir ve pilot
worker tarafından yürütülemez.

Faz 3D güvenlik temeli; V006 kontrollü completion/reconciliation durum API'lerini,
V007/V008 exact append-only publish intent kanıtını, V009 reconciliation fence
bariyerini, V010 ACK-loss dayanımlı worker geçişlerini, V011 güvenli pre-publish
lease reaper'larını, pinned snapshot loader'ını,
salt-okunur canlı Oracle schema preflight'ını, runtime connection/secret
provider'ını, aynı salt-okunur target session'ında kanonik hedef kimlik okumasını,
fence ledger çağrısından önce aynı bağlantıda hedef kimlik doğrulamasını,
`REQUIRES_NEW` lease mutasyonlarını ve tek-kapılı heartbeat supervisor'ını ekler.
Poller/bean olarak kaydedilmeyen `PilotWorkerOrchestrator`, bir claim'i pinned
kanıtlardan typed terminal sonuca kadar yürütür; ACK kaybında exact işlemi yalnız
bir kez ve lease gate'in içinde tekrarlar. Pilot payload
codec'i yalnız gerçek pilotta gereken `NUMBER`, `VARCHAR2` ve
timezone'sız `TIMESTAMP(6)` tiplerini kabul eder; hücre ve batch limitleri uygular,
satır sırasından bağımsız canonical hash üretir. Target writer exclusive table lock,
aynı bağlantıda target identity ve transaction içi target count/hash doğrulaması
olmadan DELETE/INSERT yapmaz ve uygulama bean'i olarak kaydedilmez.

Bu katman worker poller başlatmaz ve Oracle business/staging DML çalıştırmaz.
Target-local Oracle package yalnız caller-owned transaction içinde çağrılır;
adaptör commit veya rollback yapmaz. Atomic publish facade, PostgreSQL'de daha önce
commit edilmiş intent'i tekrar okuyup caller'dan connection/evidence kabul etmeden
tek guarded Oracle session üzerinde
`PREPARE -> LOCK -> IDENTITY -> LOCKED PREFLIGHT -> DML -> VERIFY -> RECORD -> COMMIT`
sırasını zorlar. Exact marker varsa DML'i atlar; commit hatasını, sonradan rollback
yanıt verse bile `OutcomeUnknown` olarak sınıflandırır. Mutabakat servisi PostgreSQL'de
run ve target generation'ını birlikte artıran exact lease'i alır; immutable intent'i
yeniden yükler, Oracle'da `T+1` fence commit'ini kısa transaction'da doğrular ve yalnız
bundan sonra ayrı reconciliation session'ında fence ile eski `T` publish marker'ını okur.
`PUBLISHED`, `NOT_PUBLISHED` ve `CONFLICT` sonuçları heartbeat sonrası typed ve ACK-loss
idempotent PostgreSQL completion'a çevrilir; Oracle sonucu belirsizse completion yapılmaz.
Pilot runtime plan yalnız iki Oracle tablo,
doğrudan kolon eşlemesi, en fazla 1000 kaynak satır ve atomik delete/insert
stratejisini kabul eder. Ana worker poller'ı, lease'e bağlı Oracle operation budget,
retry/resume, canlı crash enjeksiyon matrisi ve scheduler henüz ürün kodu değildir.
Worker flag'i bu kapılar ve crash testleri tamamlanana kadar açık değerde fail-closed
kalır.

Her worker reference aynı anda en fazla bir run taşır. Claim ACK'si kaybolursa aynı
profil/reference ile yapılan tek exact retry yeni run almak yerine aktif, hedefsiz
`HAZIRLANIYOR` claimini döndürür. Kaynak batch'i okunmadan hemen önce pinned source
snapshot canlı Oracle şemasıyla aynı salt-okunur session üzerinde yeniden doğrulanır.
Preflight target claiminden önce güvenli biçimde reddedilirse run yalnız exact lease
token ve null target kanıtıyla terminal duruma alınır. Heartbeat supervisor kısa
PostgreSQL yetki mutasyonlarını periyodik heartbeat ile aynı kritik bölümde yürütür;
başarılı terminal mutasyon heartbeat'i kilit bırakılmadan kapatır. Oracle ve ağ I/O'su
bu kilidin dışında kalır; lease kaybı yeni yetkili adıma geçişi fail-closed durdurur.

Aktivasyon öncesinde JDBC çağrılarının mutlak lease deadline'ına bağlanması, transaction
finalization rezervinin uygulanması ve canlı crash enjeksiyon matrisinin geçmesi
zorunludur. Mevcut profile timeout'ları işlem başına sınır koyar; tek başına uçtan uca
lease bütçesi değildir. Publish intent oluşmadan sona eren run'lar
V011 exact pre-publish reaper yolunu kullanır; mevcut publish reconciliation servisi
kanıt olmayan durumda sonuç uydurmaz ve PostgreSQL completion yapmadan fail-closed kalır.
