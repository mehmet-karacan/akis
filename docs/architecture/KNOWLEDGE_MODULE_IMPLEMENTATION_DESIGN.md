# AKIŞ — Knowledge Module ve Mapping Uygulama Tasarımı

Tarih: 15 Eylül 2026. Tasarım sürümü: 0.1.
Referans kod: `a8da8000cb7105a1b90911c20ae341733d2f7672`.
Durum: Uygulamaya yön veren önerilen kararlar; uygulanmış özellik veya üretim kabulü değildir.
Bu belgeyi hazırlamak uygulamayı başlatmaz, çalışma motorunu etkinleştirmez ve Oracle'da nesne oluşturmaz.

## 1. Amaç ve ürün sınırı

Kullanıcı mapping ile NE yapılacağını, Knowledge Module (KM) ile NASIL yapılacağını seçer.
Backend, bu seçimlerden SOURCE/STAGING/TARGET konumları açık, sürümlü bir fiziksel plan üretir.
Worker yalnız yayımlanmış ve yetkili planı yürütür. KM tek başına çalıştırılan serbest SQL paketi değildir.

| Kavram | AKIŞ'taki sorumluluk |
|---|---|
| Mapping / Arayüz | Kaynak, hedef, kolon eşlemesi, filtre ve desteklenen dönüşümler |
| LKM / Yükleme Modülü | Kaynak verisini çalışma alanına taşıma yöntemi |
| IKM / Entegrasyon Modülü | Hazır veri kümesini nihai hedefe uygulama yöntemi |
| CKM / Veri Kontrol Modülü | Açık kurallara göre durdurma veya reddedilen kayıtları ayırma |
| Prosedür | Kullanıcının açıkça tanımladığı sıralı komutlar; mevcut sözleşmesi korunur |
| Paket / Yük Planı | Nesneler arası orkestrasyon; bu tasarımın ilk uygulama kapsamı değildir |

İlk sürüm: tek Oracle kaynak tablosu, tek Oracle hedef tablosu, hedefle aynı DB/PDB'de yönetilen çalışma şeması.
İlk IKM: `ATOMIC_FULL_REFRESH`, yani hedefin TAMAMINI tek transaction'da DELETE + INSERT SELECT ile yenileme.
Bu, büyük tablolar için evrensel performans önerisi değildir; yük testi ve undo/redo kapasitesi kabul koşuludur.

İlk sürümde yok: MERGE, APPEND, bölüm/tarih aralığı yenileme, çoklu kaynak/JOIN, CDC,
üçüncü staging sunucusu, kaynakta staging, keyfi kullanıcı KM kodu, satırdan devam ve otomatik iş tekrarı.
Desteklenmeyen seçenek kaydedilebilir bir gelecek vaadi olarak etkin gösterilmez.

## 2. Mevcut kodda doğrulanan başlangıç sorunları

- `frontend/src/features/definitions/DefinitionsWorkspace.tsx`: yeni kayıtta yalnız PROCEDURE şema 2; MAPPING şema 1.
- `execution/PilotRuntimePlanResolver.java`: mapping şema 2 bekliyor; dataset alanları `id/role/ui/layout` ile sınırlı.
- `frontend/src/features/definitions/MappingGrid.tsx`: `name/dataObjectUuid/schemaSnapshotUuid` üretiyor.
- `execution/PilotRuntimePlan.java`: pilotun toplam kaynak satırı sınırı 1.000.
- `execution/ProcedureVariableContext.java`: süreç içi değer önbelleği; kalıcı yeniden başlatma snapshot'ı değil.
- `execution/JdbcProcedureVariableRuntime.java`: görünür geçmişin NONE/LATEST/ALL saklama davranışı var.

Backend yolları `backend/src/main/java/tr/com/innova/akis/` köküne göredir.
Bu bulgular statik doğrulamadır; bu belge hazırlanırken canlı test yapılmadı.
İlk iş bu uyumsuzlukları regresyon testleriyle görünür kılmaktır. İzin listelerini sınırsız gevşetmek çözüm değildir.

