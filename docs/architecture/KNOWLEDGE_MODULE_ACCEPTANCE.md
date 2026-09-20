# KM Kurulum ve Kabul

Bu belge otomatik canlı çalıştırma izni değildir. Yalnız ayrılmış test ortamında uygulanır.

## Yerel PostgreSQL runtime ve CI kontrol noktası: 2026-09-17

- `PostgresRuntimeConnectionIT`, dinamik sağlayıcı profiliyle gerçek yerel PostgreSQL oturumu açıp `select 1` sorgusunu başarıyla çalıştırdı. Kimlik bilgileri yalnızca test ortamından sağlandı; kaynak veya çıktıya yazılmadı.
- Sağlayıcı birim testleri PostgreSQL JDBC URL üretimini, timeout ayarlarını, salt-okunur oturumu, rollback ve maskelenmiş hataları doğruluyor.
- CI workflow'u bu canlı kabul fazında bilinçli olarak devre dışıdır; kalite kapıları yerel olarak çalıştırılmıştır.
- TTBP erişimi Oracle kabul fixture'ı olarak kullanılabilir; ayrı bir Oracle ortamı zorunlu değildir.

## TTBP Oracle erişim kontrolü: 2026-09-17

- `OracleTtbpConnectivityIT`, `.env` içindeki TTBP kaynak bağlantısıyla gerçek Oracle oturumu açtı ve yalnızca `select 1 from dual` sorgusunu çalıştırdı: başarılı.
- Bu kabul TTBP erişimini yeterli Oracle fixture olarak kullanır; başka bir Oracle ortamına bağımlı değildir. Prosedür/DML yürütmesi bu salt-okunur probe kapsamında değildir.

## Büyük tablo aktarım kabulü: 2026-09-17

- TTBP `INNOVA_ODI.AKIS_MILLION_TEST` altında ayrılmış test tablosu oluşturuldu ve 1.000.000 sentetik kayıt üretildi.
- Kayıtlar fetch-size 5.000 ve batch insert 5.000 kullanılarak `akis_pg_target."AKIS_MILLION_TEST"` tablosuna aktarıldı.
- PostgreSQL hedef sayımı `TRANSFERRED_ROWS=1000000` döndürdü; kaynak ve hedef işlemleri test nesnesiyle sınırlıdır.

## Güncel kabul kapsamı: 2026-09-17

Aşağıdaki matris mevcut kodu esas alır. Sonraki "İlk kapsam" bölümü önceki pilotun tarihsel kaydıdır; oradaki JOIN/MERGE/APPEND kapsam dışı ifadeleri ve AKIS_KM/1 önerisi yeni sürümün kapsamı olarak kullanılmamalıdır. Migration ve çalışma bayrakları bu belgenin okunmasıyla yetkilendirilmez.

