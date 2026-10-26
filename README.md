# Akış

Oracle'dan Oracle'a güvenilir, izlenebilir ve yeniden çalıştırılabilir veri taşıma
için tasarlanmış veri entegrasyon platformu. Tanımlar, bağlantılar, yayınlar ve
çalıştırma kayıtları PostgreSQL'de tutulur; taşınan iş verisi PostgreSQL üzerinden
geçirilmez, kaynak ve hedef Oracle arasında akar.

**Proje Sorumlusu:** Mehmet Karacan (`mkaracan@innova.com.tr`)

## Arka plan ve amaç

Kurum içinde Oracle tabanlı veri taşıma işleri çoğunlukla elle yazılmış
script'lerle, sürümlenmemiş tanımlarla ve izlenemeyen çalıştırmalarla yürütülüyor.
Akış bu işleri tek bir platformda toplar:

- Taşıma tanımları (Paket, Prosedür, Mapping, Değişken, Sequence, Scenario,
  Load Plan) **değişmez sürümler** olarak saklanır ve yayınlanır.
- Kaynak ve hedef bağlantıları sürümlenir; her bağlantı testi kanıt olarak
  saklanır ve hedef kimliği (`DB_UNIQUE_NAME + CON_NAME`) sabitlenir.
- Çalıştırmalar PostgreSQL lease/fencing ve hedef Oracle üzerindeki ledger ile
  korunur; çökme, tekrar deneme ve yarım kalmış yayın durumlarında veri
  bütünlüğü fail-closed davranışla korunur.
- Tüm tasarım, JSON bundle olarak dışa/içe aktarılabilir.

## Mimari

![Mimari](docs/architecture.png)

