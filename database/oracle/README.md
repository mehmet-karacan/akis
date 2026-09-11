# Oracle 19c target-local ledger ve fencing

Bu dizin, AKIS worker'ın hedef Oracle transaction sonucunu yerel olarak
kanıtlaması ve eski worker token'larını reddetmesi için gerekli kontrol
nesnelerini içerir. Nesneler bağlanılan mevcut target owner altında oluşturulur;
ayrı kontrol şeması zorunlu değildir. Bütün nesneler `ETL_` öneklidir.

Bu kurulum herhangi bir host, kullanıcı veya parola içermez; bağlantı bilgisi
komut satırına ya da repository dosyasına yazılmamalıdır. Scriptler hiçbir
business/staging tablosuna, özellikle `STG_HAKEDIS_TIPI` tablosuna dokunmaz.

## Nesneler

- `ETL_YUKLEME_KILIDI`: global target hash başına dayanıklı ve monotonik fence
  token ile tam job/run/attempt/release/plan sahibini tutar.
- `ETL_YUKLEME_DEFTERI`: deterministik batch doğal anahtarı ve batch DML ile aynı
  transaction'daki immutable kanıttır.
- `ETL_YAYIN_DEFTERI`: publish doğal anahtarı, stage/hash/sayım/watermark kanıtıdır.
- `ETL_KANIT_PKG`: `AUTHID DEFINER` fence ve prepare/record protokolüdür.
- `ETL_KURULUM_SURUMU`: kurulan sözleşmenin fail-closed sürüm işaretidir.

Tablo, kolon, constraint, trigger ve package adları Oracle'ın eski 30-byte
identifier sınırını aşmaz. UUID'ler canonical lowercase 36 karakter, hash'ler
lowercase 64 hex karakterdir. Token `PostgreSQL BIGINT` üst sınırını aşamaz.

## Kurulum

Ön koşullar hedef owner için en az `CREATE TABLE`, `CREATE PROCEDURE` ve
`CREATE TRIGGER`; package için `DBMS_TRANSACTION` erişimidir. DBA bakım
penceresinde bu dizine geçip SQL*Plus ile çalıştırır:

```sql
@install.sql
```

Repository kökünden aynı işlem, gerçek değerleri yalnız `.env` dosyasından
okuyan SQLcl runner ile de yapılabilir. Parola komut satırına yazılmaz:

```powershell
.\scripts\invoke-oracle-ledger.ps1 -Mode Install
.\scripts\invoke-oracle-ledger.ps1 -Mode Validate
```

Runner yalnız EZConnect service URL'sini kabul eder, hedef kullanıcı ile owner'ın
aynı olduğunu doğrular ve bağlantı kurulamazsa VPN gerektiğini bildirir.

Oracle DDL çalışmadan önce ve sonra implicit commit oluşturabilir. Bu nedenle
kurulum bir worker data transaction'ının parçası değildir ve worker kapalıyken
ayrı DBA değişiklik sürecinde uygulanır. `install.sql` hata halinde SQL*Plus'ı
başarısız kodla sonlandırır.

Tamamlanmış aynı V1 sözleşmesi tekrar çalıştırılabilir. Sürüm işareti olmadan
mevcut yönetilen nesne, eksik nesne veya farklı contract hash görülürse script
otomatik onarım/upgrade yapmaz. DBA nesneleri ve veriyi inceleyip ayrı, sürümlü
migration hazırlamalıdır. Scriptin ortasında DDL hatası oluşursa implicit commit
nedeniyle bazı nesneler kalabilir; sürüm işareti yazılmadan tekrar çalıştırma
bilinçli olarak durur.

Salt okunur kontrol ayrıca çalıştırılabilir:

```sql
@validate.sql
```

`negative-test.sql` fence, stale token, idempotent replay, çelişen payload,
negatif sayaç ve append-only trigger'ları sınar. Yalnız disposable/test owner'da
DBA onayıyla çalıştırılmalıdır. Test business/staging DML yapmaz ve başarılı
sonunda kendi kontrol verisini temizler.