| Gereksinim | Mevcut uygulama ve yerel kanıt | Canlı kabul için gereken |
|---|---|---|
| Kendi dilimiz ve dinamik modüller | AkisKmLanguage ve AkisKmInterpreter, AKIS_KM/2 seçeneklerini ve Boolean EGER koşullarını yorumlar. Dil/interpreter testleri adım sırasını, koşulu ve hatada hedefe geçilmemesini doğrular. | Kullanıcının oluşturduğu sürümlü LKM/CKM/IKM seçimiyle gerçek yayın ve çalıştırma. |
| Sürüm ve seçeneklerin korunması | KnowledgeModuleRegistry tip, zorunluluk, varsayılan ve yerleşik seçenek kurallarını kontrol eder. StagedRuntimePlanResolver sabitlenmiş kaynak/seçenekleri yeniden derler; fiziksel adım planıyla karşılaştırır. | Gerçek kayıt, yeniden açma, yayın ve sürüm sabitleme yolculuğu; değiştirilmiş planın reddi. |
| LKM seçenekleri | StagedWorkerOrchestrator, DISTINCT ve ORACLE_HINT değerlerini JdbcStagingTransfer.QueryOptions'a aktarır. Aktarım testleri üretilen sorgu, bind sırası ve satır/byte sınırlarını kapsar. | İzinli test kaynağında DISTINCT açık/kapalı sonuç farkı ve geçerli Oracle hint davranışı. |
| CKM koşulları | CHECK_NOT_NULL ve CHECK_UNIQUE, mühürlenmiş çalışma nesnesinde koşullu çalışır. OracleKmRuntime başarısız kontrolde hedef yayınını başlatmaz. | Gerçek null/duplicate verisinde hedefin değişmediğinin doğrulanması. |
| IKM yazma seçenekleri | StagedPublishFacade yazma modu, anahtarlar ve hedef hint değerini writer'a taşır. APPEND, MERGE, TRUNCATE_LOAD ve ATOMIC_DELETE_INSERT bulunur. TRUNCATE_LOAD için ayrıca Boolean TRUNCATE_TARGET=true gerekir. | Her modda kontrollü hedef tablosu, satır sayıları ve hata/geri alma davranışı. TRUNCATE atomik DML değildir. |
| Çok kaynak, join, filtre, ifade | Kaynaklar aynı bağlantı sürümünde olmalıdır. Kaynak filtreleri join öncesinde, genel filtreler sonrasında uygulanır. Plan AST ifadelerini ve metadata sürümlerini korur. | Birden çok kaynak ve farklı filtre kapsamlarıyla gerçek SQL sonucu, kayıt ve yayın doğrulaması. |

17 Eylül 04:41:40 yerel test kaydı: AkisKmLanguageTest, AkisKmInterpreterTest, KnowledgeModuleRegistryTest, StagedRuntimePlanResolverTest, OracleKmRuntimeTest, JdbcStagingTransferTest, JdbcStagedAtomicRefreshWriterTest ve iki VariableTest test sınıfında **83 test**, sıfır hata/başarısızlık/atlama, Maven BUILD SUCCESS. Bu birim/mocked JDBC kanıtıdır; Oracle üzerinde başarı veya gerçek metadata veritabanında kayıt kanıtı değildir.

Dil sonlu operasyonlardan oluşur. Kullanıcının tanımladığı seçenek ancak EGER koşulunda veya desteklenen yürütme davranışında kullanıldığında etkilidir; seçenek eklemek kendiliğinden yeni SQL/Java operasyonu yaratmaz. Serbest Java/eval ve özel RKM yürütücüsü bu kanıtla tamamlanmış sayılmaz. Arayüz, standart JDBC reverse-engineering ile özel RKM çalıştırmayı birbirine karıştırmamalıdır.

Başarılı canlı Oracle testi, gerçek yayın/persistence, DDL kilit/fence ve belirsiz commit kurtarması tamamlanmadan bu matris kabul edilmiş sayılmaz. Ayrılmış hedef için açık yetki olmadan yazma veya TRUNCATE testi yapılmaz.

## İlk kapsam (tarihsel pilot kaydı)

Tek Oracle kaynak, aynı hedef DB/PDB içindeki yönetilen çalışma şeması ve tek hedef tablo.
Doğrudan kolon eşlemesi; atomik tam hedef yenileme. MERGE, APPEND, JOIN, CDC kapsam dışıdır.
DSL sonlu işlem listesidir; Java/Groovy/serbest SQL çalıştırma veya eval değildir.

## Ön koşullar

1. Test projesi, ortamı, kaynak tablosu ve tamamen yenilenmesine izin verilen hedef tablo seçilir.
2. Hedef DB/PDB üzerinde yalnız AKIŞ'ın kullanacağı çalışma hesabı ayrılır. Kendi tabloları için
   CREATE/DROP ve hedef veri hesabına SELECT grant gerekir. Kaynak hesabı salt okunurdur.