## 3. Yerleşim kararı: staging nerede?

```text
Kaynak Oracle                  Hedef Oracle DB/PDB
SRC.<kaynak> -- typed JDBC --> WORK.AKIS_C$... -- IKM --> DATA.<hedef>
                                      |
                                gerekirse AKIS_I$...

AKIŞ Java worker: plan, aktarım, yetki ve işlem yönetimi
AKIŞ PostgreSQL: metadata, değişmez plan ve sonuç kayıtları; iş verisinin staging'i değil
```

WORK ve DATA örnek şema isimleridir; gerçek isimler ortam eşlemesinden gelir.
Staging ayrı sunucu olmak zorunda değildir. İlk sürümde hedef DB/PDB içindeki ayrı çalışma şeması önerilir.
Aynı şema ancak çalışma nesnesi politikası ve yetkiler açıkça izin verirse kullanılabilir; sessiz fallback yoktur.

| Yerleşim | İlk davranış |
|---|---|
| Kaynak başka Oracle, staging hedefte | JDBC aktarımı; temel kabul yolu |
| Kaynak ve hedef aynı Oracle DB/PDB | İlk aşamada aynı güvenli staging yolu; doğrudan yol sonraki optimizasyon |
| Üçüncü Oracle staging | Plan doğrulamasında desteklenmiyor |
| Kaynak DB'de staging | Plan doğrulamasında desteklenmiyor |
| PostgreSQL/MySQL iş kaynağı veya hedefi | Bu runtime tarafından desteklenmiyor |

Aynı veritabanı kararı host/port veya bağlantı ismine değil doğrulanmış DB/PDB kimliğine dayanır.
Gelecekte doğrudan INSERT SELECT stratejisi ayrı capability ve testlerle açılır.
Özellikle kaynak ile hedef aynı tabloysa DELETE'den sonra kaynağı okumaya dayanan optimizasyon yasaktır.

## 4. Bağlantı, şema ve yetki modeli

Fiziksel şemalar bağlantı içinde; mantıksal şemalar ayrı yönetilmeye devam eder.
Mapping `staging.logicalSchemaUuid` seçer. Ortam + mantıksal şema, mevcut eşlemeyle fiziksel şema ve bağlantı sürümüne çözülür.
Çalışma alanı, henüz olmayan bir tablo için sahte katalog/snapshot kaydı oluşturmaz.

Bir fiziksel şemaya çalışma alanı politikası bağlanır:
`enabled`, `managementMode`, `maxObjects`, `maxRowsPerRun`, `maxLogicalBytesPerRun`,
`retentionHours`, `namingVersion`, izinli nesne türleri. Bunlar başlangıçta ürün önerisidir.
Kota ve süreler ortam işletim kararıyla zorunlu belirlenir; limitsiz varsayılan yoktur.
Mantıksal byte sayısı Oracle disk kotasının yerine geçmez; tablespace kotası DBA tarafından uygulanır.

İlk desteklenen yöntem `MANAGED_TABLES`: önceden DBA tarafından açılmış çalışma hesabında run'a özel normal tablolar.
Bu tablolar kısa ömürlüdür fakat session-temporary değildir; worker kaybından sonra inceleme için kalabilir.
DBA-onaylı dinamik DDL yoksa staging runtime kapalı kalır. Önceden kurulmuş tablo havuzu sonraki adaptördür;
ilk sürümde çalışıyormuş gibi seçenek sunulmaz.

Kaynak hesabı salt okuma; çalışma hesabı kendi alanında sınırlı oluşturma/yazma/silme;
hedef hesabı yalnız yetkili hedef ve defter işlemlerini yapabilmelidir. `CREATE/DROP ANY TABLE` varsayılmaz.
Hedef oturumunun çalışma tablosunu okuyabilmesi ayrıca gerekir: ayrı hesaplarda run nesnesi için dar SELECT grant,
aynı hesapta ise mevcut nesne erişimi doğrulanır. Grant hazırlık aşamasının izlenen parçasıdır, hedef DML içinde yapılmaz.
Çalışma hesabı ve hedef hesabı şifreleri plan JSON'una veya loglara girmez.

