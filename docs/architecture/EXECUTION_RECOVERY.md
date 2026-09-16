# Güvenli yeniden çalıştırma ve büyük veri yürütme mimarisi

Durum tarihi: 2026-09-16. Sözleşme: `AKIS-EXECUTION-RECOVERY-01`.

## Güvenlik modeli

Mantıksal iş (`is_talebi`) ile deneme (`calistirma`) ayrıdır. `RESUME` ve
`RETRY_FAILED_UNIT` aynı işi, immutable girdi snapshot'ını ve work-unit anahtarını
koruyan yeni deneme üretir. `RESTART` yeni iş ve workspace üretir; eski koşuyu veya
kanıtını silmez. İstemci checkpoint gönderemez.

Transaction sonucu `NOT_ATTEMPTED`, `EXECUTED_UNCOMMITTED`, `COMMIT_CONFIRMED`,
`ROLLBACK_CONFIRMED` veya `OUTCOME_UNKNOWN` olur. Statement'ın hatasız dönmesi
commit kanıtı değildir. Managed `NO_COMMIT` adımı önce `EXECUTED_UNCOMMITTED`
yazılır; grup commit'i sonrası durable reference ile tamamlanır. Commit çağrısından
sonraki bağlantı hatasını rollback sonucu silemez.

Recovery planner yalnız kalıcı kanıttan karar verir. Unknown outcome varken tek
işlem `RECONCILE` olur. Immutable girdi snapshot'ı olmayan tarihsel koşular
`LEGACY_NO_RECOVERY_EVIDENCE` ile kapanır. Preview `planHash` ve
`expectedStateVersion` değerleri uygulama anında tekrar denetlenir.

## Sabit kaynak ve bounded transfer

Transfer sözleşmesi source Oracle'ın kendi SCN değerini yakalar ve okumayı
`AS OF SCN` ile sabitler. İlk profil tek, non-null, unique sayısal anahtarda
lower-exclusive/upper-inclusive sıralı range okumasıdır. Identifier'lar metadata
bağlıdır; kullanıcı SQL'i chunk SQL'ine çevrilmez.

`OracleRangeTransferReader` yalnız bir commit batch'i tutar. Satır ve canonical
byte bütçesi birlikte uygulanır; tek satır bütçeyi aşarsa açık hata oluşur.
`fetchSize` bellek garantisi değildir. Son okunan değil, receipt ve commit'i teyit
edilmiş batch'in son anahtarı ilerleme sayılır. Payload PostgreSQL'e yazılmaz;
hash/count/boundary/ledger reference tutulur.

## Çalışma nesnesi ve publish

`km_work_object` registry sahipliğin kaynağıdır; `AKIS_` prefix'i tek başına
sahiplik değildir. Workspace, database identity, owner, object id/incarnation,
structure hash ve generation birlikte doğrulanır. Resume sırasında READY/LOADING
nesne drop edilmez. Cleanup doğru incarnation ve açık karar ister.

Oracle DDL implicit commit yaptığı için normal target fence yeterli değildir.
Session-scoped lifecycle lock `DBMS_LOCK.REQUEST(..., release_on_commit => FALSE)`
ile create/grant/drop boyunca tutulur. Disposable Oracle kabulü tamamlanana kadar
managed-DDL/transfer recovery capability'leri kapalıdır. Varsayılan stage publish,
kapasite uygunsa aynı target transaction'da `DELETE + INSERT SELECT stage + ledger`
modelidir; TRUNCATE atomik diye sunulmaz.

## Capability ve kalıcı model

- Tarihsel `ORACLE_PROCEDURE_V1` otomatik recovery profiline yükseltilmez.
- Yeni profiller `ORACLE_PROCEDURE_RECOVERY_V1` ve
  `ORACLE_TRANSFER_RECOVERY_V1` olarak ayrı ilan edilir.
- İlgili environment flag'leri varsayılan `false` değerindedir.
- Flag tek başına yeterli değildir; gerçek recovery/transfer handler'ı kurulu değilse
  readiness false kalır, API/UI capability listesine özellik eklenmez.
- V021 transaction/grup kanıtını, V022 input/recovery/chunk modelini, V023 deferred
  commit ACK yolunu, V024 workspace lifecycle kanıtını ekler.
- Kesin sayaçlar decimal string exact alanlarla taşınır; eski numeric alanlar
  uyumluluk için korunur.

PostgreSQL checkpoint Oracle receipt'inin izdüşümüdür; XA veya sahte ortak
atomiklik iddia edilmez.