Diyagramın düzenlenebilir kaynağı: [`docs/architecture.excalidraw`](docs/architecture.excalidraw)
(https://excalidraw.com adresine sürükleyip bırakarak açabilirsiniz).

| Katman | Teknoloji | Görev |
|---|---|---|
| Arayüz | React 19, TypeScript 6, Vite 8 | Tanım tasarımı, bağlantı yönetimi, run izleme. Varsayılan dil İngilizce; Türkçe, açık/koyu/sistem teması seçilebilir |
| Backend | Java 25, Spring Boot 4.1 | Domain/API, yayın planlama, run kontrol düzlemi, worker orkestrasyonu |
| Metadata deposu | PostgreSQL 18 | Tanımlar, sürümler, bağlantılar, run durumu, lease/heartbeat |
| Kaynak / hedef | Oracle 19c (JDBC 23.26) | Bağlantı, discovery, veri taşıma ve hedef-yerel ledger |

Backend paketleri: `catalog`, `discovery`, `execution`, `export`, `identity`,
`knowledge`, `metadata`, `oracle`, `postgres`, `projectbundle`, `publication`,
`scenario`, `security`, `tanim`, `topology`, `web`.

## Yetenekler

- **Tanım yönetimi:** Proje, klasör ve JSON tanım taslakları; değişmez tanım
  sürümleri; fiziksel/mantıksal şema ve ortam eşlemeleri.
- **Bağlantı yaşam döngüsü:** `DRAFT → TESTED → ACTIVE`. Discovery yalnız
  `ACTIVE` sürümlerde çalışır ve aynı Oracle oturumunda hedef kimliğini yeniden
  doğrular.
- **Güvenilir şema görüntüsü:** Oracle dictionary metadata'sı sunucuda
  kanonikleştirilir, değişmez fingerprint üretilir ve bağlantı testine bağlı
  provenance kanıtıyla saklanır.
- **Yayın:** Yalnız güvenli pilot şekline uyan Mapping V2 yayınları
  `ORACLE_TABLE_COPY_V1` yürütme yeteneği ve onaylı `runtimePlanHash` alır; diğer
  geçerli sürümler `DEFINITION_ONLY` olarak yayınlanır.
- **Run kontrol düzlemi:** Kalıcı ve idempotent run isteği, izleme, kuyrukta iptal.
- **Koordinasyon ve dayanıklılık:** PostgreSQL lease/fencing, heartbeat
  supervision, hedef-yerel Oracle ledger, exact T/T+1 reconciliation, atomic
  publish facade, crash sonrası güvenli lease reaping.
- **Kaynak ön doğrulaması:** Yayınlanmış Oracle Procedure için hedefe bağlanmadan,
  salt-okunur ve sınırlı (en fazla 1000 satır) kaynak doğrulaması.
- **Taşınabilir tasarım paketi:** Checksum korumalı tek JSON dosyasıyla
  export/import; import önce bağımsız validation ve zorunlu dry-run çalıştırır.
  Secret, kullanıcı/yetki, test/çalıştırma kanıtları, Scenario, publication ve
  discovery snapshot'ları bilinçli olarak taşınmaz.

## Durum

Manuel run, worker ve Prosedür runtime bayrakları varsayılan olarak **kapalıdır**.
Bu bayraklar yalnız ayrı, kontrollü bir kabul kararıyla açılır; CI/CD üzerinden
açılamaz. Worker bayrağı, hedef-yerel ledger ve crash/reconciliation kapıları
olmadan fail-closed davranır. Güncel uygulama durumu ve kalan işler:
[`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md).

## Yerel kurulum

Gereksinimler:

- Java 25 (LTS)
- Node.js (frontend için) ve npm
- Docker Desktop veya Docker Engine + Compose

Yeni bir checkout için:

```powershell
Copy-Item .env.example .env
# .env içindeki change-me değerlerini güçlü, yalnız yerelde kullanılan değerlerle değiştirin.
.\scripts\dev-up.ps1
```

PostgreSQL durumu:

```powershell
docker compose --env-file .env ps
```

Backend çalıştırma:

```powershell
.\scripts\run-backend.ps1
```

UI çalıştırma (ayrı terminalde):

```powershell
Set-Location frontend
npm ci
npm run dev
```

Sağlık uç noktası: http://localhost:8080/actuator/health

Container'ları durdurmak için `.\scripts\dev-down.ps1` (PostgreSQL volume'unu silmez).

## Test

```powershell
.\mvnw.cmd test
.\backend\test-api.ps1
.\scripts\run-e2e.ps1
```

Kalite kapısı: `.\scripts\test-quality-gate.ps1`. Test stratejisi ve kabul
matrisleri: [`docs/testing`](docs/testing), [`docs/acceptance`](docs/acceptance).

## Oracle bağlantı probe'u ve ledger kurulumu

Gerçek bağlantı değerleri yalnız `.env` dosyasına girilir.

```powershell
.\scripts\run-oracle-probe.ps1
```

Script, EZConnect URL'indeki host ve portu önce TCP düzeyinde sınar; host
erişilemiyorsa kurumsal VPN gerektiğini bildirir. Probe yalnız bağlantı, sürüm ve
mevcut kullanıcının nesne sayısı sorgularını çalıştırır; DDL veya DML yapmaz.

Hedef-yerel ledger kurulumu runtime migration veya CI/CD işi değildir; kontrollü
DBA kurulumu ve bağımsız doğrulama için:

```powershell
.\scripts\invoke-oracle-ledger.ps1 -Mode Install
.\scripts\invoke-oracle-ledger.ps1 -Mode Validate
```

Transaction protokolü, nesneler ve negatif test kapıları:
[`database/oracle/README.md`](database/oracle/README.md).

## Dokümantasyon

| Konu | Belge |
|---|---|
| Nesne kataloğu (Paket, Prosedür, Değişken, Sequence, Scenario, Load Plan) | [`NESNE_KATALOGU.md`](docs/architecture/NESNE_KATALOGU.md) |
| Manuel run kontrol düzlemi | [`MANUAL_RUN_CONTROL_PLANE.md`](docs/architecture/MANUAL_RUN_CONTROL_PLANE.md) |
| Hedef ledger ve fencing sözleşmesi | [`TARGET_LEDGER_AND_FENCING_CONTRACT.md`](docs/architecture/TARGET_LEDGER_AND_FENCING_CONTRACT.md) |
| Güvenli yeniden çalıştırma (execution recovery) | [`EXECUTION_RECOVERY.md`](docs/architecture/EXECUTION_RECOVERY.md), [`kabul`](docs/EXECUTION_RECOVERY_ACCEPTANCE.md), [`runbook`](docs/EXECUTION_RECOVERY_RUNBOOK.md) |
| Procedure kaynak ön doğrulaması | [`PROCEDURE_SOURCE_PREFLIGHT.md`](docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md) |
| Proje bundle formatı | [`PROJECT_BUNDLE_FORMAT.md`](docs/architecture/PROJECT_BUNDLE_FORMAT.md) |
| Oracle çalışma nesneleri adlandırma (`AKIS_C$`, `AKIS_I$`, `AKIS_E$`) | [`ORACLE_WORK_OBJECT_NAMING.md`](docs/architecture/ORACLE_WORK_OBJECT_NAMING.md) |
| Bağlantı çalışma alanı UX | [`CONNECTION_WORKSPACE_UX.md`](docs/architecture/CONNECTION_WORKSPACE_UX.md) |
| Yerel veritabanı runbook | [`LOCAL_DATABASE_RUNBOOK.md`](docs/LOCAL_DATABASE_RUNBOOK.md) |
| UI tasarım sistemi | [`UI_DESIGN_SYSTEM.md`](docs/UI_DESIGN_SYSTEM.md) |

## Güvenlik notları

- `.env` ve `.env.*` dosyaları Git'e alınmaz; yalnız `.env.example` takip edilir.
- Gerçek secret değerleri README, Compose veya Spring yapılandırmasına yazılmaz.
- Yerel giriş parolası browser storage alanına yazılmaz.
- PostgreSQL portu yalnız `127.0.0.1` adresine açılır; container yalnız yerel
  geliştirme içindir.
- Bağlantı secret'ları export/import paketlerine dahil edilmez.

## CI/CD

GitHub Actions ve diğer CI/CD workflow'ları bu aşamada bilinçli olarak kapalıdır;
`.github/workflows/` dizini ignore edilir.
