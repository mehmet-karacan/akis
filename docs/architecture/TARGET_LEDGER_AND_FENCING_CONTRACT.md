# Target ledger ve fencing sözleşmesi

Bu belge PostgreSQL kontrol düzlemi ile Oracle 19c hedef transaction'ı arasındaki
güvenilirlik sınırını tanımlar. İki veritabanı tek transaction değildir ve ürün
uçtan uca `exactly once` garantisi vermez.

## En küçük güvenli kapsam

İlk dilim tek bir Oracle hedef nesnesine yazan, immutable publication ve Scenario
planına bağlı bir `RUN` içindir. PostgreSQL iş talebini, lease'i, olayları ve
checkpoint aynasını; Oracle hedefi ise business DML ile aynı transaction'da
dayanıklı batch/publish kanıtını tutar. Çok hedefli run, incremental watermark,
`TRUNCATE`, otomatik retry ve scheduler bu dilimin dışındadır.

## Kimlikler

- `jobUuid`, `is_talebi.uuid` değeridir ve bütün retry/resume denemelerinde sabittir.
- `runUuid`, tek immutable `calistirma` denemesidir; `attempt` ile birlikte kanıttır,
  idempotency anahtarı değildir.
- `releaseHash`, publication'ın context, Scenario planı ve fiziksel bağlarını
  kapsayan SHA-256 özetidir.
- `planHash`, immutable Scenario planının SHA-256 özetidir.
- `targetKeyHash`, DBA tarafından doğrulanmış DB/PDB, owner ve nesne kimliğinin
  sürümlü kanonik SHA-256 özetidir. Host adı veya bağlantı alias'ı kimlik değildir.
- Batch kimliği `(targetKeyHash, jobUuid, stepCode, partitionCode,
  deterministicBatchKeyHash)` bileşimidir. Yeni attempt bu anahtarı değiştirmez.

## Oracle 19c kontrol şeması gereksinimleri

Nesneler DBA tarafından yönetilen dar bir kontrol şemasında oluşturulur. Runtime
kullanıcısı `UPDATE`/`DELETE` defter yetkisi almaz; mümkünse `AUTHID DEFINER` bir
paketin yalnız `EXECUTE` yetkisini alır. Paket autonomous transaction veya `COMMIT`
çalıştırmaz.

### `ETL_YUKLEME_KILIDI`

Asgari alanlar: `TARGET_KEY_HASH VARCHAR2(64) PRIMARY KEY`, monotonik
`FENCE_TOKEN NUMBER(19,0)`, sahip job/run UUID'leri, attempt, release hash ve
`UPDATED_AT TIMESTAMP(6) WITH TIME ZONE`. Token PostgreSQL `BIGINT` üst sınırını
aşamaz. Daha düşük token ve aynı token ile farklı sahip reddedilir.

Yeni token önce ayrı, business DML içermeyen kısa bir Oracle transaction'ında
kalıcı olarak yükseltilir. Her sonraki write/publish transaction'ı kilit satırını
`SELECT ... FOR UPDATE` ile alır, tam token ve sahibi doğrular ve kilidi commit ya
da rollback sonuna kadar tutar. Böylece rollback, daha önce kalıcılaştırılmış fence
token'ını geriye alamaz.

### `ETL_YUKLEME_DEFTERI`

Doğal PK batch kimliğidir. Ayrıca aynı bölümde `BATCH_NO` tekildir. Alanlar en az
run UUID, attempt, fence token, release/plan/payload SHA-256 özetleri, satır ve byte
sayısı ile `EVIDENCE_AT TIMESTAMP(6) WITH TIME ZONE` içerir. Satır yalnız batch DML
ile aynı target transaction'ında eklenir ve sonra değiştirilemez/silinemez.

Mevcut aynı batch anahtarı bütün hash ve sayılarla eşleşirse DML tekrar edilmez.
Aynı anahtarda farklı release, plan veya payload corruption kabul edilir; “zaten
var” diye atlanmaz. Ledger'daki eski fence token geçerli bir önceki commit'in
kanıtıdır; retry sırasında güncel token ile eşit olması gerekmez.

### `ETL_YAYIN_DEFTERI`

Doğal PK `(targetKeyHash, jobUuid, stepCode, publishKeyHash)` bileşimidir. Run,
attempt, fence, release/plan/stage hash ve sayımları ile varsa kanonik alt/üst
watermark kanıtını taşır. Publish DML ve ledger insert tek transaction'dır.

Oracle DDL implicit commit sınırına sahip olduğundan runtime `TRUNCATE`, tablo
oluşturma veya rename çalıştırmaz. Teknik şema kurulumu ayrı DBA değişiklik
sürecidir.

## Commit sırası

1. PostgreSQL kısa transaction: claim, DB-time lease, generation ve olay.
2. PostgreSQL transaction: immutable publish intent; exact runtime plan, publish
   key, payload, satır/bayt sayısı ve iki lease generation kanıtı; satır verisi içermez.
3. Oracle kısa transaction: daha yüksek target fence token'ını kalıcı yükselt.
4. Oracle data transaction: `PREPARE`, hedefte exclusive lock, aynı connection'da
   identity ile schema/trigger preflight, DML, transaction içi payload doğrulama,
   marker `RECORD` ve tek commit. Exact marker zaten varsa DML yapılmaz.
