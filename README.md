# Akış

Current implementation status and remaining work are tracked in
[`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md).

Akış, ilk aşamada Oracle'dan Oracle'a güvenilir veri taşıma hedefiyle geliştirilen
bir veri entegrasyon platformudur. Metadata ve kontrol verisi PostgreSQL'de tutulur;
taşınan iş verisi PostgreSQL üzerinden geçirilmez.

Bu repository'de Faz 0 teknik spike, Faz 1 metadata veritabanı, backend domain/API,
ilk uçtan uca tanım yönetimi UI kapıları, Faz 2 güvenli manuel run kontrol düzlemi,
Faz 3A PostgreSQL lease/fencing koordinasyon temeli, Oracle 19c target-local
ledger/fencing kurulumu, Faz 3B Oracle ledger JDBC adaptörü, Faz 3C kanonik
Oracle hedef kimliği ile bounded typed pilot runtime planı ve Faz 3D'nin kontrollü
completion/reconciliation, append-only publish-intent, pinned snapshot preflight
ve bounded typed I/O güvenlik temeli tamamlanmıştır. Exact runtime connection/secret
provider, reconciliation sırasında target generation'ı kalıcı artıran V009 fence
bariyeri, V010 ACK-loss dayanımlı worker geçişleri, V011 intent-öncesi güvenli
lease reaping ayrımı, exact lease'li mutabakat
orkestrasyonu, aynı Oracle oturumunda source schema re-attestation ve tek guarded
Oracle transaction sahibi atomic publish facade da hazırdır. Salt-okunur hedef
kimlik doğrulaması, fence oturumunda ledger'dan önce aynı bağlantıda kimlik
yeniden doğrulaması ve bağımsız transaction'lı heartbeat supervision kapısı da
tamamlanmıştır. Poller olmayan, tek run'ı typed sonuçlarla fail-closed yürüten
pilot worker orkestrasyon çekirdeği de hazırdır. Manuel
istek yalnız kalıcı `BEKLIYOR` run üretir. Worker poller ve Oracle business DML
kapalıdır; ürün üretim kullanımı için hazır değildir.

Yayın manifesti yürütme yeteneğini açıkça taşır. Yalnız güvenli pilot şekline uyan
Mapping V2 yayınları `ORACLE_TABLE_COPY_V1` ve onaylı `runtimePlanHash` alır;
diğer geçerli tanım ve mapping sürümleri `DEFINITION_ONLY` olarak yayınlanabilir.

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

Proje taşınabilir tasarım paketi; proje, klasörler, JSON tanım taslakları,
değişmez tanım sürümleri, bağlantı sürümleri, fiziksel/mantıksal şemalar, ortam
eşlemeleri ve katalog tasarımını tek bir checksum korumalı JSON dosyasıyla
export/import eder. Import önce bağımsız validation ve zorunlu dry-run çalıştırır.
V2 secret değerlerini, kullanıcı/yetkiyi, test/çalıştırma kanıtlarını, Scenario,
publication ve keşif snapshot'larını bilinçli olarak taşımaz. İçe alınan bağlantı
sürümleri yeniden test edilmek üzere taslağa döner. Sözleşme:
`docs/architecture/PROJECT_BUNDLE_FORMAT.md`.

Sağlık uç noktası: http://localhost:8080/actuator/health

Yayınlanmış Oracle Procedure için hedefe hiç bağlanmadan salt-okunur kaynak
doğrulaması yapılabilir. Bu akış immutable plan/binding hash'lerini ve trusted
snapshot'ı yeniden doğrular, en fazla 1000 satırı tip/byte sınırları içinde okur
ve API'de yalnız özet/hash döndürür. Sözleşme:
`docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md`.

Manuel run, worker ve Prosedür runtime varsayılan ve mevcut yerel ortamda kapalıdır.
Bu üç bayrak yalnız ayrı bir kontrollü kabul kararıyla etkinleştirilir; CI/CD veya
GitHub workflow tarafından açılamaz. Worker bayrağı target-local Oracle ledger
ve crash/reconciliation kapıları olmadan `true` değerinde fail-closed açılmaz.
PostgreSQL claim/heartbeat/target generation sözleşmeleri hazırdır fakat kendi
başına veri taşımaz. Sözleşmeler: `docs/architecture/MANUAL_RUN_CONTROL_PLANE.md`
ve `docs/architecture/TARGET_LEDGER_AND_FENCING_CONTRACT.md`.

Oracle 19c bağlantı ve discovery probe'u için gerçek değerleri yalnız .env
dosyasına girin ve çalıştırın:

    .\scripts\run-oracle-probe.ps1

Script, EZConnect URL içindeki host ve portu önce TCP düzeyinde sınar. Host
erişilemiyorsa kurumsal VPN'in açılması gerektiğini açıkça bildirir. Probe kaynak
ve hedefte yalnız bağlantı, sürüm ve current-user nesne sayısı sorguları çalıştırır;
DDL veya DML yapmaz.

Uygulama içindeki Oracle bağlantı sürümleri `DRAFT → TESTED → ACTIVE` yaşam
döngüsünden geçer. Her test append-only kanıt olarak saklanır; başarılı testte
`DB_UNIQUE_NAME + CON_NAME` hedef kimliği sabitlenir. Discovery yalnız `ACTIVE`
sürümlerde çalışır ve aynı Oracle oturumunda hedef kimliğini yeniden doğrular.
Güvenilir katalog şema görüntüsü istemci tarafından oluşturulmaz: V2 snapshot
capture akışı Oracle dictionary metadata'sını sunucuda kanonikleştirir, immutable
fingerprint üretir ve bağlantı testine bağlı provenance kanıtıyla birlikte kaydeder.
V1 manuel snapshot endpoint'i yalnız geriye uyumluluk içindir ve bu provenance
kanıtını üretmez; kanıtsız Oracle snapshot'ı yeni bir tanıma bağlanamaz ve runtime
tarafından yüklenmez.

Oracle target-local ledger kurulumu runtime migration veya CI/CD işi değildir.
Kontrollü DBA kurulumu ve bağımsız doğrulama için:

    .\scripts\invoke-oracle-ledger.ps1 -Mode Install
    .\scripts\invoke-oracle-ledger.ps1 -Mode Validate

Transaction protokolü, nesneler ve negatif test kapıları
`database/oracle/README.md` belgesindedir.

Gelecekteki Oracle staging/geçici nesneleri bağlantı ayarı değildir; çalışma
şeması ve yürütme stratejisi bağlamında platform tarafından adlandırılır.
`AKIS_C$`, `AKIS_I$` ve `AKIS_E$` sahiplik sözleşmesi
`docs/architecture/ORACLE_WORK_OBJECT_NAMING.md` belgesindedir.

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
6. PostgreSQL lease/fencing, target-local Oracle ledger ve atomic publish facade
   (tamamlandı); exact T/T+1 reconciliation, V010 worker ACK/lifecycle portları ve
   V011 pre-publish crash reaping, aynı oturumda target identity kapısı, heartbeat
   supervision ve non-polling tek-run orkestrasyonu tamamlandı; lease'e bağlı mutlak
   Oracle operation budget ve canlı crash enjeksiyon matrisi sıradaki kapıdır
7. Retry/resume, scheduler ve production operasyonları

Paket, Prosedür, Değişken, Sequence, Scenario ve Load Plan dahil tam kavram
sözleşmesi `docs/architecture/NESNE_KATALOGU.md` içindedir. Bir kapı tamamlanmadan
sonraki katman ürün koduna eklenmez.
