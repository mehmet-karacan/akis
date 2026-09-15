# n8n karşılaştırma raporu: uygulama ve kabul takibi

Tarih: 2026-09-15. Başlangıç commit'i: `3d1781365321c0f0d63bcc91cc78b197adefe284`.
Kaynak: kullanıcının `AKIS_N8N_KARSILASTIRMA_VE_UYGULANABILIRLIK_RAPORU.md` raporu.

## Durum

**Raporun tamamı uygulanmış değildir.** İlk güvenilirlik dilimi kodlandı; yerel birim
testleri ve izole tarayıcı bileşen testi çalıştırıldı. Çalıştırma, worker/journal ve
yayın repository PostgreSQL kontrolleri geçti. Gerçek Oracle hata enjeksiyonu ve
rapordaki henüz uygulanmamış yeteneklerin uçtan uca kabulü bekliyor.
Mevcut paket/mapping/load-plan tasarım ekranları genel amaçlı çalışma motoru olarak
sunulmayacaktır. Hiçbir üretim bağlantısında veri değiştiren test yapılmadı.

## İlk dilimdeki değişiklikler

- Commit çağrısına girildikten sonra hata oluşursa rollback ile güvenli tekrar iddiası
  üretilmez. JDBC oturumu belirsiz commit sonrasında ikinci commit/teyitli rollback
  çağrısını reddeder; kapatırken kaynak temizliği ayrı tutulur.
- Named bind keşfi ve JDBC SQL üretimi aynı Oracle-farkındalıklı taramadan gelir.
  Literal/yorumlar değişmez; tekrar, harf büyüklüğü, `$`/`#` korunur. Sabitlenmiş bind
  sırası ile uyuşmayan SQL yürütülmez. Standalone ve batch aynı üreticiyi kullanır.
- Üretim ortamı ve görev onayı yayın/runtime tarafında ortak kuraldan hesaplanır.
  Var olan immutable manifestler veya içerik özetleri yerinde değiştirilmez.
- `POST /api/v1/projects/{projectUuid}/sql/validate` proje okuma yetkisi ister;
  SQL çalıştırmaz. Statik risk ve sınırlı yapı kontrolleri sunucu/runtime tarafından
  paylaşılır. Bu bir Oracle parser ya da tüm nesne/kolonların doğrulaması değildir.
- SQL kontrolü kullanıcı düğmesiyle yapılır. Eski istek yanıtları yeni SQL'i etkilemez.
  Biçimlendirme hataları SQL'i değiştirmeden gösterilir. SQL bileşeninin stili ortak
  bileşene taşındı; koyu tema CodeMirror'a da uygulanır.
- Basit mapping editörünün temsil edemediği çok argümanlı/iç içe/tipli ifadelerde
  Uygula kapalıdır. Mevcut AST silinmez. Özet üretimi derinlik/boyut açısından sınırlıdır.
- INSERT sayaçları adım adından tahmin edilmez. Immutable scenario içindeki `logCounter`
  kullanılır. Commit teyidi olmayan sonuçlar `UNCONFIRMED` kalır; managed işlemde
  devam edilen hata varsa commit toplamı muhafazakâr şekilde gösterilmez. Bu, henüz
  tam transaction-group ledger veya ayrıntılı INSERT/UPDATE/MERGE metriği değildir.
- Kaynak sorgunun yayın ve okuyucu kontrolleri aynı sözleşmeyi kullanır; tanımlı tipli
  bind ile sınırlı eşitlik/tarih aralığı filtreleri katmanlar arasında tutarlıdır.
- Refresh sonucu aynı yürütme oturumu ve sabitlenmiş bağlantı içinde tekrar kullanılır.
  NULL/çok satır/çok kolon reddedilir; timeout uygulanır. Oracle DATE refresh sonucunun
  saat bilgisi Timestamp bağlamasında korunur. **Kalıcı run snapshot, restart ve değişken
  sürüm bağımlılığı henüz yoktur**; bu genel amaçlı değişken motoru değildir.
- Runtime kapalıyken capability API çalıştırılabilir profil ilan etmez; worker'ın
  kapalı olduğu durum ayrıca bildirilir.