3. Hedef ledger/fence kurulumu mevcut runtime sözleşmesine göre DBA tarafından hazırlanır.
   Çalışma hesabı ve hedef hesabı varsayılan olarak ayrıdır; aynı şema istisnası açık politika gerektirir.
4. Metadata yedeği alınır; V017–V020 migrationları sıralı uygulanır. İzole test komutu:
   `./database/akis-baseline/test-knowledge-modules.ps1`.
5. Bağlantı veya fiziksel şemada loading/integration/error prefixleri tanımlanır.
   İlk sürümde loading çalışma tablosu kullanılır; IKM/CKM için gereksiz tablo üretilmez.
6. Fiziksel şemada çalışma alanı etkinleştirilir; nesne/satır/byte kotaları seçilir.
   Maksimum nesne kotasına inceleme bekleyen nesneler de dahildir.
7. Kaynak/hedef metadata keşfi ve snapshot'ları, ortam–mantıksal şema eşleşmeleri hazırlanır.

## Tanım ve yayın

- LKM/IKM ve gerekirse CKM `AKIS_KM/1` diliyle oluşturulur; değişmez sürümleri kaydedilir.
- Mapping sürüm 3'te açıkça ATOMIC_DELETE_INSERT seçilir; çalışma mantıksal şeması,
  KM sürümleri ve kotalar atanır. Boş kaynak varsayılan olarak hedefi boşaltamaz.
- Yayın önizlemesinde kaynak, çalışma, hedef, modül sürümleri, prefix ve adımlar incelenir.
- Yalnız kontrollü kabul ortamında `AKIS_EXECUTION_STAGED_RUNTIME_ENABLED=true` açılır.
  Mevcut manual-request/procedure-runtime/worker flag ve worker kimlik ayarları da gerekir.
  Bu depo değişikliği mevcut `.env` içinde hiçbir flag'i açmaz.
- Flag kapalıyken oluşturulan DEFINITION_ONLY yayını kendiliğinden yürütülebilir hale gelmez;
  flag açıkken yeni, doğrulanmış yayın oluşturulur.

## Kabul matrisi

| Senaryo | Beklenen kanıt |
|---|---|
| 1.201 satır, batch 500 | Bütün satırlar aktarılır; tek atomik hedef yayın; sonuçta aynı satır sayısı |
| Boş kaynak, izin kapalı | Hedef değişmez; yükleme başarısız |
| Satır/byte kotası aşımı | Hedef değişmez; çalışma nesnesi incelemeye kalır |
| CKM null/unique ihlali | Hedef oturumu/yayını başlatılmaz |
| Politika değişimi veya lease kaybı | Sonraki etki durur; eski işleyici yayınlayamaz |
| Hedef DML hatası | Hedef işlemi rollback; kör tekrar yok |
| Commit cevabı kaybı | Sonuç belirsiz; yeni bağlantı/fence ile ledger mutabakatı |
| Mutabakat PUBLISHED | Başarı kanıtı görünür; ilk belirsiz kayıt korunur; ikinci DML yok |
| Yanlış object ID/kolon yapısı | DROP reddedilir; inceleme durumu |
| Başarılı normal akış | Kendi çalışma nesnesi temizlenir; kaynak tablo değişmez |
| Başka proje/sürüme erişim | Tanım, yayın, günlük ve mutabakat reddedilir |

Başarısız/belirsiz çalışma nesneleri otomatik silinmez. Saklama süresi dolması tek başına
DROP yetkisi değildir. DBA incelemesi gerekir. Reconciliation eski nesil için cleanup yetkisi üretmez.

## Kabul kaydı

Her deneme için yayın UUID/hash, çalıştırma UUID, beklenen/gerçek kaynak ve hedef sayıları,
adım sonuçları, kontrol düzlemi/Oracle ledger kanıtı ve cleanup sonucu kaydedilir.
Kimlik bilgileri ve iş satırı payload'ları rapora alınmaz.
Tam ürün tarayıcı akışı ve gerçek Oracle matrisi geçmeden canlı kullanım onayı verilmez.