## 5. Tanım, plan ve sürüm sözleşmeleri

Üç ayrı model kullanılır:

1. Editör taslağı: tamamlanmamış alanlar, seçimler, görsel koordinatlar.
2. Semantik mapping: kaynak/hedef referansları, ifadeler, strateji, sürümü sabit KM/değişken bağları.
3. Fiziksel plan: ortamda çözülmüş çalışma yerleri, adımlar, operation türleri, transaction sınırları ve nesne slotları.

Önerilen yeni sözleşmeler: mapping içerik sürümü 3, KM içerik sürümü 2,
fiziksel plan sürümü 1 ve `ORACLE_STAGED_MAPPING_V1` capability.
Bunlar yalnız backend doğrulayıcı, migration ve testleri birlikte hazır olduğunda etkinleşir.
Eski `ORACLE_TABLE_COPY_V1` / `ORACLE_PROCEDURE_V1` içerikleri ve hash'leri değiştirilmez.
Eski tanım dönüşümü yeni sürüm oluşturur; dönüştürülemeyen alanlar kullanıcıya bildirilir.

KM mevcut `KNOWLEDGE_MODULE` tanımı ve sürüm altyapısında saklanır; ikinci CRUD sistemi kurulmaz.
İlk KM'ler yerleşik ve denetlenmiş operation reçeteleridir. Projeye sürümlü kopya/provenance ile alınır.
Kopya değiştirmek otomatik yürütme yetkisi vermez: runtime desteklediği reçete/operation sözleşmesini doğrular.
Mapping değişmez KM sürüm UUID'si ve hash'i kullanır; güncel KM sürümüne kendiliğinden geçmez.

Örnek KM alanları: `kind`, `recipeVersion`, `technologyConstraints`, `supportedLayouts`,
`optionSchema`, `steps`, `requiredCapabilities`.
Adım alanları: `id`, `phase`, `dependsOn`, `executionSite`, `operation`, `objectSlots`,
`transactionGroup`, `timeoutPolicy`, `retryClass`, `metricContract`.
İlk plan sıralı bir zincirdir; bu alanların varlığı genel paralel DAG motoru desteği anlamına gelmez.

## 6. İlk mapping ve seçenek örneği

Aşağıdaki JSON kavramsal sözleşme örneğidir; bugünkü API'ye gönderilecek payload değildir.
UUID yer tutucuları uygulamada gerçek UUID olarak doğrulanır.

```json
{
  "schemaVersion": 3,
  "sources": [{"id": "SRC_1", "dataObjectUuid": "<source-uuid>", "snapshotUuid": "<snapshot-uuid>"}],
  "target": {"id": "TGT_1", "dataObjectUuid": "<target-uuid>", "snapshotUuid": "<snapshot-uuid>"},
  "columns": [{"targetColumn": "ID", "expression": {"kind": "COLUMN", "datasetId": "SRC_1", "column": "ID"}}],
  "staging": {"logicalSchemaUuid": "<work-logical-schema-uuid>"},
  "modules": {
    "loading": {"versionUuid": "<lkm-version-uuid>", "contentHash": "<sha256>"},
    "integration": {"versionUuid": "<ikm-version-uuid>", "contentHash": "<sha256>"}
  },
  "writeStrategy": "ATOMIC_FULL_REFRESH",
  "options": {"batchRows": 500, "fetchRows": 500, "allowEmptySource": false},
  "variables": []
}
```