- Yeni prosedür yayınları `policyVersions` ile bind derleyicisi, SQL ve onay politikası
  sürümlerini sabitler. Alan yayın ve runtime hash'ine dahildir; eksik/bozuk/bilinmeyen
  sürüm kümesi reddedilir. Tarihsel alanı olmayan yayınlar uyumluluk yolunda kalır;
  kayıtları/hash'leri değiştirilmez. Gelecekte politika sürümü artırılırken bu eski
  yol için ayrıca uyumluluk uygulaması veya açık yeniden yayınlama geçişi gerekir.
- Mapping AST sunucuda da doğrulanır: COLUMN yalnız SOURCE datasetine referans verir;
  LITERAL metin/sayı/boolean/null tipini korur; TRIM/UPPER/LOWER tek argüman,
  COALESCE 2–64 argüman kabul eder. Bilinen literal tipleri denetlenir; kolon tipi
  metadata olmadan tahmin edilmez. En fazla 32 derinlik ve 1000 düğüm işlenir.
  Bu doğrulama SQL üretimi/çalıştırması değildir; tam recursive editör hâlâ bekliyor.
- `scripts/test-quality-gate.ps1` backend birim testleri, frontend test/lint/build
  kontrollerini tek komutta çalıştırır; başarısızlıkta durur. Yalnız yeni `*Test.xml`
  dosyalarını sayar; eski `*IT.xml` dosyalarını kabul kanıtına katmaz. Çalıştırılmayan
  tarayıcı/entegrasyon kontrollerini açıkça `not_run` olarak gösterir. CI etkinleştirilmedi.
- Execution entegrasyon betiği güncel journal migration'larını da kuracak şekilde
  düzeltildi; geçici veritabanı adı korunur ve işlem sonunda ortam değişkenleri geri
  alınır. SQL dosyaları container içinde ortak dosyaların üzerine yazılmadan aktarılır.

## Rapor kapsamının tamamı

| Madde | Durum | Kalan kabul / çalışma |
|---|---|---|
| R01 İşlem sonucu | Kısmi | Transaction-group kalıcı kanıtı, gerçek commit-loss ve çok kanal kabulü |
| R02 SQL bind derleyici | Çekirdek/sürüm sabitleme uygulandı | Oracle entegrasyonu, geniş property testleri ve eski yayın geçiş politikası |
| R03 Onay politikası | Çekirdek/sürüm sabitleme uygulandı | HTTP/DB üretim onay matrisi |
| R04 SQL politikası | Çekirdek/sürüm sabitleme uygulandı | Geniş FE/BE fixture matrisi ve tüm editör tüketicileri |
| R05 Kayıpsız AST | Koruma ve sunucu imzaları uygulandı | Tam recursive/type-aware editör, metadata kolon tipi doğrulaması |
| R06 Metrikler | Kısmi | Kalıcı typed metrics, işlem grupları, MERGE ve büyük sayı sözleşmesi |
| R07 Kalite kapıları | Kısmi | PostgreSQL migration/repository, izole Oracle fault injection, tam E2E/a11y |
| R08 Yetenek görünürlüğü | Kısmi | Her nesnede tasarım/derlenebilir/yürütülebilir durumları ve nedenleri |
| R09 Değişken bağlamı | Oturum temeli uygulandı | Değişmez değişken sürümü, run snapshot, tarih/zaman/null ve yeniden başlatma |
| R10 Tipli ifade motoru | Bekliyor | Sınırlı AST, imza/tip kataloğu, güvenli değerlendirme |
| R11 Paket motoru | Bekliyor | Sürüm sabitlenmiş alt prosedürler, compiler ve runtime |
| R12 İptal/yeniden deneme | Bekliyor | Kanıtlı işlem sonucu ve checkpoint sonrası güvenli resume |
| R13 Test/yayın ayrımı | Bekliyor | Readonly önizleme, üretim onayı ve sabit sürüm deneyimi |
| R14 SQL çalışma alanı | Kısmi | Manuel kontrol mevcut; izinli readonly preview/veri inceleyici bekliyor |
| R15 Mapping motoru | Bekliyor | Tipli Oracle SQL üretimi, kontrollü veri hareketi ve hacim testleri |
| R16 Yük planı | Bekliyor | Paket sonrası seri plan, sonra limitli paralellik ve hata dalları |
| R17 Kimlik bilgileri | Bekliyor | Üretim TLS/ENV doğrulaması; ihtiyaç varsa ek secret provider |
| R18 Yetki/audit | Bekliyor | Çapraz proje/rol matrisi ve uçtan uca audit kapsamı |
| R19 Nesne seçimi | Bekliyor | Ortak referans seçici, sürüm/kapsam görünürlüğü |
| R20 Düzenleme geçmişi | Bekliyor | Ortak command history, undo/redo ve klavye işlemleri |
| R21 Diyagram yerleşimi | Bekliyor | Kalıcı paylaşılabilir layout ve otomatik yerleşim |
| R22 Tasarım sistemi | Kısmi | SQL stili ortaklaştırıldı; tüm ekranların tema/a11y kabulü bekliyor |
| R23 Ölçekli listeleme | Bekliyor | Sunucu pagination, gerçek virtualization ve ölçülmüş performans |
| R24 Çalışma inceleyici | Kısmi | İşlem kanıtı görünürlüğü; reconnect/retention/ileri metrikler bekliyor |
| R25 Lineage | Bekliyor | Sürüme bağlı bağımlılık ve tarihsel etki analizi |

