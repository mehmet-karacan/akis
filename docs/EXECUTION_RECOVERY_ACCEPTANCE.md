# Execution recovery kabul kaydı

Tarih: 2026-09-16  
Başlangıç/remote commit: `fcf6442e83c0f259931d72733afc4feafa3bb4e2`

## Başlangıç kanıtı

| Komut | Sonuç | Gerçek kapsam |
|---|---|---|
| `scripts/test-quality-gate.ps1` | PASS | Backend 500 unit; frontend 64 dosya/205 test; lint; production build |
| `database/akis-baseline/test-execution.ps1` | BLOCKED_ENV | Docker engine pipe yok; DB kurulmadı/değişmedi |
| Oracle fault injection | BLOCKED_ENV | Disposable Oracle 19c owner sağlanmadı |
| Capacity K1-K4 | BLOCKED_ENV | İzinli Oracle hacim ortamı sağlanmadı |

## 2026-09-16 yerel bitiş kapısı

| Komut | Sonuç | Gerçek kapsam |
|---|---|---|
| `.\\mvnw.cmd -pl backend test -q` | PASS | 101 test sınıfı, 537 test; 0 failure/error/skip |
| `npm test -- --run` | PASS | 65 test dosyası, 207 test |
| `npm run lint` | PASS | ESLint, sıfır warning |
| `npm run build` | PASS | TypeScript + Vite production build |
| `database/akis-baseline/test-execution.ps1` | BLOCKED_ENV | Docker Desktop engine pipe bulunamadı; hiçbir DB oluşturulmadı |

Frontend paketinde jsdom pseudo-element ve bazı React `act(...)` uyarıları vardır;
sonuç PASS olsa da uyarılar kabul kanıtından gizlenmez.

## Uygulanan dilim

- Typed recovery/transaction/transfer sözleşmeleri ve fail-closed planner
- V021-V024 forward-only metadata ve deferred-commit kanıtı
- Immutable logical-job input snapshot claim/finalize portu
- SCN yakalama ve satır+bayt bütçeli ordered range reader
- Recovery preview/idempotent request API ve stale-plan kontrolü
- Exact decimal-string sayaç alanları ve frontend `BigInt` yardımcısı
- Managed `NO_COMMIT` adımın commit öncesi başarılı yazılmasını engelleyen yol
- Bounded chunk için intent → Oracle DML + aynı transaction receipt → metadata
  projection koordinatörü; commit ACK kaybında kör tekrar yok
- PostgreSQL `COMMIT_CONFIRMED` izdüşümünün Oracle hedef makbuzu okunmadan skip
  yetkisi üretmesini engelleyen bağımsız reconciliation verifier
- Oracle DDL için `DBMS_LOCK` session lock; create/grant/drop süresince aynı kilit
  ve object-id/structure doğrulaması
- Sayfalı chunk kanıt API'si, exact string sayaçlar ve operasyon ekranı
- Feature flag açık olsa bile kurulmamış worker handler'ının capability olarak
  ilan edilmesini engelleyen iki aşamalı readiness kapısı

## Ayrı kabul durumları

- **IMPLEMENTATION:** PARTIAL — transaction/recovery/streaming/chunk/DDL/API/UI
  çekirdeği kodlandı. Recovery ve transfer worker handler'ı uçtan uca bağlı olmadığı
  için readiness kapısı kapalıdır; açık ayar yetenek ilan etmez ve iş başlatmaz.
- **LOCAL_TESTS:** PASS_UNIT_BUILD — backend 537/537, frontend 207/207, lint ve
  production build geçti. PostgreSQL integration ayrıca `BLOCKED_ENV` durumundadır.
- **ORACLE_ACCEPTANCE:** BLOCKED_ENV — mock, Oracle kabulü sayılmadı.
- **CAPACITY_ACCEPTANCE:** BLOCKED_ENV — 1M/10M/1B/2B/3B koşuları yapılmadı.

Bu belge üretim desteği iddiası değildir. Gerçek ortam kanıtı olmadan worker,
manual request, recovery ve transfer flag'leri varsayılan kapalı kalır.

R01-R50 satır bazlı eşleme [test matrisindedir](EXECUTION_RECOVERY_TEST_MATRIX.md).