```powershell
.\scripts\invoke-oracle-ledger.ps1 -Mode NegativeTest
```

## Runtime yetkisi

Ayrı runtime hesabı kullanılabiliyorsa ona tablo DML yetkisi değil yalnız package
çalıştırma yetkisi verilir:

```sql
GRANT EXECUTE ON ETL_KANIT_PKG TO <AKIS_RUNTIME_ROLE>;
```

`READ_FENCE`, `VERIFY_BATCH` ve `VERIFY_PUBLISH` salt okunur package API'leri
unknown-commit/reconcile kanıtını `absent=0`, `match=1`, çelişkide hata olarak
döndürür; tablo `SELECT` yetkisi gerekmez. Operasyonel ad-hoc inceleme gerekiyorsa
ayrı DBA/reconciler rolüne salt okunur tablo yetkisi verilebilir.

Runtime hedef owner'ın kendisiyse Oracle owner ayrıcalıkları geri alınamaz; bu
profil cooperative platform fencing sağlar, ele geçirilmiş owner'a karşı güvenlik
sınırı değildir. Worker'ın bütün yazma yolları yine package protokolünü kullanır.

## Zorunlu transaction sırası

Fence yükseltme ayrı ve business DML içermeyen kısa transaction'dır:

1. Auto-commit kapalı bağlantıda `ETL_KANIT_PKG.ACQUIRE_FENCE` çağrılır.
2. Çağrı başarılıysa caller commit eder. Belirsiz commit yanıtında kör devam
   edilmez; `READ_FENCE` ile kilit sahibi/token/hash kanıtı okunup mutabakat
   yapılır.

Her batch transaction'ında aynı fiziksel connection/session kullanılır:

1. `PREPARE_BATCH` çağrılır. Package fence satırını `SELECT ... FOR UPDATE` ile
   kilitler ve kilidi transaction sonuna kadar tutar.
2. `O_ALREADY_RECORDED=1` ise worker business DML çalıştırmaz; mevcut marker'ı
   doğrular ve transaction'ı kapatır.
3. Yeni batch için dönen `O_TX_GUARD` korunur; business DML çalıştırılır.
4. Aynı session ve aynı transaction'da aynı kanıtlarla `RECORD_BATCH` çağrılır.
5. Business DML ve marker birlikte caller tarafından commit veya rollback edilir.

Publish akışı aynı kuralla `PREPARE_PUBLISH` → business publish DML →
`RECORD_PUBLISH` şeklindedir. Guard package session state'i ile Oracle local
transaction kimliğini birlikte doğrular; arada transaction kapanırsa record
reddedilir. Package içinde commit, rollback, autonomous transaction, dinamik SQL
ve business DML yoktur.

Commit yanıtı kaybolursa worker aynı DML'yi yeniden çalıştırmaz. Batch için
`VERIFY_BATCH`, publish için `VERIFY_PUBLISH` çağrılır; match sonucu PostgreSQL
checkpoint'ini yeniden kurmaya, absent sonucu ise önceki transaction'ın kesin
sonlandığı doğrulandıktan sonra kontrollü yeniden üretime temel olur.

Runtime `TRUNCATE`, `CREATE`, `ALTER`, `DROP` veya rename kullanmaz. Bunlar Oracle
DDL implicit commit sınırını aşar ve bu atomik DML kanıt protokolüne dahil değildir.

## Üretim kapısı

Scriptlerin derlenmesi üretim desteği kanıtı değildir. Gerçek kullanılan Oracle
19c RU üzerinde en az eşzamanlı worker, eski token, DML rollback/commit, commit
yanıtı kaybı, worker kill, marker'dan PostgreSQL checkpoint yeniden kurma ve
PostgreSQL restore sonrası Oracle token üstünlüğü testleri tamamlanmadan worker
flag'i açılmaz.