## Kabul komutları ve sınırlar

- Backend: `./mvnw.cmd -pl backend test -q` (Surefire birim testleri; `*IT` dahil değildir).
- Ortak yerel kapı: `./scripts/test-quality-gate.ps1`; açık frontend ile isteğe bağlı
  `-SqlBrowserComponent`. Bu komut tam yayın kabulü yerine geçmez.
- Frontend: `npm.cmd test -- --reporter=dot`, `npm.cmd run lint`, `npm.cmd run build`.
- Tarayıcı bileşeni: Vite açıkken `npx.cmd playwright test e2e/sql-policy.component.spec.ts`.
  API yanıtları bu testte taklit edilir; backend/Oracle kabulünün yerine geçmez.
- Tam uygulama tarayıcı kabulü: `scripts/run-e2e.ps1`; çalışan backend ve metadata DB gerekir.
- PostgreSQL kabulü yalnız adı üretilmiş ayrı test veritabanında çalıştırılmalıdır.
- Oracle testleri için ayrı şema ve kontrollü bağlantı hatası ortamı gerekir.

## Ortam engelleri

### 2026-09-15 yerel sonuçlar

- Backend: 71 birim test sınıfı, 421 test; hata/başarısızlık yok.
- Frontend: 51 test dosyası, 165 test geçti; lint ve production build geçti.
- Canlı backend ile mevcut tarayıcı paketi: **22 test geçti**. Ana ekranların
  açık/koyu tema ve dar ekran kontrolleri, oturum, doğrudan prosedür editörü,
  bağlantı kart/liste/tablo ve hata geri bildirimi, model gezgini, SQL politika HTTP
  kimlik doğrulaması/kaynak güvenliği kontrol edildi. Bazı hata/yetki senaryoları API
  yanıtı taklit eder; Oracle veri yürütme/fault-injection testi değildir.
- İlk tarayıcı turunda 20 test geçti, eski `Tasks` sekmesini arayan 1 test başarısızdı.
  Gerçek ekran ve test çıktısı incelenerek, doğrudan açılan adım tablosu + detay
  bölgesini doğrulayacak şekilde güncellendi; sonraki tam tur yukarıdaki 22/22 sonucudur.
- Playwright SQL bileşen testi: 1 geçti; 390/1440 piksel genişlikleri, manuel kontrol,
  hata mesajı, SQL değişince eski sonucun temizlenmesi ve koyu tema kontrol edildi.
- Prosedür/bağlantı testlerindeki asenkron `act(...)` uyarıları servis yanıtları açıkça
  taklit edilip beklenerek düzeltildi; ilgili 17 test uyarısız geçti. jsdom'un eksik Range ölçüm API'leri test adaptörüyle
  tamamlandı; gerçek yerleşim yalnız Playwright sonucuyla değerlendirildi.