Batch/fetch önerisi 500, ilk yapılandırma aralığı 1–5.000 satır; performans garantisi değil ölçülecek başlangıç değeridir.
Uygulama tamponu için ayrıca byte sınırı önerisi 16 MiB; sürücü buffer'ları buna dahil sanılmaz.
Boyut aşımında daha küçük batch kullanılır; tek satır izinli sınırı aşıyorsa açık hata verilir.
İşin toplam satır/byte kotası batch sınırından ayrıdır; aşımda hedefe eksik veri uygulanmaz.
`allowEmptySource=false` beklenmeyen boş kaynakta hedefin tamamının silinmesini önler.
İlk sürümde `gatherStats`, `flowControl`, `mergeKeys` gibi executor'ı olmayan seçenekler kabul edilmez.

İlk mapping yalnız doğrudan kolon eşlemesi ve tam kaynak kümesi destekler.
Tarih filtresiyle bir günlük veri seçip tüm hedefi silmek bu stratejiye sessizce eklenmez.
Tarih aralığı yenileme için ayrı IKM ve hedef silme predikatı sözleşmesi gerekir.

## 7. Derleyici ve SQL güvenliği

Sıra: içerik → sürümlü bağımlılıklar → katalog tipleri → yerleşim → capability → operasyon üretimi
→ yetki/transaction analizi → kanonik plan → yayın.
Önizleme iş verisine yazmaz. Gerekli canlı metadata kontrolleri salt okunur ve açıkça işaretlidir.
Yayın, önizleme girdilerinin sürüm/hash'ini kontrol eder; farklı bağlarla aynı onay kullanılmaz.

İlk operation kümesi: `ALLOCATE_WORK`, `CREATE_WORK`, `GRANT_READ`, `TRANSFER_JDBC`,
`SEAL_WORK`, `ATOMIC_REPLACE`, `RECONCILE_TARGET`, `CLEANUP_WORK`.
SQL metni serbest script motorundan değil teknolojiye özgü typed operation üreticisinden çıkar.
Nesne isimleri katalog veya kayıtlı slotlardan doğrulanıp quote edilir; değerler JDBC bind olur.
İlk sürümde Java/Groovy/shell kodu, DB link oluşturma ve keyfi SQL parçası enjeksiyonu yoktur.

Yayın planı `WORK_SOURCE_1` gibi sembolik slot ve şema yapısını saklar.
Run başlangıcında slot run/nesil tablosuna bağlanır; gerçek komut hash'i ayrıca kaydedilir.
Plan hash'i çalışma tablosu adı sonradan bulundu diye değiştirilmez.
Hash kanonikleştirmesinde nesne alan sırası normalize edilir; anlamlı kolon ve adım sırası korunur.

## 8. Çalışma nesnesi ve aktarım

`AKIS_C$` taşınan küme, `AKIS_I$` gerektiğinde entegrasyon kümesi, `AKIS_E$` ileride reject kümesidir.
Bağlantı üzerinde LKM yükleme, IKM entegrasyon ve CKM hata tablosu prefixleri tanımlanır.
Fiziksel şema bunları devralır veya açık bir özel ayarla ezer. Çözüm sırası fiziksel şema →
bağlantı → platform varsayılanıdır (`C$`, `I$`, `E$`). Kullanıcının güncel kararıyla bağlantı
varsayılanı desteklenir; eski yalnız şema düzeyindeki öneri artık geçerli değildir.
Sabit `AKIS_` işareti korunur; özel prefix 1–8 ASCII karakterdir. Üç rol farklı prefix kullanır.
Çözülen değerler yayın planına sabitlenmelidir; çalışan yayın sonradan değişen ayarı okuyamaz.
İlk birebir aktarımda tek C tablosu yeterlidir; yalnız isim geleneği nedeniyle I/E oluşturulmaz.
Ad üreticisi sürümlüdür ve gerçek Oracle identifier sınırını uygular.
Prefix sahiplik kanıtı değildir: proje, job, run, generation, slot, DB/PDB, owner,
nesne kimliği ve yapı hash'i birlikte kontrol edilir. Çakışan nesne sahiplenilmez veya silinmez.