5. Commit sonucu belirsizse Oracle marker yeni reconciliation connection'ında okunur.
6. PostgreSQL transaction: checkpoint aynası, metrik/olay ve state projection.

Oracle commit'ten önce PostgreSQL'e tamamlandı checkpoint'i yazılmaz. Commit yanıtı
kaybolursa kör retry yapılmaz; run `SONUC_BELIRSIZ` olur.

## Lease ve mutabakat

Varsayılan heartbeat 10 saniye, lease 60 saniyedir. Süreler PostgreSQL
`clock_timestamp()` değerine göre hesaplanır. Worker saati karar vermez. Heartbeat;
run UUID, worker referansı, run generation, target resource ve target generation'ın
tamamı eşleşirse ve lease henüz dolmamışsa run ile target lease'ini aynı
transaction'da uzatır.

Süresi dolan target sahibi yeni run'a verilmez. Reaper eski run'ı
`SONUC_BELIRSIZ`, target kaynağını `ASKIDA` yapar ve olayı aynı PostgreSQL
transaction'ında ekler. Reconciler daha yüksek target token'ını Oracle'da kalıcı
yükseltirken eski transaction kilidinin çözülmesini bekler, sonra ledger'ı okur.
Reconciliation claim aynı PostgreSQL transaction'ında run generation'ını `R+1`,
askıdaki target generation'ını `T+1` yapar. PUBLISHED checkpoint eski marker nesli
`T` ile mutabakat bariyeri `T+1` değerlerini ayrı saklar. Uygulama sırası fail-closed
olarak sabittir: PostgreSQL claim, aktif lease'ten pinned intent yükleme, kısa Oracle
transaction'ında `T+1` fence commit'i, yeni Oracle reconciliation session'ında önce
exact fence okuması sonra eski `T` marker doğrulaması, PostgreSQL heartbeat ve typed
completion. Fence commit'i veya Oracle okuma sonucu belirsizse PostgreSQL completion
yapılmaz:

Worker, Oracle target'ı sahiplenmeden önce `HAZIRLANIYOR` aşamasında kaybolursa
Oracle DML başlamamıştır. Ayrı DB-time reaper bu hedefsiz ve lease'i dolmuş run'ı
neslini değiştirmeden `BASARISIZ` yapar, lease'i temizler ve
`PREPARATION_LEASE_EXPIRED` olayını aynı transaction'da yazar. Bu yol
`SONUC_BELIRSIZ`/reconcile üretmez.

`CALISIYOR` aşamasında hedef sahiplenilmiş fakat immutable publish intent henüz
oluşmamışken lease dolarsa V011 reaper run ve target deadline'larının exact
eşleştiğini doğrular; target'ı `BOS`, run'ı `BASARISIZ` yapar. Intent öncesi iptal
aynı kanıtla `IPTAL/BOS` olur. Intent mevcutsa veya publish başlamışsa target
`ASKIDA`, run `SONUC_BELIRSIZ` kalır. Mutabakat lease'i dolduğunda original
publish `R/T` ve bariyer `R+1/T+1` ile immutable hash/count kanıtları terminal
olayda korunur ve run `MUDAHALE_GEREKLI` olur. Ayrıca iki Oracle session'ın toplam
zaman bütçesi boyunca lease periyodik yenilenmeden otomatik worker aktive edilmez.

- Eşleşen marker varsa checkpoint yeniden kurulur; DML tekrarlanmaz.
- Kilit alındıktan sonra marker yoksa önceki transaction kesin sonuçlanmıştır;
  kaynak yeniden üretilebilirliği doğrulanırsa yeni attempt mümkün olur.
- Marker hash'i farklıysa veya kanıt yetersizse `MUDAHALE_GEREKLI` ve target
  `ASKIDA` kalır.

PostgreSQL restore sonrasında worker/scheduler kapalı başlar. Oracle token daha
yüksekse PostgreSQL nesli düşürülmez; hedef defterleriyle mutabakat yapılıp güvenli
yeni nesil belirlenmeden iş kabul edilmez.

## Zorunlu testler

- Eşzamanlı iki worker için tek claim; kilitli adayda `SKIP LOCKED` ilerlemesi.
- İstemci saatleri ileri/geri olsa da DB-time lease; geç kalan heartbeat reddi.
- Eski run veya target generation ile heartbeat/write reddi.
- Aynı fiziksel hedefin iki alias'ının aynı `targetKeyHash` üretmesi.
- Batch DML rollback olduğunda marker yok; commit olduğunda ikisi de görünür.
- Commit yanıtı kaybı ve hedef commit/PG checkpoint arası kill sonrasında marker'dan
  checkpoint kurulması ve duplicate olmaması.
- Daha yüksek token'ın aktif eski Oracle transaction'ını beklemesi; yükseltme
  commit'inden sonra eski token'ın reddedilmesi.
- Aynı batch anahtarı ve farklı payload/release/plan hash'inin reddedilmesi.
- Publish commit/PG watermark arası kill'de başarısız watermark ilerlemesi olmaması.
- PG restore sonrası Oracle token üstünlüğü ve orphan evidence akışı.

Oracle testleri hedeflenen gerçek 19c RU üzerinde ve rapordaki dokuz crash noktasını
en az yirmişer tekrar kapsayacak şekilde yürütülmeden retry/fencing üretim desteği
olarak işaretlenmez.