- Ayrı, üretilmiş PostgreSQL veritabanlarında `CleanExecutionRepositoryIT`,
  `CleanWorkerLeaseRepositoryIT`, `CleanProcedureExecutionJournalIT` ve
  `CleanPublicationRepositoryIT`: 4 test geçti. Geçici veritabanları temizlendi.
  Execution/worker testleri V001–V015 güncel şemayı, release testi V001–V006 katmanını
  doğrular. Bu sonuçlar bütün repository sınıflarının veya Oracle yürütmesinin kabulü değildir.

İlk Docker Desktop başlatma girişimi başarısızdı: host logunda
`sailor-ingest.sock` dosyasını yeniden adlandırırken `The file cannot be accessed by the system`
hatası vardı. Sonraki kontrolde Docker 29.8.0 ve PostgreSQL hazır bulundu; yukarıdaki
entegrasyon kontrolleri çalıştırıldı. Docker dosyaları silinmedi veya değiştirilmedi.
Uygulama tarayıcı testi için backend, worker ve yeni çalıştırma kabulü kapalı şekilde
başlatıldı; mevcut kuyruktaki işler/Oracle SQL'i çalıştırılmadı. Canlı şema V015 idi;
Flyway doğrulamasında yeni migration gerekmemiştir.

İzole Oracle test bağlantısı/şeması kullanıcıdan istendi. Mevcut iş tablolarını fault-injection
hedefi olarak kullanmak onaylanmış bir test sınırı sayılmaz.

## Sıra

### Değişken akışı regresyon kontrolü (2026-09-15)

- Değişken draft isteğinin hatası yakalanıp mevcut bildirim yoluna aktarılır.
  Adım/komut/içerik değiştikten sonra dönen eski yanıt artık yeni düzenlemeyi ezemez.
  İki regresyon testi eklendi; frontend toplamı 167 test oldu.
- Sınırlı `SYSDATE - 1` refresh sözleşmesinde yalnız DATE/TIMESTAMP kabul edilir.
  BOOLEAN'a sessizce `false` dönüşmesi önlendi; tanım doğrulaması ve runtime derleyicisi
  ayrı testlerle kontrol edilir. Backend toplamı 423 birim testtir.
- `OracleVariableReadOnlyIT` gerçek Oracle kaynak bağlantısında geçti. Yalnız sabit
  SELECT/DUAL sorguları çalışır: dünün tarih/saatini bind etme ve aynı oturum bağlamında
  tekrar kullanma doğrulanır. Bağlantı test ortam değişkenlerinden alınır; bu test
  mantıksal şema çözümlemesi, yayın veya hedefe veri yazma kabulü **değildir**.
- Gerçek kaydedilmiş değişken draft'ını API'den alıp kaydedilmeyen yeni prosedüre
  ekleyen tarayıcı testi geçti. SQL ve bind adı korunur; iş tanımı kaydedilmez.
  Mevcut tarayıcı paketi bu testle 23 senaryodur.
- **Açık kritik eksik:** değişkenin bağımsız mantıksal şeması/ortam eşlemesi,
  değişmez sürüm referansı ve kalıcı çalışma değeri hâlâ uygulanmamıştır. Mevcut
  adapter tüketen komutun bağlantısını kullanır. Bu nedenle değişken özelliği için
  uçtan uca üretim kabulü verilmemiştir. Veri yazan tam senaryo ayrı test şeması ister.

### Değişken şeması ve değer geçmişi (2026-09-15, sonraki revizyon)

Önceki bölümde belirtilen bağımsız şema ve kalıcı değer eksikleri için:
- VARIABLE formunda proje mantıksal şeması seçilir. Sorgulu değişken doğrulaması
  şema seçimini zorunlu tutar. Yeni değişkenlerde varsayılan geçmiş modu ALL'dır.
- Prosedüre eklenen değişkenin sorgu/tür/şema/geçmiş ayarları prosedür sürümüne
  kopyalanır. Yayın sırasında çalıştırma ortamının fiziksel bağlantı sürümü
  `variableBindings` içinde sabitlenir ve runtime hash'ine dahil edilir.
  Eksik eşleme veya çelişkili değişken referansı yayını durdurur.
- Runtime, tüketen komut bağlantısına geri düşmez. Değişkeni kendi sabitlenmiş
  bağlantısında okur, aynı oturumdaki kaynak/hedef komutlarında tekrar kullanır.