Yaşam döngüsü: `ALLOCATED → CREATING → READY → LOADING → SEALED → CONSUMED → CLEANUP_PENDING → DROPPED`.
Belirsiz DDL `REVIEW_REQUIRED` durumuna gider. Tahsis CREATE'den önce kalıcılaşır.
CREATE yanıtı kaybında nesne/owner/yapı eşleşmesi incelenir; ad eşleşmesiyle kör DROP yapılmaz.

Worker tek kaynak cursor'undan typed satırları sınırlı buffer ile okur, batch halinde stage'e yazar.
Stage batch'leri ayrı commit edilebilir; tamamlanana kadar hedef tarafından kullanılamaz.
İlk sürüm belirsiz stage batch commit'ini yeniden göndermek yerine nesli karantinaya alır;
güvenli yeni çıkarım yeni run/nesil ile baştan başlar. Eski kısmi veri birleştirilmez.

`SEALED` için cursor'ın başarıyla tükenmesi, kotaya/timeout'a çarpmaması, kesin stage yazımı,
okunan/yazılan sayının eşleşmesi, stage'in nesne/yapı kimliği ve izinli boş sonuç kararı gerekir.
Batch sıra numarası ve typed kanonik payload özeti yükleme sırasında hesaplanır; SQL'in doğal sırası varsayılmaz.
Bu kanıt yeniden çalıştırılan sorgunun aynı veriyi döndüreceği garantisi değildir.
SEALED nesle worker yeniden yazamaz; başka worker erişimi generation/lease ile engellenir.
DBA'nın veya kontrol dışı hesabın veriyi değiştirmediği güven sınırı açıkça belgelenir.

İlk tip matrisi: NUMBER için BigDecimal ve precision/scale kontrolü; VARCHAR2/NVARCHAR2 için
Unicode ve uzunluk kontrolü; DATE/TIMESTAMP için saat bilgisini koruyan açık eşleme; NULL için typed bind.
CHAR padding, timezone türleri, LOB, RAW, binary floating point ve özel tipler test matrisi olmadan reddedilir.
Oracle DATE yalnız takvim günü kabul edilmez. NLS'e dayalı örtük string dönüşümü yapılmaz.
Kaynak kesiti ilk sürümde tek SELECT cursor'uyla sınırlıdır; paralel parçalı okuma ve çoklu sorgu tutarlılığı vaat edilmez.

## 9. Hedef transaction'ı ve hata sınırı

Oracle DDL implicit commit davranışı nedeniyle CREATE/GRANT/DROP hazırlığı ve temizliği
hedef DML oturumundan ayrılır. Kaynak: [Oracle COMMIT](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/COMMIT.html).
Yeni staging DDL adaptörü eski hedef defteri sözleşmesindeki DML-only sınırını kaldırmaz.

Hedef aşaması:

1. Mühürlü stage, hedef kimliği, yapı ve yetkiler doğrulanır.
2. Mevcut hedef sahipliği/fencing protokolü edinilir; yalnız PostgreSQL lease'i yeterli sayılmaz.
3. Aynı Oracle hedef transaction'ında gerekli kilit, son preflight, DELETE ve INSERT SELECT uygulanır.
4. Etkilenen satırlar beklenen stage sayısıyla kontrol edilir; hedef defteri aynı transaction'a yazılır.
5. Tek commit; kesin sonuç daha sonra PostgreSQL'e yansıtılır.

Hedef uygulamasında batch başına commit yoktur. TRUNCATE bu IKM'nin alternatifi değildir.
Constraint, trigger, foreign key ve hedef tablo güvenlik koşulları destek matrisiyle doğrulanır;
desteklenmeyen yazma davranışı preflight'ta reddedilir. Sonuç sayısı tek başına değer doğruluğu kanıtı değildir;
typed transfer doğruluğu ayrıca test edilir, tam readback olmadan kriptografik eşitlik iddiası verilmez.

