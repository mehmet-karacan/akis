# Manual run control plane

Faz 2, Oracle üzerinde komut çalıştırmadan kalıcı ve idempotent manuel run talebi
oluşturur. Bu sınır, yürütme motorundan önce kontrol düzlemini test edilebilir hale
getirir.

## API

- `POST /api/v1/projects/{projectUuid}/runs`
  - `Idempotency-Key` başlığı zorunludur.
  - Gövde yalnız `publicationUuid` taşır.
  - Yeni talep `201`, aynı anahtar ve aynı istek `200` döndürür.
  - Aynı anahtar farklı istekle kullanılırsa `409 IDEMPOTENCY_KEY_REUSED` döner.
- `GET /api/v1/projects/{projectUuid}/runs`
- `GET /api/v1/projects/{projectUuid}/runs/{runUuid}`
- `GET /api/v1/projects/{projectUuid}/runs/{runUuid}/events`
- `POST /api/v1/projects/{projectUuid}/runs/{runUuid}/cancel`
  - Yalnız `BEKLIYOR` run iptal edilir.
  - Tekrarlanan iptal yeni olay üretmeden aynı sonucu döndürür.

API iç numeric ID, secret veya fiziksel bağlantı uç noktası döndürmez.

## Yetkiler

- `RUN_READ`: run ve olayları görüntüleme
- `RUN_START`: non-production manuel run oluşturma
- `RUN_CANCEL`: güvenli queued cancel
- `PRODUCTION_RUN`: `URETIM` riskindeki ortam için ek çalıştırma yetkisi

`PRODUCTION_RUN` hiçbir varsayılan role verilmez. `CALISTIRICI` rolü yalnız
non-production başlatma, iptal ve okuma yetkilerini alır.

## Durum ve atomiklik

Yeni talep tek PostgreSQL transaction'ında iş talebi, immutable ilk deneme,
`BEKLIYOR` projeksiyonu, olay 1 ve idempotency sonucunu oluşturur. Queued cancel
aynı transaction içinde projeksiyonu `IPTAL` yapar ve olay 2'yi ekler. Retry veya
resume mevcut denemeyi geri sarmaz; ileride yeni bir immutable deneme yaratacaktır.

Canonical durumlar:

`BEKLIYOR`, `HAZIRLANIYOR`, `CALISIYOR`, `YAYINLANIYOR`, `IPTAL_ISTENDI`,
`SONUC_BELIRSIZ`, `MUTABAKAT`, `YENIDEN_DENENEBILIR`, `MUDAHALE_GEREKLI`,
`BASARILI`, `BASARISIZ`, `IPTAL`.

## Kapalı yürütme sınırı

- `AKIS_EXECUTION_ACCEPT_MANUAL_REQUESTS=false` varsayılandır; yazma uç noktaları
  `503 EXECUTION_REQUESTS_DISABLED` döndürür.
- `AKIS_EXECUTION_WORKER_ENABLED=false` zorunlu güvenli varsayılandır.
- Worker bayrağı açılırsa target-local ledger ve fencing olmadığı için uygulama
  fail-closed başlatılmaz.
- Oracle DML, dequeue/claim, retry/resume ve scheduler bu fazda yoktur.