- V016 `degisken_deger_gecmisi`: değer, tür, çalışma, ortam, mantıksal şema,
  bağlantı sürümü ve plan özeti saklanır. ALL bütün değerleri, LATEST ortam bazında
  son LATEST kaydını tutar; NONE kalıcı kayıt üretmez. Önceden ALL ile tutulmuş
  geçmiş, LATEST'e geçildiğinde silinmez. Proje yetkili, 50 kayıtlık cursor API ve
  değişken ekranında Değer Geçmişi bölümü vardır.
- Eski yayınlar otomatik değiştirilmez. Değişken ayarı değişince prosedürdeki
  referans tekrar seçilmeli ve yeni prosedür sürümü/yayın oluşturulmalıdır.
- Backend 425 birim testi geçti. `CleanVariableHistoryIT` gerçek, geçici PostgreSQL
  veritabanında retention ve aynı çalışma snapshot tekrarını doğruladı; bu testte
  Oracle JDBC yanıtı taklittir. `CleanExecutionRepositoryIT` de geçti.
- Gerçek API'ye bağlı Playwright değişken ekranı testi geçti; şema seçimi ve geçmiş
  bölümü tarayıcıda açıldı. Canlı iş tablolarına veri yazılmadı.
- Son toplu frontend koşusunda 52 dosyada 169 test, tarayıcı paketinde 23 senaryo
  geçti; lint ve build başarılıdır. 30 zengin kartı jsdom'da işleyen bağlantı testi
  tek başına geçiyor ancak toplu koşuda 5 saniyeyi aşıyordu; yalnız bu entegrasyon
  testinin zaman bütçesi 15 saniyeye çıkarıldı, işlevsel kontrolleri azaltılmadı.
- Sorgu sözleşmesi hâlâ DATE/TIMESTAMP için sabit `SELECT SYSDATE - 1 FROM DUAL` ile
  sınırlı; genel SQL, paketler arası kapsam ve tam üretim kabulü bu sonuçlardan çıkarılamaz.
- Testler için Spring Boot tarafından sürümü yönetilen Mockito test bağımlılığı eklendi.

### Değişkenli canlı çalıştırma denemesi (2026-09-15)

- `PRC_HAKEDIS_TIPI` yeni sürümüne `DUN_TARIHI` bağlandı: `SELECT SYSDATE - 1 FROM DUAL`,
  DATE, kendi mantıksal şeması ve ALL geçmiş modu. Kaynak koşulu
  `TANIMLAMA_ZAMANI >= :DUN_TARIHI AND TANIMLAMA_ZAMANI < :DUN_TARIHI + 1`.
- Biçimlendirilmiş SELECT ve INSERT satır sonlarını reddeden doğrulamalar düzeltildi;
  DefinitionContentValidatorTest ve ProcedureRuntimePlanResolverTest toplam 40 test geçti.
- Normal sürüm, veri bağı, derleme, yayın ve onay API'leri üzerinden TEST yayını oluşturuldu.
  Yayın UUID: `0a666e47-581d-4780-bd4f-9228e836d7ef`.
- Gerçek çalışma UUID: `ac457541-564e-4c12-9e33-b6b0c724ba6e`.
  `PROCEDURE_TARGET_IDENTITY_FAILED` ile ön kontrolde başarısız oldu; hiç adım başlamadı,
  TRUNCATE/INSERT çalışmadı, değişken değeri veya geçmiş kaydı üretilmedi.
- Salt okunur `OracleProcedurePreflightReadOnlyIT`, sabitlenmiş hedef bağlantının
  Oracle vendor code 28001 (şifre süresi dolmuş) verdiğini doğruladı. SQL/şifre/URL
  hata çıktısına yazılmaz. Hedef hesabın şifresi yenilenmeden canlı uçtan uca kabul verilemez.

Önce R01–R07 kabul kapıları; ardından R17/R18 güvenlik doğrulaması ve kalıcı R09 bağlamı.
Sonra R11/R12 paket işletimi; ardından R15 mapping, R16 yük planı ve R25 lineage.
R19–R24 arayüz geliştirmeleri ilgili runtime yetenekleriyle birlikte doğrulanır.
Yeni bağımlılık ya da n8n kaynak kodu alınmadı; lisans değerlendirmesi yapılmadan
n8n kodu projeye taşınmayacaktır.