| Olay | Veri sonucu ve davranış |
|---|---|
| Kaynak/stage yarıda kalır | Hedef değişmez; stage mühürlenmez |
| DML başarısız, rollback kesin | Başarısız; kontrollü yeni çalıştırma mümkün |
| Commit/rollback yanıtı belirsiz | Sonuç belirsiz; otomatik tekrar yok, hedef defteriyle mutabakat |
| Oracle commit kesin, PostgreSQL kaydı kayıp | Mutabakat sonucu onarır; ikinci veri yazımı yok |
| Veri başarılı, temizlik başarısız | Başarılı veri + temizlik uyarısı; yalnız cleanup tekrar edilir |
| Lease kaybı veya eski worker | Yeni yetkili işlem başlatılmaz; mevcut çağrı sonucu kesin değilse belirsizlik yolu |

Cancel istek olarak kaydedilir. JDBC cancel/timeout işlemin kesin rollback kanıtı değildir.
Aktif veya sonucu belirsiz çalışmanın stage'i otomatik silinmez. TTL güvenlik koşullarını geçersiz kılamaz.
İlk sürüm otomatik veri retry/resume sağlamaz; mevcut mutabakat kabiliyeti yeni stage kanıtına uyarlanır.
Mevcut job idempotency/fence kimlikleri korunur; yeni run UUID'si eski commit'i tekrar yazma gerekçesi değildir.

## 10. Değişkenler

Editörde `@DEGISKEN`; çalışırken typed bind. Metne değer birleştirme yoktur.
Değişken sürümü ve ortam bağı yayında, çözülmüş değer iş/çalıştırma snapshot'ında sabitlenir.
NONE/LATEST/ALL yalnız görünür geçmişi etkiler; operasyonel snapshot bundan bağımsız saklanır.
Yeni veri aralığı yeni iş anlamına gelir; eski işin devamında değişken sessizce yeniden hesaplanmaz.
Proje değişkeni ile satır bind isimleri çakışırsa derleme hatası verilir.

İlk mapping tam kaynak aktarımında değişken gerektirmez. Snapshot altyapısı hazırlanır;
mevcut prosedürün sınırlı SYSDATE yenilemesi geriye uyumlu kalır.
Serbest scalar SELECT ve artımlı tarih filtreleri ayrı fazda read-only yetki/nesne kapsamı,
tek satır/kolon, NULL politikası ve tip doğrulamasıyla açılır. Sınırsız SELECT regex'i çözüm değildir.

## 11. Backend ve veri modeli

Java/Spring Boot ve JDBC korunur; yeni servis veya mesaj kuyruğu ilk kapsam için gerekmez.
Metadata/PostgreSQL ile hedef Oracle'ın tek transaction olduğu varsayılmaz.

| Bileşen | Sorumluluk |
|---|---|
| KnowledgeModuleValidator/Registry | Sürümlü reçete, seçenek ve kapsam kontrolü |
| LogicalMappingPlanner | Kaynak/hedef kolon semantiği ve tip denetimi |
| PhysicalMappingPlanner/CapabilityAnalyzer | Yerleşim, destek durumu ve açıklanabilir ret |
| OraclePlanRenderer | Typed operasyonlardan güvenli SQL üretimi |
| WorkObjectManager | Tahsis, nesil, kimlik, mühürleme ve cleanup |
| JdbcStreamingTransfer | Sınırlı bellekle typed kaynak→stage aktarımı |
| StagedMappingRunHandler | Planı mevcut yetki/lease altyapısıyla yürütme |
| StagedAtomicRefreshWriter | Mühürlü stage'den hedef transaction'ı; pilot writer'ı sessizce değiştirmez |

Mevcut metadata, scenario, publication, topology ve execution store genişletilir.
Yeni migration'lar `database/akis-baseline` zincirine eklenir; eski migration/hash'ler değiştirilmez.
Yeni ana kayıtlar: fiziksel çalışma alanı politikası, run çalışma nesnesi,
run değişken snapshot'ı, genel adım artefaktı ve işlem sonucu.
Fiziksel planın tek otoritesi değişmez yayın artefaktıdır; sorgu tabloları yeniden üretilebilir projeksiyondur.
Çalışma nesnesi unique anahtarları slot/nesil ve fiziksel ad çakışmasını engeller;
proje kapsamı foreign key ve API yetkisiyle birlikte korunur.

API önerileri (henüz mevcut değil):

- Sürümlü tanım altında `POST .../physical-plan/preview`: plan, uyarılar, gerekli yetkiler, hash.
- `POST .../km-compatibility`: uygun modüller ve ret nedenleri; veri yazmaz.
- Mevcut yayın akışı: beklenen preview hash ve sürüm kontrolü.
- `GET .../runs/{r}/work-objects` ve `.../steps/{s}/artifacts`: proje yetkili okuma.
- Cleanup serbest tablo adı almaz; kayıt kimliği ve sunucu taraflı güvenlik kontrolü gerektirir.

## 12. Ekran standardı

Ant Design, mevcut ortak font/spacing/renk token'ları ve ekran–filtre–özet–veri düzeni korunur.
Bu iş yeni genel tema dönüşümü değildir. Sol proje ağacı sabit; sağda nesne editörü kalır.

Mapping: `Tasarım | Çalışma Planı`.
Tasarımda kaynak/hedef ve kolon eşlemesi; Çalışma Planı'nda ortam, çalışma şeması,
uyumlu yükleme/entegrasyon modülü ve seçenekleri; altında backend'in ürettiği adım grid'i.
Seçili adım ayrıntısı açıkça ayrılmış bölüm; SQL inline okunabilir, büyük düzenleyici yalnız uygun bağlamda açılır.
Üretilen SQL doğrudan düzenlenmez; kullanıcı modül/ifadeyi değiştirip yeniden önizler.

Kullanıcı durumları: `Kaydedildi`, `Doğrulama Gerekli`, `Çalıştırılabilir`, `Desteklenmiyor`.
Kaydetme toast'ı taslak/nihai yayın ayrımını yanlış anlatmaz: “Tanım kaydedildi”; yayın ayrı eylemdir.
Kaydetmek başarılı test veya canlı bağlantı gerektirmez; çalıştırılabilir yayın ve run preflight gerektirir.

Çalıştırma geçmişinde önde: nesne, durum, süre, kaynak/hedef satırları, aşamalar ve hata.
Hazırlık → Aktarım → Hedefe Yazma → Temizlik aşamaları anlaşılır isimlerle gösterilir.
KM sürümü, defter, hash, batch ve generation isteğe bağlı Teknik Ayrıntılar içinde kalır.
Okunan/stage'e yazılan/hedefe eklenen/silinen sayılar toplanıp tek benzersiz kayıt sayısı yapılmaz.
Bilinmeyen sayaç 0 gösterilmez; “Ölçülemedi” gösterilir. Sayılar commit durumundan ayrı tutulur.

## 13. Uygulama fazları ve kabul kapıları

| Faz | Somut teslim | Kabul |
|---|---|---|
| F0 | Mapping editörü/runtime sözleşme hizası, capability mesajları | UI→taslak→sürüm→senaryo→yayın destekli örnek; eski prosedür ve hash regresyonları yeşil |
| F1 | Mapping/KM yeni sürümleri, typed seçenekler, plan önizleme | Deterministik plan; sürüm/kapsam/SQL enjeksiyon retleri; önizleme sıfır iş DML/DDL |
| F2 | Çalışma şeması politikası, nesne tahsisi ve streaming | İzole Oracle'da >1.000 satır; kota/iptal/DDL yanıt kaybı; sınırlı bellek; hedefe yazma henüz kapalı |
| F3 | Staged atomik refresh, defter/mutabakat, cleanup, run görünümü | Commit yanıt kaybı, eski worker, alias çakışması, rollback ve cleanup hata testleri |
| F4 | Dönüşümler, CKM, APPEND/MERGE ve tarih aralığı stratejileri | Her strateji ayrı capability; duplicate/null/tip/sayaç ve idempotency testleri |
| F5 | Çoklu kaynak/JOIN, üçüncü staging, özel KM/CDC | Ayrı araştırma ve kabul; bu belgenin ilk sürüm taahhüdü değil |

F0'daki eski pilot uyumu: önce failing test, ardından yeni authoring çıktısına açık sürümlü dönüştürme.
Sadece MAPPING değerini 2 yapmak yeterli değildir; katalog binding ve editör alanları da çözülmelidir.
Eski yürütücü alan izinlerini genişletme ancak açık yeni contract testiyle yapılır; bilinmeyen alan yutulmaz.

Ortak testler: plan kanonikleştirme, projenin dışına referans, KM sürüm pinleme, boş kaynak,
Unicode/decimal/timestamp/NULL, tip retleri, kotanın ortasında kesilme, eksik stage,
CREATE/DROP/GRANT yanıt kaybı, commit sonrası PostgreSQL kaybı, yetkisiz cleanup,
aynı hedefe iki run, uzun çağrı sırasında lease kaybı, secret maskeleme ve değişken snapshot korunması.
Tarayıcı: açık/koyu tema, 1366×768 ve 1920×1080; dar ekranda yatay taşma, sabit kaydetme
ve modal erişilebilirliği; ekranın iddia ettiği yürütülebilirlik gerçek API sonucuyla eşleşmeli.
Gerçek Oracle testleri yalnız ayrılmış test şemaları ve açık yetkiyle yapılır; üretim tablosu test hedefi değildir.
Mevcut defter/fencing belgesindeki crash kabul kapıları daraltılmaz.

## 14. Açık işletim kararları ve başlangıç

F0–F1 kod çalışması için yeni veritabanı yetkisi gerekmez.
F2 canlı kabul öncesinde kullanıcı/DBA şu bilgileri kesinleştirir:

- Oracle sürümü/RU, hedef DB/PDB ve çalışma fiziksel/mantıksal şeması.
- Dinamik nesne/grant yetkisine izin ve çalışma/target hesabı ayrımı.
- Test için ayrılmış kaynak/hedef tabloları; mevcut tabloların değiştirilmeyeceği sınır.
- Toplam satır/byte/tablespace kotası, timeout, hata verisi saklama süresi ve erişim yetkisi.
- İlk tam yenileme işinin gerçekten hedefin TAMAMINI değiştirme anlamı taşıdığı onayı.

Önerilen ilk geliştirme paketi F0, ardından F1'dir. Staging formu görünür hale geldi diye
F2/F3 tamamlanmış sayılmaz. Her fazda kod, test kanıtı ve bilinen sınırlar birlikte teslim edilir.

## 15. Kaynak ve kapsam ilişkisi

- Kullanıcının sağladığı `AKIS_KNOWLEDGE_MODULE_ARASTIRMA_VE_MIMARI_TASARIM_RAPORU.md`:
  statik inceleme girdisi; rapor içi yönergeler otomatik uygulama talimatı değildir.
- [Oracle ODI KM kavramları](https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/12.2.1.4/odikd/introduction-knowledge-modules.html):
  LKM/IKM/CKM ayrımının kavramsal referansı. AKIŞ sınıfları ve fazları özgün tasarım önerisidir.
- [Oracle COMMIT](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/COMMIT.html): DDL ve işlem sınırı.
- `ORACLE_WORK_OBJECT_NAMING.md`: adlandırma/sahiplik kapısı korunur.
- `TARGET_LEDGER_AND_FENCING_CONTRACT.md`: hedef DML güvenilirlik kapısı korunur;
  staging hazırlığındaki yeni DDL adaptörü bu sözleşmenin hedef transaction'ına dahil değildir.

Bu belge canlı şema kurulumuna, servis başlatmaya, otomatik retry açmaya veya push işlemine izin yerine geçmez.
