# Akış Kurumsal UI/UX ve Ürün Deneyimi Araştırma Raporu

## 1. Yönetici özeti ve kesin öneri

**Akış, üç çalışma alanı etrafında düzenlenmelidir: Geliştirme, Operasyonlar ve Bağlantılar.** Proje bağlamı bu alanların ön koşulu olmalı; Proje Genel Bakış yalnız sakin bir giriş sayfası olarak kalmalıdır. Nesne tanımlamak, çalıştırılabilir sürüm hazırlamak ve o sürümün çalışmasını izlemek aynı işlem gibi sunulmamalıdır. Bu yön, kabul edilmiş V2 ürün kararlarını uygular; yeni bir ürün kapsamı veya farklı bir veri taşıma mimarisi önermez. [R03 · Ürün Deneyimi V2 Kararları](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PRODUCT_EXPERIENCE_V2_DECISIONS.md)

**Mevcut:** Proje, klasör, tanım, sürüm, bağlantı revizyonu, şema eşlemesi ve manuel çalıştırma temelleri bulunuyor. Ancak shell eski navigasyonu, genel bakış operasyon kartlarını, prosedür editörü bütün adımların açık olduğu uzun formu, operasyon ekranları ise ağırlıklı olarak UUID ve olay listesini kullanıyor. Dolayısıyla ihtiyaç sıfırdan ekran yazmak değil; mevcut domain modelini daha anlaşılır bir çalışma yüzeyine dönüştürmektir. [R11 · AppShell.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/AppShell.tsx) [R12 · ProjectOverviewPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/projects/ProjectOverviewPage.tsx) [R13 · DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx) [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R18 · RunsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunsPage.tsx) [R19 · RunDetailPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunDetailPage.tsx)

**Kesin önerilen beş karar:**

1. **Proje kapısı ve üç çalışma alanı:** Proje adı/genel bakış üst başlıkta; ana navigasyonda yalnız Development / Operations / Connections. Proje ayarları, içe/dışa aktarma ve yönetim ikincil menüde.
2. **Bağlantı kataloğu ve anlaşılır çözümleme:** Provider → bağlantı → fiziksel şema ağacı; bağlantı sürümleri detay panelinde; mantıksal şema/ortam eşlemeleri ayrı ilişkisel görünümde. Secret referansları normal UI’dan kaldırılır, backend güvenlik mekanizması korunur.
3. **Prosedürde seçili adım odaklı master–detail:** Solda sıralı adımlar, sağda yalnız seçili adımın rolü, işlemi, mantıksal bağlamı, çözümlenen fiziksel hedefi ve SQL’i. Runtime V1’in gerçek sınırları editörün ön kontrolünde görünür olur.
4. **Nesne bağlamında Çalıştırılabilir Sürüm:** Publication/release backend’de kalır. Kullanıcı UUID yapıştırarak yayın oluşturmaz; nesne sürümünü ve ortamı seçer, hazırlık sonuçlarını görür, yetkisi ve çalışma yeteneği uygunsa çalıştırır.
5. **Typed operasyon ağacı ve erişilebilir ortak bileşenler:** Nesne → run → sıralı adım ağacı backend projection’dan beslenir. Ham event JSON’u ağacın veri modeli olmaz. Erişilebilirlik, dil ve tema düzeltmeleri sonraya bırakılmadan aynı bileşen sözleşmesinin parçası olur.

### 1.1 Tasarımdan önce kapatılması gereken uyumsuzluklar

En kritik bulgu, tanımın kaydedilebilmesi ile runtime’da çalışabilmesinin aynı sınırlara sahip olmamasıdır. Prosedür editörünün yeni rowset varsayılanı **10.000 satır**, runtime üst sınırı **1.000 satırdır**. Tanım doğrulayıcısı en fazla **10.000 adımı**, runtime **1.000 adımı** kabul eder. Tanım timeout sınırı **3.600 saniye**, runtime sınırı **300 saniyedir**. Bunlar çalıştırılmış hata testi değil, kaynak kodunda doğrulanmış sözleşme farklarıdır. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R28 · ProcedureRuntimePlan.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlan.java) [R29 · ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java) [R30 · DefinitionContentValidator.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/metadata/DefinitionContentValidator.java)

Runtime ayrıca kaynak rowset üreticisinin hemen arkasında tüketicisini ister. Kaynak ve hedef arasında başka bir adım bulunması bile yürütme planını geçersiz kılabilir. Editörde yalnız “kaynak daha önce geliyor” denetimi yeterli değildir. Sıralama değiştirilirken bağı sessizce kaldırmak yerine geçersiz hareket reddedilmeli ve gerekçesi açıklanmalıdır. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R29 · ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java)

**Öncelik:** Önce veri kaybını ve yanıltıcı yetenek sunumunu önleyen düzeltmeler; ardından proje/shell ve erişilebilir temel bileşenler; sonra bağlantı/çözümleme ve çalıştırılabilir sürüm API’leri; en son bu API’lerle beslenen gelişmiş editör ve operasyon ağacı. Bölüm 18’de tek bir uygulama sırası verilmiştir.

### 1.2 Okuma anahtarı ve kapsam

**Araştırma kesim ve erişim tarihi: 12 Eylül 2026.** Tam kaynak incelemesi `9cf018ddcdfdef0c9878ce42401bdac648eb1f41` commit’ine sabitlenmiştir. Teslim öncesi `main` kontrolünde görülen `05cffdcf2170c81eb43ee19115f059d557ad50e2` commit’ine kadar beş yeni commit’in dosya farkı incelenmiş; UI/API kararlarını etkileyen eklemeler bölüm 2.6 ve ilgili sözleşmelere işlenmiştir. **Mevcut**, aksi belirtilmedikçe başlangıç commit’inde kaynak veya repository belgesiyle gösterilen, son fark kontrolünde değişmediği görülen durumu; **Hedef**, önerilen ürün sözleşmesini; **Boşluk**, ikisi arasındaki farkı anlatır. **Çıkarım**, kanıttan yapılan değerlendirmedir. **VARSAYIM**, sağlanmamış bilgiyi; **PROTOTİPLE DOĞRULANACAK**, ölçüm gerektiren tasarım hedefini; **DOĞRULANAMADI**, yeterli kanıt bulunmadığını belirtir.

Bu rapor kaynak kodu incelemesi ve resmi kaynak karşılaştırmasıdır. Çalışan uygulamanın kullanılabilirlik testi, penetrasyon testi, üretim hazırlık onayı veya WCAG uygunluk sertifikası değildir. Wireframe’lerdeki nesne adları ve çalışma bilgileri temsili örneklerdir. Uygulama kodu, veritabanı, migration, `.env`, workflow ve CI/CD ayarları değiştirilmemiştir. Commit/push yapılmamıştır.

## 2. İncelenen commit ve repository mevcut durum envanteri

### 2.1 Kanıt sınırı ve sürüm sabitleme

| Alan | İnceleme sonucu |
|---|---|
| Repository | `mehmet-karacan/akis` |
| Dal | `main` |
| Tam inceleme başlangıç commit’i | `9cf018ddcdfdef0c9878ce42401bdac648eb1f41` |
| Başlangıç commit açıklaması | `docs: catalog ODI repository metadata` |
| Başlangıç commit zamanı | 11 Eylül 2026 21:13:22 UTC; 12 Eylül 2026 00:13:22 Europe/Istanbul |
| Son `main` kontrolü | `05cffdcf2170c81eb43ee19115f059d557ad50e2`; 12 Eylül 2026 00:41:46 Europe/Istanbul; `fix: use Turkish names in Akis baseline` |
| Fark incelemesi | 5 commit, 12 değişen dosya; UI/API açısından ilgili yeni kaynaklar ayrıca okundu. [R40 · İnceleme Başlangıcı ile Son Kontrol Arasındaki Commit Farkı](https://github.com/mehmet-karacan/akis/compare/9cf018ddcdfdef0c9878ce42401bdac648eb1f41...05cffdcf2170c81eb43ee19115f059d557ad50e2) |
| Kaynak erişimi | Commit SHA’sına sabitlenmiş repository dosyaları ve ağaç envanteri |
| Yerel Git durumu | İnceleme ortamında repository çalışma ağacı yoktu; doğrudan Git bağlantısı DNS çözümlemesinde başarısız oldu |
| Çalıştırılamayan doğrulamalar | Repository üzerinde `git status`, `git remote -v`, `git branch --show-current`, kısa log ve `git fetch origin main` |
| Yapılmayan işlem | Reset, checkout, clean, kullanıcı çalışma ağacına yazma, uygulama başlatma, DB erişimi, test çalıştırma |
| Commit kanıtı | [GitHub commit kaydı](https://github.com/mehmet-karacan/akis/commit/9cf018ddcdfdef0c9878ce42401bdac648eb1f41) |

Dosya okumalarının aynı SHA’ya sabitlenmesi, araştırma sırasında `main` değişse bile iki farklı sürümün bulgularının karışmasını önler. Bununla birlikte API üzerinden kaynak okumak, yerel `git fetch` yapılmış olduğu anlamına gelmez. Son dosyada yapılan whitespace kontrolü de repository çalışma ağacı denetiminin yerine geçmiş sayılmaz.

### 2.2 Teknoloji ve uygulama temeli

| Bileşen | Kaynakta görülen sürüm / durum | Sonuç |
|---|---|---|
| Java / Spring Boot | Java 21; Spring Boot 4.1.1 | Backend değiştirilmez. [R21 · Maven Üst Proje Manifesti](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/pom.xml) |
| Oracle JDBC | 23.26.3.0.0 | Bu artifact sürümü, bütün Oracle 12c/19c kombinasyonlarının test edildiği anlamına gelmez. [R21 · Maven Üst Proje Manifesti](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/pom.xml) |
| React / TypeScript / Vite | 19.3.0 / 6.0.3 / 8.3.0 | Mevcut React/TypeScript yapısı korunur. [R20 · Frontend Paket Manifesti](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/package.json) |
| Router | react-router-dom 7.18.3 | Proje boundary ve nesne deep link’leri mevcut router üzerine kurulabilir. [R20 · Frontend Paket Manifesti](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/package.json) |
| Dil | i18next 26.4.2; react-i18next 17.0.13; varsayılan İngilizce | Başlangıç davranışı korunur; kapsam ve metin tutarlılığı tamamlanır. [R20 · Frontend Paket Manifesti](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/package.json) [R24 · Dil Başlatma ve Tercihi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/core/i18n/index.ts) |
| İkon | lucide-react 1.44.0 | Yeni ikon ailesi eklemek yerine ortak boyut/anlam standardı kurulur. [R20 · Frontend Paket Manifesti](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/package.json) |
| Test | Vitest 5.0.0, Testing Library; giriş/dil test örneği mevcut | Test altyapısı vardır; bu araştırmada test sonucu üretilmemiştir. [R20 · Frontend Paket Manifesti](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/package.json) [R33 · Uygulama Temeli Testleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/App.test.tsx) |
| Metadata DB | PostgreSQL; README’de 18.6 belirtiliyor | PostgreSQL’in metadata/control rolü korunur; çalışan DB sürümü sorgulanmadı. [R01 · README](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/README.md) |
| Tema | ThemeProvider ve light/dark token’ları mevcut | Dark tema yeni bir gelecek özellik gibi planlanmaz; mevcut desteğin doğruluğu iyileştirilir. [R22 · Frontend Başlangıcı](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/main.tsx) [R23 · Ortak CSS ve Tema Token’ları](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/styles.css) |
| Profesyonel SQL editörü / sanallaştırma | İncelenen frontend paket manifestinde Monaco, CodeMirror veya ayrı sanallaştırma paketi bulunmuyor | Hedef yetenekler mevcutmuş gibi yazılmaz; yeni bağımlılık ayrı seçim ve test gerektirir. [R20 · Frontend Paket Manifesti](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/package.json) |

### 2.3 Zorunlu okuma envanteri

| Grup | İncelenen dosyalar | Kullanım |
|---|---|---|
| Ürün ve durum | [R01 · README](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/README.md); [R02 · Uygulama Durumu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/IMPLEMENTATION_STATUS.md); [R03 · Ürün Deneyimi V2 Kararları](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PRODUCT_EXPERIENCE_V2_DECISIONS.md); [R04 · Önceki Ürün Deneyimi Belgesi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROFESSIONAL_PRODUCT_EXPERIENCE.md) | Uygulanmış olanı kabul edilmiş hedeften ayırma |
| Domain ve güvenlik sınırları | [R05 · Proje Bundle V1 Sözleşmesi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROJECT_BUNDLE_FORMAT.md); [R06 · Nesne Kataloğu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/NESNE_KATALOGU.md); [R07 · Prosedür Kaynak Ön Kontrolü](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md); [R08 · Manuel Çalıştırma Kontrol Düzlemi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/MANUAL_RUN_CONTROL_PLANE.md) | Bundle, nesne, ön kontrol ve manuel run sözleşmeleri |
| Önceki araştırma | [R09 · Önceki Teknik Araştırma](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/research/ODI_BENZERI_ETL_PLATFORMU_TEKNIK_ARASTIRMA_RAPORU.md) | Mimari bağlam; güncel kodun kanıtı olarak kullanılmadı |
| Shell ve giriş | [R10 · App.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/App.tsx); [R11 · AppShell.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/AppShell.tsx); [R12 · ProjectOverviewPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/projects/ProjectOverviewPage.tsx) | Route, proje bağlamı ve overview |
| Geliştirme | [R13 · DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx); [R14 · ProjectExplorer.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProjectExplorer.tsx); [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) | Klasör ağacı, draft/version ve adım editörü |
| Bağlantılar | [R16 · TopologyPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/TopologyPage.tsx) | Provider, bağlantı, schema, binding ve katalog yüzeyi |
| Operasyon | [R17 · PublicationsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/operations/PublicationsPage.tsx); [R18 · RunsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunsPage.tsx); [R19 · RunDetailPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunDetailPage.tsx) | Publication, run listesi ve olay detayı |

Ek olarak paket manifestleri, ortak API istemcisi, dil başlangıcı, ortak CSS, Oracle bağlantı formu/API tipleri, runtime planı, resolver’ın doğrulama bölümleri, tanım doğrulayıcısı, execution controller/feature flags ve bir frontend test dosyası incelendi. Bu çalışma repository’nin bütün dosyalarının satır satır güvenlik incelemesi değildir. Ek dosyalar kaynakçada ayrı gösterilmiştir.

### 2.4 Mevcut → hedef → boşluk bulguları

P0: Veri/işlem güvenliği açısından ilk düzeltme; P1: ana akış veya erişilebilirlik engeli; P2: kalite/ölçek iyileştirmesi. Öncelikler ölçülmüş olay sıklığı değil, bu raporun risk değerlendirmesidir.

| Kimlik | Mevcut ve kanıt | Hedef / boşluk | Öncelik |
|---|---|---|---|
| F01 | `AppShell` eski çalışma gruplarını ve Publications menüsünü içeriyor. [R11 · AppShell.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/AppShell.tsx) | Üç çalışma alanı; release nesne bağlamına taşınmalı | P1 |
| F02 | `ProjectOverviewPage` run listesi, metrikler ve yoğun giriş kartları yüklüyor. [R12 · ProjectOverviewPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/projects/ProjectOverviewPage.tsx) | Sade giriş; gereksiz run isteği de kaldırılmalı | P1 |
| F03 | Proje değiştirici proje listesine bağlantı; shell proje verisi state’te tutuluyor. [R10 · App.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/App.tsx) [R11 · AppShell.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/AppShell.tsx) | Yetki kontrollü son proje, aramalı seçici, proje değişiminde eski state’in temizlenmesi | P1 |
| F04 | Explorer tüm klasörleri açık başlatıyor; klasör prop’u yenilendiğinde tüm UUID’leri expanded kümesine ekliyor. [R14 · ProjectExplorer.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProjectExplorer.tsx) | Kullanıcının kapattığı dal veri yenilemesinde yeniden açılmamalı | P2 |
| F05 | Prosedür düzeninde adım kartları aynı anda açılıyor. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) | Tek seçili adım editörü; liste/editor ayrımı | P1 |
| F06 | Rowset varsayılanı 10.000; runtime 1.000; timeout ve adım sayısı sınırları da farklı. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R28 · ProcedureRuntimePlan.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlan.java) [R30 · DefinitionContentValidator.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/metadata/DefinitionContentValidator.java) | Tek capability sözleşmesi; draft ile runnable ayrımı | P0 |
| F07 | `normalizeTasks` geçersizleşen input bağını silebiliyor. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) | Sıralama işleminde sessiz semantik değişiklik olmamalı | P0 |
| F08 | UI geniş SQL/PLSQL türleri sunuyor; bağlı runtime komutları çok daha dar. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R29 · ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java) | Desteklenen şablon ve sınıra göre işlem seçimi | P0 |
| F09 | Bağlantılar yüklemesi secret referanslarını da normal kaynak kümesine alıyor; form referans seçtiriyor. [R16 · TopologyPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/TopologyPage.tsx) [R26 · Oracle Bağlantı Sürümü Formu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/OracleConnectionVersionForm.tsx) | Secret yönetimi backend’de kalmalı; kullanıcı referans girmemeli. Bu bulgu parola değeri sızdığı iddiası değildir | P1 |
| F10 | `executableVersions` seçiminde JDBC modu esas alınıyor; lifecycle ve runtimeCapability alanları API’de ayrıca mevcut. [R16 · TopologyPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/TopologyPage.tsx) [R27 · Bağlantılar API İstemcisi ve Tipleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts) | Mode, ACTIVE durumu, capability ve yetki birlikte değerlendirilmeli | P1 |
| F11 | Publications ekranı scenario/environment UUID girişi istiyor. [R17 · PublicationsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/operations/PublicationsPage.tsx) | Nesne/sürüm/ortam seçimi ve backend hazırlama cephesi | P1 |
| F12 | Runs ve RunDetail listeleri UUID ve event verisiyle çalışıyor; ExecutionController typed step endpoint sunmuyor. [R18 · RunsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunsPage.tsx) [R19 · RunDetailPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunDetailPage.tsx) [R31 · ExecutionController.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java) | Nesne adları, typed step projection, action availability | P1 |
| F13 | `ProblemDetails` istemci tipi code/correlationId içeriyor, alan/adım bazlı violations içermiyor. [R25 · Ortak API İstemcisi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/core/api/client.ts) | Hata pointer’ları ve önerilen düzeltme eylemleri | P1 |
| F14 | JSON görsel editöre alternatif normal yüzey; bazı nesnelerde ana düzenleme yolu. [R13 · DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx) | Structured form ve Advanced ayrımı | P1 |
| F15 | Modal/dialog rolleri ve bazı klavye eylemleri var; incelenen bileşenlerde ortak odak yönetimi sözleşmesi yok. [R13 · DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx) [R16 · TopologyPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/TopologyPage.tsx) | Focus trap, geri odak, roving focus ve ortak primitives; manuel AT testi gerekir | P1 |
| F16 | Dark ana buton beyaz metin ile `#49B9AD` kullanıyor. [R23 · Ortak CSS ve Tema Token’ları](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/styles.css) | Hesaplanan kontrast 2,38:1; normal metin için 4,5:1 hedefi karşılanmıyor. Tam ekran WCAG hükmü değil, CSS renk çifti bulgusudur. [S20 · Understanding 1.4.3: Contrast (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) | P1 |
| F17 | Bazı ekranlarda ham enum, İngilizce sabit metin ve sabit tarih locale’i var. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R16 · TopologyPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/TopologyPage.tsx) [R19 · RunDetailPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunDetailPage.tsx) | Tek sözlük, locale’e uygun sunum, canonical kısaltmalar | P2 |

### 2.5 Belgeler arasındaki öncelik ve uyumluluk

`PRODUCT_EXPERIENCE_V2_DECISIONS.md`, önceki ürün deneyimi belgesini günceller. Eski teknik araştırmadaki uzun sidebar, overview kartları veya secret referansını gösteren wireframe’ler yeni tasarıma taşınmaz. Eski raporun önerdiği bir teknoloji ya da gelecek API, paket manifestinde/controller’da bulunmadıkça “Mevcut” sayılmaz. [R03 · Ürün Deneyimi V2 Kararları](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PRODUCT_EXPERIENCE_V2_DECISIONS.md) [R04 · Önceki Ürün Deneyimi Belgesi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROFESSIONAL_PRODUCT_EXPERIENCE.md) [R09 · Önceki Teknik Araştırma](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/research/ODI_BENZERI_ETL_PLATFORMU_TEKNIK_ARASTIRMA_RAPORU.md)

README’nin “worker/poller kapalı” anlatımı ile güncel durum belgesinin kontrollü prosedür runtime açıklaması tek başına aynı olgunluğu ifade etmiyor. Kodda manuel kabul, worker ve prosedür runtime için **varsayılanı false** olan feature flag’ler doğrulandı. **Sonuç:** Uygulanmış runtime kodu vardır; bu, bu kurulumda yürütmenin açık veya üretim kullanımının onaylı olduğu anlamına gelmez. UI bunu sunucu readiness verisinden öğrenmelidir. [R01 · README](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/README.md) [R02 · Uygulama Durumu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/IMPLEMENTATION_STATUS.md) [R32 · ExecutionFeatureFlags.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionFeatureFlags.java)

İnceleme başlangıcındaki ODI envanteri, **ODI 12c repository metadata’sının Oracle Database 19c üzerinde** toplandığını bildiriyor. Bu, Oracle Database 12.1/12.2 runtime uyumluluğunun testi değildir. Ham yerel envanter dosyaları yerine repository’deki envanter açıklaması incelendi. Şema envanteri, gerçek ODI proje tanımlarının ve çalışma geçmişinin tamamı gibi yorumlanmamalıdır. [R34 · ODI 12c Metadata Envanter Belgesi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/research/ODI12C_REPOSITORY_METADATA_INVENTORY.md)

### 2.6 Araştırma sırasında eklenen commit’ler ve tasarıma etkisi

Son `main` kontrolünde **`05cffdcf2170c81eb43ee19115f059d557ad50e2`** görüldü. Başlangıç commit’ine göre beş commit ve 12 dosya değişikliği vardır. App/AppShell, genel bakış, DefinitionsWorkspace, ProcedureEditor, RunsPage/RunDetailPage, ExecutionController ve ProcedureRuntimePlan/Resolver bu fark listesinde değişmemiştir. Bu nedenle yukarıdaki ana UI ve runtime sınırı bulguları geçerliliğini korur. Yeni migration dosyaları çalıştırılmadı; bu bölüm yalnız değişiklik envanterini ve ilgili kaynak/belge okumalarını kapsar. [R40 · İnceleme Başlangıcı ile Son Kontrol Arasındaki Commit Farkı](https://github.com/mehmet-karacan/akis/compare/9cf018ddcdfdef0c9878ce42401bdac648eb1f41...05cffdcf2170c81eb43ee19115f059d557ad50e2)

| Yeni kanıt | Mevcut durum | Hedef tasarıma etkisi |
|---|---|---|
| `OracleColumnCapability.java` | Keşfedilen kolonlarda canonical tip ile `TRANSFER_SUPPORTED`, `CATALOG_ONLY`, `UNSUPPORTED` ayrımı yapılır. Örneğin DATE ve CLOB katalogda temsil edilir fakat aktarım desteği verilmez; UNKNOWN ayrı kalır. [R36 · Yeni Oracle Kolon Yeteneği Sınıflandırması](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/backend/src/main/java/tr/com/innova/akis/oracle/OracleColumnCapability.java) | Kolonu katalogdan saklamak yerine neden yalnız katalogda olduğunu açıklayan rozet/yardım kullan. Bütün tabloyu veya prosedürü yalnız bu rozetle çalıştırılabilir sayma. |
| `DiscoveryColumn` tipi | Frontend API tipine `canonicalType` ve `executionCapability` eklenmiştir. [R37 · Güncel DiscoveryColumn API Tipi](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/frontend/src/features/topology/api.ts) | Bu iki alanın backend’de eksik olduğunu söylemek artık doğru değildir. Yeni katalog sunumu mevcut alanları kullanmalı; genel C05 runtime readiness/işlem/limit sözleşmesiyle karıştırmamalıdır. |
| Temiz `akis` baseline | İlk grup kimlik, yetki ve proje üyeliğidir. Eski `entegrasyon` şeması geçiş sırasında korunur; tüm uygulama gruplarının taşındığı belirtilmez. [R38 · Akış Temiz Başlangıç Şeması](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/database/akis-baseline/README.md) | UI’nın DB fiziksel tablo adlarına veya eski şemaya bağımlı sorgu kurması önerilmez. API sınırı ve cross-project yetki testleri korunur. |
| Database Rebase Audit | Belgede temiz baseline yönü kabul edilmiş, geçiş devam ediyor ve yıkıcı cutover yapılmadığı belirtiliyor. Schema feature çalışmasının dondurulması, mevcut verinin korunması ve API/repository’lerin grup grup taşınması isteniyor. Bunlar bu araştırmada DB’den yeniden ölçülmüş bulgular değildir. [R39 · Veritabanı Yeniden Temellendirme Kararı](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/docs/architecture/DATABASE_REBASE_AUDIT.md) | API gerektiren B işleri eski şemaya yeni paralel özellikler eklememeli; ilgili domain’in yeni baseline sözleşmesiyle koordineli yürümeli. Araştırma, migration veya veritabanı silme onayı vermez. |

**Uygulama planına ek kapı:** A listesindeki backend değiştirmeyen UI düzeltmeleri mevcut API ile yapılabilir. B01–B10 için ilgili domain’in temiz baseline/API geçiş sınırı belirlenmeden kalıcı şema değişikliği başlatılmaz. Yeniden temellendirme bu UI araştırmasının yeni bir işi olarak eklenmemiştir; mevcut ayrı çalışmanın bağımlılığıdır. Eski immutable sürümler, çalışma/onay kanıtları ve kullanıcı verisi korunur. [R38 · Akış Temiz Başlangıç Şeması](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/database/akis-baseline/README.md) [R39 · Veritabanı Yeniden Temellendirme Kararı](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/docs/architecture/DATABASE_REBASE_AUDIT.md)

## 3. Kullanıcı rolleri, ana görevler ve kritik kullanıcı akışları

### 3.1 Roller

Aşağıdakiler kullanıcı görüşmesinden çıkarılmış persona’lar değil, ürün görevlerine dayalı **VARSAYIM** niteliğinde rol gruplarıdır. Aynı kişi birden çok grupta olabilir. Görünür eylemler rol adına değil, backend’in proje ve eylem bazlı yetkilerine bağlanır.

| Rol | Ana görev | Başarı ölçüsü | Özellikle korunacak sınır |
|---|---|---|---|
| Veri geliştirici | Kaynak/hedef bağlarını seçmek; prosedür, mapping ve paket tanımlamak | Doğru nesneyi bulur; neden çalışmadığını kod okumadan anlar | Tanım düzenleme yetkisi production çalıştırma yetkisi değildir |
| Operatör | Çalıştırmayı izlemek; hata/adım/sonuç kanıtını değerlendirmek | Hangi adımın ne durumda olduğunu, güvenli sonraki eylemi bulur | Belirsiz sonucu başarısız sayıp kör yeniden denemez |
| Bağlantı yöneticisi / DBA | Bağlantı revizyonu, test, aktivasyon, fiziksel/mantıksal bağ yönetimi | Değişikliğin hangi sürümleri etkilediğini anlar | Secret okuma veya geniş DB ayrıcalığı otomatik verilmez |
| Proje yöneticisi / gözlemci | Üyelik, proje bilgisi, onay veya salt okunur inceleme | Yetkili olduğu kapsamı açıkça görür | Başka proje isimleri/sayıları yan kanallardan açığa çıkmaz |

Yeni Database Rebase Audit belgesi geliştirici, operasyon, yayın onaylayıcı, görüntüleyici ve proje yöneticisini ayrı yerleşik roller olarak önerir. Yukarıdaki görev grupları bu rol adlarının yerine geçen authorization modeli değildir; özellikle onaylayan kişinin geliştiriciden ayrılması backend sözleşmesinde korunur. [R39 · Veritabanı Yeniden Temellendirme Kararı](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/docs/architecture/DATABASE_REBASE_AUDIT.md)

### 3.2 Kritik akışlar

**A — Giriş ve bağlam:** Giriş → hedef deep link varsa o projenin yetki kontrolü → yoksa son erişilebilir proje → yoksa proje kapısı. Son proje localStorage kaydı otorite değildir. Yetkisi kaldırılan bir proje yeniden açılmaz; erişim hatası ile “henüz projen yok” farklıdır.

**B — İlk bağlantı:** Bağlantılar → Oracle → Yeni Bağlantı → uç nokta ve yönetilen kimlik bilgisi yazma → taslak revizyon → bağlantı testi → açık aktivasyon → fiziksel şema → mantıksal şema/ortam eşlemesi. Test etmek, aktive etmek ve keşif yapmak ayrı eylemdir. Mevcut lifecycle API’leri bu ayrım için kullanılabilir; parola yazma yüzeyi ise ek backend sözleşmesi gerektirir. [R26 · Oracle Bağlantı Sürümü Formu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/OracleConnectionVersionForm.tsx) [R27 · Bağlantılar API İstemcisi ve Tipleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts)

**C — Prosedür:** Geliştirme → klasör → Prosedür Oluştur → Kaynak Veriyi Oku / Hedefe Yaz çiftini oluştur → katalog bağı seç → açık kolonlu SQL’i düzenle → Doğrula → Taslağı Kaydet → sürüm oluştur → ortam için Çalıştırılabilir Sürüm Hazırla → hazırlık/onay sonuçlarını incele → Çalıştır. Runtime V1’de üretici/tüketici ardışıklığı akış boyunca korunur. [R29 · ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java)

**D — Olay inceleme:** Operasyonlar → nesne → çalışma → başarısız veya belirsiz adım → anlaşılır hata/kanıt özeti → ilişkili olaylar → sunucunun izin verdiği eylem. Başka bir nesne veya güncel taslak, geçmiş run’ın bağlamını değiştirmez.

**E — Bağlantı rotasyonu:** Yeni revizyon oluştur → test et → aktive et → etkilenen ortam bağlarını kontrollü güncelle → eski çalıştırılabilir sürümün pinned bağlarını incele → gerekiyorsa yeni sürüm hazırla. Aktif bağlantının değişmesi, geçmiş release’in sessizce başka bağlantıyla çalışması anlamına gelmez.

**F — İçe aktarma:** Proje menüsü → Yeni Proje Olarak İçe Aktar → dosya doğrula → dry-run ve çakışma sonucu → açık onay → yeni proje. Bundle V1’in mevcut projeye merge yapmadığı baştan belirtilir. [R05 · Proje Bundle V1 Sözleşmesi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROJECT_BUNDLE_FORMAT.md)

### 3.3 Kullanılabilirlik araştırması kabulü

**PROTOTİPLE DOĞRULANACAK:** En az iki geliştirici, iki operatör ve bir bağlantı yöneticisiyle görev bazlı değerlendirme önerilir; bu araştırmada görüşme yapılmamıştır. Kritik görevler A–F; ölçümler yardım almadan tamamlama, yanlış ortam seçimi, kayıp draft, ilk hata nedenini bulma ve klavye ile tamamlama olmalıdır. Hedef: kritik güvenlik hatası sıfır; görevlerin en az %90’ının yardım almadan tamamlanması. Küçük örneklem istatistiksel ürün başarısı kanıtı değil, tasarım kusuru keşif kapısıdır.

## 4. Rakip ve benzer ürün karşılaştırma matrisi

Bu bölümde “doğrulanan desen” resmi dokümantasyona; “aktarılacak/kaçınılacak” sütunları Akış için **tasarım değerlendirmesine** dayanır. Ürünler çalıştırılarak benchmark yapılmadı. Lisanslı ürün ekranı, logo veya kod kopyalama önerilmez; soyut etkileşim desenleri Akış’ın kendi component/token sistemiyle uygulanır.

| Ürün / kaynak | Doğrulanan desen ve kapsam | Akış’a aktarılacak | Kaçınılacak / bağımsız tasarım sonucu |
|---|---|---|---|
| ODI 12c / 14c | 12c mapping’in mantıksal ve fiziksel tasarım ayrımı; 14.1.2 Studio’da Designer, Operator, Topology, Security navigator’ları. [S01 · ODI 12c: Creating and Using Mappings](https://docs.oracle.com/middleware/1221/odi/develop/mappings.htm) [S02 · ODI 14.1.2: User Interface](https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/14.1.2/quick-ref/oracle-data-integrator-user-interface.html) | Tanım/işletim ayrımı; mantıksal bağların fiziksel ortama çözülmesi | Dört navigator’ı ve tüm yönetim kavramlarını aynen taşımak yok; Akış’ın üç çalışma alanı korunur |
| Informatica Cloud Data Integration | Explore sayfasında proje/klasör/asset; tür, etiket ve arama; yetkiye bağlı eylemler. Sayfa güncellemesi Mayıs 2025 olarak görünüyor. [S03 · Cloud Data Integration: Explore Page](https://docs.informatica.com/integration-cloud/data-integration/current-version/introduction/data-integration-tools/explore-page.html) | Kalıcı nesne ağacı + sonuç listesi; nesne bağlam menüsü | IDMC’nin tüm servis navigasyonu ve çok sayıda sütunu kopyalanmaz |
| Azure Data Factory | Pipeline run’dan activity run’a geçiş; filtreler ve ayrıntılı izleme. [S04 · Azure Data Factory: Visually Monitor Pipelines](https://learn.microsoft.com/en-us/azure/data-factory/monitor-visually) | Run → adım drill-down ve ayrı detay bölgesi | ADF’deki yeniden çalıştırma yetenekleri Akış’ta hazır sayılmaz; backend eylem uygunluğu gerekir |
| Apache Airflow | DAG/run/task ayrımı; grid ve seçili çalışma/görev ayrıntıları. [S05 · Airflow: UI / Screenshots](https://airflow.apache.org/docs/apache-airflow/stable/ui.html) | Hiyerarşik seçim; geçmiş çalışmanın kendi bağlamını koruma | Bütün run’ları dev matrisle göstermek, mark-success/clear gibi eylemleri aktarmak yok |
| Dagster | Nesneye bağlı run geçmişi; run detayında süre, hata ve log; structured/raw log ayrımı. Bazı özellikler Dagster+’a özgü. [S06 · Dagster Webserver and UI](https://docs.dagster.io/guides/operate/webserver) | Nesne kimliği ile çalışma örneğini ayırma; olay ve teknik log katmanları | Tüm nesneleri “asset” diye yeniden adlandırmak veya genel dashboard’u kopyalamak yok |
| Prefect 3 | State name/type ayrımı; cancelling/cancelled, failed/crashed gibi farklı anlamlar. [S07 · Prefect 3: States](https://docs.prefect.io/v3/concepts/states) | Sonuç ile işlem evresini açık anlatma; iptal isteği ile iptalin tamamlanmasını ayırma | Prefect enum’ları Akış’a yapıştırılmaz; mevcut canonical state eşlemesi korunur |
| Apache NiFi | Process group hiyerarşisi, breadcrumb, nesneye bağlı provenance araması ve detay yetkileri. [S08 · NiFi User Guide](https://nifi.apache.org/docs/nifi-docs/html/user-guide.html) | Büyük akışı parçalara ayırma; olay ayrıntısına nesne bağlamından erişim | Sürekli akış kuyruğu/canvas/provenance replay modelini Akış’ın batch runtime’ı gibi göstermemek |
| dbt Studio IDE | Dosya explorer, editör, compile/preview, command history ve ilişkili DAG görünümü. [S09 · About the Studio IDE](https://docs.getdbt.com/docs/platform/studio-ide/develop-in-studio) | Editör ile çıktı/diagnostic alanını ayırma; dosya/nesne bağlamını kaybetmeden inceleme | dbt’nin Git-write ön koşulu veya CI akışları Akış’a taşınmaz; serbest komut terminali eklenmez |
| GitHub Primer | TreeView, açılır/kapanır ebeveyn–çocuk listesi olarak tanımlanıyor. [S10 · Primer: TreeView](https://primer.style/product/components/tree-view/) | Nesne ağacında tutarlı selection, disclosure ve satır düzeni | GitHub görsel kimliği veya kaynak dosya modeli domain otoritesi yapılmaz |
| IBM Carbon | Tree, hiyerarşik içerik için; ana ürün navigasyonunun yerine önerilmiyor. Klavye/odak davranışı ayrıca tanımlı. [S11 · Carbon: Tree View Usage](https://carbondesignsystem.com/components/tree-view/usage/) [S12 · Carbon: Tree View Accessibility](https://carbondesignsystem.com/components/tree-view/accessibility/) | Ana çalışma alanı navigasyonu ile proje ağacını ayrı component yapmak | Sidebar’ın tamamını tek karmaşık tree’ye dönüştürmemek |
| Material Design 3 | Google’ın resmi Compose açıklamasında renk/on-color çiftleri, tipografi ve shape alt sistemleri var. [S13 · Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) | Semantik token ve light/dark foreground/background eşleşmesi | Android ölçülerini web standardı saymamak; geniş renkli yüzeyleri ve hareketli temayı kopyalamamak |
| W3C WAI / WCAG | Tree, treegrid, tabs, dialog ve combobox farklı etkileşim sözleşmeleri; WCAG 2.2 erişilebilirlik kriterleri. [S14 · Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) [S15 · APG: Tree View Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/) [S16 · APG: Treegrid Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treegrid/) [S17 · APG: Tabs Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/) [S18 · APG: Dialog (Modal) Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/) [S19 · APG: Combobox Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/) | Component davranışını baştan bu sözleşmelerle tasarlamak | Yalnız ARIA rolü eklemeyi erişilebilirlik tamamlandı saymamak |

**Kaynak sınırı:** Material 3 ana sitesinin içerik sayfası JavaScript zorunluluğu nedeniyle okunamadı; M3 karşılaştırması Google’ın resmi Android/Compose açıklamasına dayanıyor. Web breakpoint önerileri bu kaynaktan kopyalanmış platform kuralları değildir. `stable` ve `current-version` URL’leri erişim tarihindeki dokümantasyon kapsamını gösterir; her müşterinin kurulu ürün sürümünü veya lisansını kanıtlamaz. Görsel ekran görüntüsüne dayanarak doğrulanmamış bir ürün özelliği çıkarılmadı.

## 5. Hedef bilgi mimarisi ve route haritası

### 5.1 Çalışma alanlarının sınırı

**Geliştirme:** Proje klasörleri, tasarım nesneleri, sürümler, bağlar, doğrulama ve nesneye ait çalıştırılabilir sürümler. **Operasyonlar:** Nesneye göre çalışma geçmişi, run/adım/olay detayları ve izinli işletim eylemleri. **Bağlantılar:** Provider, bağlantı revizyonu, fiziksel şema, mantıksal şema, ortam eşlemesi ve veri kataloğu.

Genel bakış üstteki proje adından açılır; dördüncü bir ana çalışma alanı değildir. Proje ayarları ve üyelik proje menüsündedir. Sistem kimlik yönetimi yalnız ilgili yetkisi olan kişinin kullanıcı/yönetim menüsünde bulunur. Publications/Yayınlar ana navigasyondan kalkar; backend kaydı ve yetki denetimi kalır.

### 5.2 Önerilen route ağacı

Aşağıdaki yollar **Hedef** frontend sözleşmesidir; bugün çalıştıkları iddia edilmez. `:p`, `:o`, `:r`, `:s` gibi değerler kullanıcıya yazdırılmayan, kararlı kimliklerdir.

```text
/login
/projects                                  Proje kapısı
/projects/import                           Yeni proje olarak bundle import
/projects/:p                               Sade genel bakış
/projects/:p/development
/projects/:p/development/folders/:folder
/projects/:p/development/objects/:o
/projects/:p/development/objects/:o/versions/:version
/projects/:p/development/objects/:o/runnable-versions
/projects/:p/development/objects/:o/runnable-versions/:release
/projects/:p/operations
/projects/:p/operations/runs/:r
/projects/:p/operations/runs/:r/steps/:s
/projects/:p/connections
/projects/:p/connections/providers/:provider
/projects/:p/connections/:connection
/projects/:p/connections/:connection/revisions/:revision
/projects/:p/connections/:connection/schemas/:physical
/projects/:p/connections/logical-schemas/:logical
/projects/:p/connections/environments/:environment/mappings
/projects/:p/connections/catalog/:dataObject
/projects/:p/settings
/projects/:p/settings/members
```

**Router uygulama notu:** `providers`, `logical-schemas`, `environments` ve `catalog` statik segmentleri `:connection` ile karıştırılmamalıdır. Route hiyerarşisi testte gerçek örnek URL’lerle doğrulanır. Nesne adı değişirse UUID ve deep link değişmez. Klasör taşınması geçmiş run bağlantısını bozmaz.

Filtre, seçili tab ve çalışma alanı görünümü query parametrelerinde taşınabilir: `?tab=bindings`, `?environment=...`, `?state=failed`, `?from=...&to=...`. Secret, SQL metni, kullanıcı parolası veya idempotency key URL’ye yazılmaz. Draft gövdesi route state/localStorage yedeği olarak otomatik saklanmaz.

### 5.3 Eski URL geçişi

| Mevcut yol | Hedef davranış |
|---|---|
| `/projects/:p/topology` | `/projects/:p/connections`; seçili bağlantı biliniyorsa detay korunur |
| `/projects/:p/models` | Connections içindeki katalog görünümü |
| `/projects/:p/definitions?definition=:o` | `/projects/:p/development/objects/:o`; kimlik varsa aynen korunur |
| `/projects/:p/runs/:r` | `/projects/:p/operations/runs/:r` |
| `/projects/:p/publications/:release` | Nesne–release bağı çözülebiliyorsa nesne içi runnable-version; değilse salt okunur gelişmiş eski release detayı |

Eski publication bağlantısı kör biçimde genel bakışa gönderilmez; kullanıcının incelemek istediği kayıt kaybolmamalıdır. Bu yolların mevcut karşılıkları `App.tsx` içinde doğrulanmıştır. [R10 · App.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/App.tsx)

### 5.4 Arama, breadcrumb ve ağaç sürekliliği

Development explorer proje içinde kalıcıdır; nesne değişince ağaç seçimi ve açık dallar korunur. Operations’ta bunun yerine execution tree; Connections’ta provider/schema ağacı görünür. Üç ağaç aynı anda yan yana açılmaz. Breadcrumb görünen nesne adlarından oluşur; adları API’den çözmek başarısızsa ham UUID yerine “Nesne Bilgisi Yüklenemedi” ve yeniden deneme sunulur.

Arama varsayılan olarak aktif proje kapsamındadır. Sonuçta ad, tür ve klasör yolu birlikte görünür. Filtreleme, eşleşen yaprakların atalarını gösterir; kullanıcının filtre öncesi expanded state’i filtre temizlenince geri gelir. Favori ve son kullanılanlar yetki tekrar kontrolünden geçer. İlk aşamada cihaz yerel tercihleri yalnız kullanıcı/proje kimliği ve nesne UUID’si taşıyabilir; cihazlar arası senkronizasyon ayrı backend işidir.

**Ölçek hedefi:** Lazy çocuk yükleme + sunucu arama/pagination + istemci sanallaştırma birlikte planlanır. 20.000 nesneyi tek istekte alıp yalnız DOM’u sanallaştırmak tamamlanmış ölçek çözümü sayılmaz. Bu hedefin bugünkü tam liste endpoint’lerine ek API gerektirdiği Bölüm 17’de belirtilmiştir. [R13 · DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx) [R14 · ProjectExplorer.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProjectExplorer.tsx) [R31 · ExecutionController.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java)

## 6. Global shell, proje seçici ve navigasyon sözleşmesi

### 6.1 Shell hiyerarşisi

**Hedef component yapısı:**

```text
AppRouter
└─ AuthBoundary
   ├─ ProjectGatePage
   └─ ProjectBoundary(projectUuid)
      └─ ProjectWorkspaceShell
         ├─ SkipLinks
         ├─ GlobalHeader
         │  ├─ ProjectSwitcher
         │  ├─ WorkspaceSearch
         │  └─ LanguageThemeUserMenu
         ├─ WorkspaceNavigation (3 destinations)
         ├─ Breadcrumb
         ├─ WorkspaceOutlet
         │  ├─ DevelopmentWorkspace + ObjectExplorer
         │  ├─ OperationsWorkspace + ExecutionTree
         │  └─ ConnectionsWorkspace + ProviderSchemaTree
         ├─ PendingChangesGuard
         └─ AccessibleStatusAnnouncer
```

Bu yapı yeni auth/secret mimarisi kurmaz. Mevcut BrowserRouter, AuthProvider ve ThemeProvider korunarak sorumluluklar ayrılır. Sayfada tek ana `main` landmark’ı bulunur; feature sayfaları gereksiz ikinci `main` üretmez. [R10 · App.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/App.tsx) [R11 · AppShell.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/AppShell.tsx) [R22 · Frontend Başlangıcı](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/main.tsx)

### 6.2 Proje sınırı ve eşzamanlı istekler

Proje değişimi onaylandıktan sonra eski projenin başlığı, izinleri, nesne seçimi ve bekleyen sonuçları atomik olarak geçersiz sayılır. Yeni proje doğrulanıncaya kadar eski nesne yeni başlık altında gösterilmez. İstek anahtarları kullanıcı/proje/nesne kimliklerini içerir; AbortController veya monoton request token eski yanıtın yeni state’e yazmasını engeller. Dil değişimi sunumdaki metni yeniler; kirli draft’ı yeniden fetch edip üzerine yazmaz.

Oturum sona ermesi 401, yetki kaybı 403/erişim politikası gereği 404, bağlantı arızası ise ağ hatasıdır. Yetkisi olmayan nesne ile gerçek boş liste aynı bilgi mesajına indirgenmez; fakat yetkisiz kaynağın varlığı da açıklanmaz. Sunucunun güvenli hata politikasına uygun ortak ekran kullanılır.

### 6.3 Kaydedilmemiş değişiklik sözleşmesi

Route, proje ve nesne değişimi için tek guard: **Kaydet ve Devam Et / Değişiklikleri Bırak / Burada Kal**. Kaydetme aynı draft revision/expectedVersion ile yapılır. 409/412 çatışmasında otomatik reload veya overwrite yoktur; karşılaştırma ve güvenli kopyalama seçeneği sunulur. Tarayıcı sekmesi kapanırken tarayıcının standart uyarısı kullanılabilir; özel metnin her tarayıcıda gösterileceği varsayılmaz.

Kaydet, Sürüm Oluştur, Çalıştırılabilir Sürüm Hazırla ve Çalıştır ayrı sonuçlardır. Sadece editör odağının değişmesi veya Ctrl+S, runtime başlatmaz. Dil/tema değişimi kaydedilmemiş SQL’i silmez. İlgili bug riski mevcut workspace yükleme ve draft seçimi akışından kaynaklanır; canlı ortamda tekrar üretilmemiştir. [R13 · DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx)

### 6.4 Proje seçici ve ortam ayrımı

Proje seçici aramalı combobox davranışıyla açılır; mevcut proje işaretli, son kullanılanlar ayrı küçük grup, proje oluşturma ise yetki varsa görünür. Arama içinde gezinme seçimi hemen uygulamaz; Enter veya tıklama seçimi kesinleştirir. Escape değişmeden kapatır. Combobox accessible name ve sonuç sayısı ekran okuyucuya aktarılır. [S19 · APG: Combobox Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/)

Proje ile ortam aynı seçici değildir. Ortam seçimi yalnız bağ çözümleme, sürüm hazırlama veya çalışma başlatma bağlamında görünür. Production hiçbir zaman gizli varsayılan olarak atanmaz. Geçmiş run detayındaki ortam, global seçiciyle değiştirilemeyen sabit çalışma bilgisidir.

## 7. Ekran bazlı ayrıntılı tasarım spesifikasyonları

Bu bölümdeki bütün wireframe’ler **Hedef** tasarımdır; mevcut uygulama ekran görüntüsü değildir. Piksel ölçüleri Bölüm 12’de, ortak durum davranışları Bölüm 15’te, C01–C10 API sözleşmeleri Bölüm 17’de tanımlıdır.

### 7.1 Proje seçme kapısı — WF01

```text
+------------------------------------------------------------------------+
| Akış                                            Language  Theme  User |
+------------------------------------------------------------------------+
| Select a project                                                       |
| Choose the workspace you want to work in.                               |
| [ Search projects by name or code................................. ]   |
|                                                                        |
| Recent                                                                 |
| [ Example Integration          PROJECT_A                 Open -> ]     |
| [ Example Reporting            PROJECT_B                 Open -> ]     |
|                                                                        |
| All accessible projects                         [Sort: Name v]         |
| Project name                 Code                 Your access          |
| Example Integration          PROJECT_A            Developer            |
|                                                                        |
| [Create Project]        [Import as New Project]                         |
+------------------------------------------------------------------------+
```

| Sözleşme | Tasarım |
|---|---|
| Amaç / birincil görev | Erişilebilir bir proje seçmeden proje içi işlem yapılmasını engellemek |
| Bölgeler | Minimal global başlık; arama; son kullanılanlar; bütün erişilebilir projeler; yetkili oluşturma eylemleri |
| Eylemler | Primary: proje satırından Aç veya ilk kullanımda Proje Oluştur. Secondary: Yeni Proje Olarak İçe Aktar. Proje düzenleme bu kapının ana görevi değildir |
| Loading / empty / error / permission | Yüklenirken sabit liste iskeleti; sıfır projede yetkiye göre oluşturma veya erişim isteme; ağ hatasında Yeniden Dene; yetkisiz projenin adı/sayısı gösterilmez |
| Klavye / ekran okuyucu | Arama label’lı input; sonuçlar semantik liste veya tablo; Enter proje açar; seçimi izleyen odak yeni sayfanın h1 başlığına gider; sonuç sayısı polite duyurulur |
| Dar ekran | Tek sütun; secondary metaveri satır altına iner; proje adı erişilebilir tam adıyla korunur |
| Veri | Mevcut proje liste/get uçları; C01 erişim/readiness bilgisi. Kullanıcı tercihi yalnız son proje UUID’sini saklar |
| Kabul | Geçersiz/erişimi kaldırılmış son proje açılmaz; ağ arızası boş proje listesi diye gösterilmez; refresh ve giriş sonrası intended route korunur |

### 7.2 Shell ve proje değiştirici — WF02

```text
+--------------------------------------------------------------------------------+
| Akış | [Example Integration v] | [Search in project...] | EN/TR | Theme | User  |
+-----------------+--------------------------------------------------------------+
| Development     | Project / Folder / Object                                     |
| Operations      +--------------------------------------------------------------+
| Connections     | Workspace explorer | Selected object or operation             |
|                 |                    |                                          |
|                 |                    |                                          |
|                 |                    |                                          |
| [Collapse]      |                    |                                          |
+-----------------+--------------------------------------------------------------+

Project switcher expanded:
+--------------------------------------+
| [Search accessible projects........] |
| Current: Example Integration       ✓ |
| Recent:  Example Reporting           |
| All Projects                        |
| Create Project                      |
+--------------------------------------+
```

**Amaç:** Proje ve çalışma alanı bilgisini her an anlaşılır tutmak. **Birincil görev:** Alan/proje değiştirmek; içerik üretmek shell’in görevi değildir. **Eylemler:** Alan bağlantıları primary navigasyon; proje seçici secondary navigasyon; proje/hesap ayarları overflow. Export üst başlığın primary butonu değildir.

**Durumlar:** C01 beklenirken eski projenin verisi gösterilmez; yalnız global başlık ve yeni bağlam yüklemesi kalır. Proje silinmiş/erişilmezse güvenli kapıya dönülür. Yetki değişiminde açık editör salt okunura alınır; kaydetme sunucuda da reddedilir. **Klavye:** İçeriğe/Ağaca Git skip link’leri; seçici APG combobox; kapandığında açan düğmeye geri odak. **Dar ekran:** Ana navigasyon kompakt menüye, çalışma alanı explorer’ı açılır panele dönüşür; içerik ile iki sidebar aynı anda daraltılmaz. [S19 · APG: Combobox Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/)

**API:** C01 + route bazlı veri. **Kabul:** A projesinin geç gelen isteği B projesine yazılamaz; dil/tema değişikliği draft’ı bozmaz; 320 CSS px genişlikte shell yatay taşmaz; ekran okuyucu yalnız bir `main` algılar.

### 7.3 Sade proje giriş ekranı — WF03

```text
+------------------------------------------------------------------------+
| Example Integration                               [Project Actions ...]|
| Integration definitions and controlled execution.                      |
|                                                                        |
| Continue with Development                                              |
| Create and maintain the objects in this project.                        |
| [Open Development]                                                     |
|                                                                        |
| Connections       Manage connections and schema mappings     Open ->   |
| Operations        Inspect execution history                  Open ->   |
|                                                                        |
| Project settings                                                       |
+------------------------------------------------------------------------+
```

**Amaç/görev:** Projeyi tanıyıp doğru çalışma alanına geçmek. **Bölgeler:** Ad/açıklama, bir temel devam eylemi, iki sade metin bağlantısı. **Eylemler:** Geliştirmeyi Aç primary; Bağlantılar/Operasyonlar secondary; Proje İşlemleri içinde ayarlar ve export. Kart panosu, run sayaçları, son çalışma listesi ve kalıcı “aktif” rozeti yoktur. Dikkat gerektiren gerçek proje durumu varsa tek açıklayıcı banner eklenir.

**Durumlar:** Proje bilgisi loading; açıklama yoksa alan sessizce kaldırılır; hata/permission ortak proje boundary tarafından yönetilir. **Klavye/SR:** Başlık ve bağlantılar standart HTML; grafik veya carousel yok. **Dar ekran:** Aynı tek sütun yapı korunur. **API:** Proje bilgisi ve yetki; `listRuns` çağrısı gerekmez. **Kabul:** Overview açılması operasyon API’sine istek üretmez; export primary değildir; metrik sayısı sıfırdır. Mevcut sayfadan kaldırılacak bölgeler doğrudan component’te görülebilir. [R12 · ProjectOverviewPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/projects/ProjectOverviewPage.tsx)

### 7.4 Development explorer ve nesne çalışma yüzeyi — WF04

```text
+--------------------------------------------------------------------------------+
| Development                     [Search objects...]        [New Object v]     |
+---------------------------+----------------------------------------------------+
| Project Objects       ... | Folder / Customer Integration                     |
| [Filter...]               | Customer Load                         [Actions ...]|
| v Integrations            | Procedure · Draft changed                          |
|   v Customer              | [Design] [Bindings] [Versions] [Runnable Versions] |
|     Interface: Customer   +----------------------------------------------------+
|   > Finance               |                                                    |
| v Procedures              | Selected object's structured editor                |
|   > Customer Load         |                                                    |
| > Packages                |                                                    |
| > Parameters              +----------------------------------------------------+
| [New Folder]              | 2 validation issues      [Validate] [Save Draft]  |
+---------------------------+----------------------------------------------------+
```

**Amaç:** Nesneyi klasör bağlamını kaybetmeden bulmak ve düzenlemek. **Bölgeler:** Tek explorer, breadcrumb/nesne başlığı, nesne tab’ları, editör, küçük diagnostics alanı. **Eylemler:** Kirli draft’ta Taslağı Kaydet primary; Doğrula secondary; Yeni Nesne ve nesne menüsü bağlama göre görünür. Bütün sayfada birbiriyle yarışan Kaydet/Oluştur/Yayınla/Çalıştır primary’leri yoktur.

**Durumlar:** Hiç nesne yoksa seçili klasörde oluşturma; filtre sıfırsa Filtreyi Temizle; çocuk yükleme hatası yalnız o dalda; yetkisiz nesne açılmaz; stale draft çatışması editörü silmez. **Klavye/SR:** Explorer tree davranışı, tab listesinde ok tuşları; nesne seçimi sağ paneli yeniler ama her ok tuşunda odağı panelin içine atmaz. Tab aktivasyonu maliyetli veri yükleyecekse Enter/Space ile manuel aktivasyon tercih edilir. [S15 · APG: Tree View Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/) [S17 · APG: Tabs Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/)

**Dar ekran:** Explorer ayrı açılır panel; nesne editörü tam genişlik; işlem menüsü taşınır. **API:** Bugünkü definition/folder/version uçları; büyük proje için C02; doğrulama için C05. **Kabul:** Nesne rename/move sonrası deep link değişmez; kapalı klasör refresh’te açılmaz; filtre temizlenince önceki ağaç durumu döner; kaydetmeden nesne değiştirme guard’dan geçer. Mevcut explorer’ın UUID ve klasör modeli korunur. [R13 · DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx) [R14 · ProjectExplorer.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProjectExplorer.tsx)

### 7.5 Proje/nesne menüsü ve import-export — WF05

```text
Project Actions                         Object Actions
+-----------------------------------+   +-------------------------------------+
| Project Settings                  |   | Rename                              |
| Manage Members                    |   | Move to Folder                      |
| --------------------------------- |   | Duplicate                           |
| Export Project                    |   | ----------------------------------- |
| Import as New Project             |   | Runnable Versions                   |
| --------------------------------- |   | Advanced                         >  |
| Advanced                        > |   +-------------------------------------+
+-----------------------------------+           +------------------------------+
                                               | View Raw Definition          |
                                               | Copy Technical Identifier    |
                                               +------------------------------+

Import: Select file -> Validate -> Dry-run summary -> Confirm -> New project
```

**Amaç:** Seyrek kullanılan teknik işlemleri normal çalışma yüzeyinden uzaklaştırmak, gizlememek. **Eylemler:** Menüde **Projeyi Dışa Aktar**; import akışında sonucu inceleme sonrası İçe Aktar primary. Bağlam menüsü sağ tıkla ve görünür üç nokta düğmesiyle eşdeğer açılır; yalnız sağ tıkla erişilen işlev olmaz.

**Mevcut sınır:** Bundle V1 proje/klasör/tanım/draft/immutable version taşır; mevcut projeye merge etmez. `FAIL` ve `RENAME` uygulanır; `SKIP` ve `NEW_VERSION` desteklenmez. Nesne bazlı bağımsız paket içe/dışa aktarma mevcut sözleşmede doğrulanmadığından aktif özellik olarak sunulmaz. Menüde gösterilecekse desteklenmediği açıkça belirtilir; sahte bir JSON dosyasına dönüştürülmez. [R05 · Proje Bundle V1 Sözleşmesi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROJECT_BUNDLE_FORMAT.md)

**Durumlar:** Dosya boyutu/format/checksum/secret tespitinde alan veya dosya seviyesinde hata; dry-run isteği açıkça `dryRun=true`; gerçek import açıkça `dryRun=false`; bağlantı hatasında “başarılı” bildirimi yok. Yetkisiz eylem, kaynağın varlığını ifşa etmeden gizlenir veya gerekçeli pasif olur. **Klavye:** Shift+F10/ContextMenu, oklar, Enter, Escape, açan satıra geri odak. **Dar ekran:** Sağ tık yerine aynı menü düğmesi; import adımları tek sütun.

**API:** Mevcut bundle validate/import/export; gelecekte nesne bundle için ayrı C10 genişletmesi. **Kabul:** Export GET cevabında credential değeri veya secret referansı taşınmaz; dry-run write yapmaz; import “bu projeye eklendi” diye yanlış sonuç bildirmez; ham tanım varsayılan salt okunur ve yetki kontrollüdür.

### 7.6 Çalıştırılabilir Sürüm hazırlama ve çalıştırma

**Amaç:** Backend yayın ayrıntılarını kullanıcıya taşıtmadan doğru nesne sürümünü doğru ortam için hazırlamak. **Bölgeler:** Nesne adı + immutable version; ortam seçimi; kaynak/hedef çözümleme özeti; doğrulama/uyumluluk/onay durumu; çalıştırma özeti. Release hash normal yüzeyde tam uzunlukta gösterilmez; teknik ayrıntıda kopyalanabilir.

**Eylemler:** Henüz release yoksa Çalıştırılabilir Sürüm Hazırla; hazır ve izinliyse Çalıştır. Eksik onayda Onaya Gönder ancak gerçek backend akışı varsa görünür. Ön kontrol metadata kontrolü ile gerçek kaynak bağlantısı açan probe olarak ayrı adlandırılır. Kaynak ön kontrolü hedefe veri yazma veya tam run simülasyonu değildir. [R07 · Prosedür Kaynak Ön Kontrolü](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md)

**Durumlar:** `DEFINITION_ONLY`, eksik binding, unsupported shape, kapalı runtime, üretim onayı ve stale snapshot ayrı gerekçelerdir. Hepsine “Yayın başarısız” denmez. **Klavye/SR:** Kontrol sonuçları başlıklandırılmış liste; engelleyici maddeden ilgili alan/adıma git; risk onayında başlangıç odağı güvenli eylemde. **Dar ekran:** Çözümleme özeti tek sütun; hedef/ortam adları kısaltılıp gizlenmez. **API:** C04–C06 ve mevcut manuel run POST. **Kabul:** Hiç UUID yazdırılmaz; exact release/environment/plan hash onaya bağlıdır; double click aynı run isteğini tekrar oluşturmaz; 503 sonrasında kullanıcı yeniden anlamlandırılmayan bir hata döngüsüne girmez. [R17 · PublicationsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/operations/PublicationsPage.tsx) [R18 · RunsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunsPage.tsx) [R31 · ExecutionController.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java)

### 7.7 Proje ayarları ve üyelik

**Amaç:** Proje açıklaması, üyelik ve seyrek yönetim işlemlerini çalışma yüzeyinden ayırmak. **Bölgeler:** Genel / Üyeler / Gelişmiş sekmeleri; permission’a göre içerik. **Eylemler:** Değişikliği Kaydet primary; üye ekleme ilgili sekmede; export gelişmiş veya proje menüsünde. Silme/arşivleme ancak backend destekliyorsa ve etkisi açıklanabiliyorsa görünür.

**Durumlar:** Üye listesi hatası proje bilgisi formunu bozmaz; yetki kaldırılınca düzenleme durur; optimistic lock çatışması ayrı gösterilir. **Klavye/SR:** Label’lı form, tablo başlıkları, hata özeti ve dialog odak yönetimi. **Dar ekran:** Üye eylemleri satır menüsüne taşınır; isim/rol kaybolmaz. **API:** Mevcut proje ve üyelik uçları, C01 izin projection’ı. **Kabul:** Başka çalışma alanına geçince ayar taslağı için guard işler; yönetim menüsü normal geliştiricinin ana navigasyonunu büyütmez. Bu bölüm backend’de yeni kullanıcı yönetim modeli tasarlamaz.

## 8. Bağlantılar, fiziksel ve mantıksal şema deneyimi

### 8.1 Kullanıcıya anlatılacak model

**Bağlantı**, veritabanına erişim tanımıdır. **Revizyon**, bu tanımın immutable yapılandırma sürümüdür. **Fiziksel şema**, veritabanındaki şemadır. **Mantıksal şema**, tanımın ortamdan bağımsız kullandığı addır. **Ortam eşlemesi**, belirli ortamda mantıksal şemanın hangi fiziksel şema ve bağlantı revizyonuna çözüleceğini belirler. Bu ayrım ODI’nin fiziksel/mantıksal tasarım yaklaşımından yararlanır; Akış’ın mevcut API modelinde de farklı kayıtlar olarak bulunmaktadır. [S01 · ODI 12c: Creating and Using Mappings](https://docs.oracle.com/middleware/1221/odi/develop/mappings.htm) [R27 · Bağlantılar API İstemcisi ve Tipleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts)

**Önemli tasarım kararı:** Provider → connection → revision → physical → logical → environment tek bir sahiplik zinciri değildir. Aynı mantıksal şema farklı ortamlarda farklı fiziksel şemalara bağlanır. Bu nedenle revizyonu ve mantıksal eşlemeyi her zaman ağaçta bir sonraki çocuk seviyesine koymak yanlış bir mental model yaratır.

### 8.2 Provider/schema ağacı ve detay — WF06

```text
+--------------------------------------------------------------------------------+
| Connections                       [Search...]              [New Connection]   |
+--------------------------+-----------------------------------------------------+
| Providers                | SKY                                     [Actions...]|
| v Oracle                 | Oracle · Active revision v3                         |
|   v SKY                  | [Overview] [Schemas] [Revisions] [Policies]         |
|     SOURCE_SCHEMA        +-----------------------------------------------------+
|   > GPU                  | Connection test    Passed        [Test Connection] |
|                          | Execution profile  Controlled Oracle V1            |
| Logical Schemas          | Credentials        Configured    [Change...]       |
| Environments             |                                                     |
| Data Catalog             | Physical schemas                                   |
|                          | SOURCE_SCHEMA -> CUSTOMER_DATA                      |
+--------------------------+-----------------------------------------------------+
| Selected mapping: LOGICAL_SOURCE + TEST -> SKY / SOURCE_SCHEMA / revision v3    |
+--------------------------------------------------------------------------------+
```

SKY ve GPU bağlantı takma adlarıdır. `SOURCE_SCHEMA` ve `CUSTOMER_DATA` sentetik gösterimdir; gerçek host, kullanıcı veya schema kimlikleri rapora alınmamıştır.

**Amaç/görev:** Bağlantı kurmak ve çözümleme ilişkisini anlamak. **Bölgeler:** Provider/schema tree; seçili bağlantı özeti; revizyon ve şema sekmeleri; yalnız ilgili bağlamda mapping özeti. **Eylemler:** Provider düzeyinde Yeni Bağlantı; bağlantıda Yeni Revizyon/Test Et; fiziksel şemada Keşfet; eşlemede Eşlemeyi Kaydet. Hepsi aynı anda primary yapılmaz.

**Durumlar:** Provider boşsa ekleme; keşif boşsa “nesne bulunamadı” ile yetki/istek hatası farklı; kısmi keşif `truncated=true` ise sonuç kesildiği belirtilir. Secret listeleme yetkisi eksikliği bütün bağlantılar sayfasını çökertecek tek `Promise.all` bağımlılığı olmamalıdır. [R16 · TopologyPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/TopologyPage.tsx) [R27 · Bağlantılar API İstemcisi ve Tipleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts)

**Klavye/SR:** Tree ve sekme klavyesi; seçili revizyon screen reader açıklamasında; bağlantı test sonucu polite, başarısızlık alanla ilişkili. **Dar ekran:** Tree açılır panel; detail tek sütun; mapping özeti satırlara ayrılır. **API:** Mevcut connection/versions/schema/binding/catalog API; C03 credential/policy; C04 resolution; büyük katalogda C02. **Kabul:** Adım editöründe seçilen logical/environment ile burada gösterilen physical/revision birebir aynı çözülür; JNDI kaydı yürütülebilir JDBC gibi etiketlenmez; test aktivasyon yapmaz.

### 8.3 Bağlantı oluşturma, revision ve timeout

Mevcut Oracle formu JDBC/JNDI, Service Name/SID ve üç adımlı giriş yapısını zaten ayırıyor. JNDI için runtime sınırlaması ve TCPS için planlı/pasif seçenek görünür; bunlar gerçek destek varmış gibi etkinleştirilmemelidir. Bağlantı API’si `DRAFT`, `TESTED`, `ACTIVE`, test sonucu ve `runtimeCapability` alanlarını zaten taşıyor. Bu temeller korunur. [R26 · Oracle Bağlantı Sürümü Formu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/OracleConnectionVersionForm.tsx) [R27 · Bağlantılar API İstemcisi ve Tipleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts)

**Hedef form sırası:** Kimlik → Bağlantı Ayrıntıları → Kimlik Bilgisi → Gözden Geçir. Oracle provider’dan başlatıldığı için veritabanı türü yeniden seçtirilmez. JDBC driver adı kullanıcı girdisi değil yönetilen bilgidir. Service Name ve SID karşılıklı dışlayıcıdır. Test edilmemiş taslak ile aktif revision ayrı görünür. Aktivasyon en son başarılı test ve `expectedStateVersion` üzerinden yapılır; test sonucu stale ise yeniden test istenir.

**Timeout:** Bağlantı politikasında bağlantı kurma, sorgu/statement ve gerekiyorsa ağ okuma sınırları ayrı typed alanlar olmalıdır. Alanlar sunucu provider capability’sine göre sunulur. Her birinin birimi ve devreye girdiği evre açıkça yazılır. Procedure task JSON’una kopyalanmaz. Seçili adımda yalnız “Etkili Sorgu Süre Sınırı: …; Bağlantı Politikası v…” salt okunur gösterilir. Exact resolved değerlerin immutable runtime planında bulunması ise güvenli tekrar üretilebilirlik için doğrudur; tanım payload’ına kopyalama yasağıyla karıştırılmaz.

Bugünkü `executionPolicy: Record<string, never>` tipi ve task üzerindeki timeout birlikte düşünüldüğünde, timeout alanını frontend’den silmek tek başına çözüm değildir. Typed bağlantı politikası, resolver ve eski immutable sürümler için geçiş sözleşmesi gereklidir. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R27 · Bağlantılar API İstemcisi ve Tipleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts) [R28 · ProcedureRuntimePlan.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlan.java) [R29 · ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java)

### 8.4 Parola/secret deneyimi

**Mevcut:** Form yalnız `ENV` ve `AKTIF` secret referanslarını seçtiriyor; parola yazma endpoint’i mevcut topology istemcisinde bulunmuyor. **Hedef:** Kullanıcı “Kimlik Bilgisi Yapılandırıldı”, “Parolayı Değiştir” veya “Kurum Tarafından Yönetiliyor” görür; referans yolu/provider UUID’si girmez. **Boşluk:** Write-only credential kabul eden, onaylı secret provider’a yazan backend cephesi gerekir. [R26 · Oracle Bağlantı Sürümü Formu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/OracleConnectionVersionForm.tsx) [R27 · Bağlantılar API İstemcisi ve Tipleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts)

Frontend eski `credentialSecretReferenceUuid` alanının label’ını “Parola” yaparak bu işi çözüyor gibi davranamaz. Backend yazma provider’ı yoksa yeni parola akışı pasif ve gerekçeli olmalıdır; mevcut kurumsal provisioning süreci sürer. Secret’ler response, log, analytics, URL, browser storage ve export’a konmaz. Parola GET ile dönmez; “••••••” maskesi gerçek değer saklanıyor izlenimi yaratmayan bir durum metnidir. Değişiklik kaydedildikten sonra form belleği temizlenir; başarısız işlemde parola otomatik tekrar gönderilmez. Merkezi yaşam döngüsü ve erişim sınırları OWASP secret yönetimi ilkeleriyle uyumludur. [S27 · Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)

### 8.5 Şema seçimi, keşif ve uyumluluk

Fiziksel şema detail’de nesne listesi, kolonlar, tür/precision/scale/nullability ve snapshot zamanı bulunur. İlişkili mantıksal şemalar bağlantı olarak sunulur. Mantıksal şema detail’de satırlar ortamları, kolonlar bağlantı/fiziksel şema/revision/son doğrulamayı gösterir. “Eşlenmedi”, “erişim yok”, “snapshot eski”, “desteklenmeyen DB profili” ayrı sonuçlardır.

Nullable metadata için bilinmeyen değer sıfıra veya boş string’e çevrilmez. Oracle `NUMBER` precision/scale eksikliği `NUMBER(0,0)` diye çizilmez; BYTE/CHAR ve LOB gibi farklı metaveriler korunur. ODI 12c repository envanteriyle gerçek Oracle Database 12c desteği ayrı capability satırlarıdır. 19c’de görülen bir keşif sonucu, 12.1/12.2 uyumluluğu olarak işaretlenmez. [R27 · Bağlantılar API İstemcisi ve Tipleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts) [R34 · ODI 12c Metadata Envanter Belgesi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/research/ODI12C_REPOSITORY_METADATA_INVENTORY.md)

**Ek kabul:** Kullanıcı connection değiştirince ona ait olmayan physical schema seçili kalmaz; ortam değişince eski resolution hemen stale olur; test/keşif kullanıcı eylemi olmadan her keypress’te DB bağlantısı açmaz. Kayıt sırasında backend de aynı bağlantı–şema–revision tutarlılığını kontrol eder.

### 8.6 Güncel kolon yeteneği verisinin kullanımı

**Mevcut — son fark kontrolü:** Discovery kolonları artık `canonicalType` ve `executionCapability` taşır. UI bu alanları ham enum olarak yazmak yerine **Aktarım Destekleniyor / Yalnız Katalog / Desteklenmiyor** biçiminde yerelleştirmelidir. `CATALOG_ONLY` kolon keşifte görünür kalır; aktarım eşlemesine eklenirken engelin açıklaması sunulur. `TRANSFER_SUPPORTED` yalnız kolon tipine ilişkin mevcut sınıflandırmadır; bağlantı izni, tüm eşleme, SQL şekli, hedef bağ ve açık runtime birlikte doğrulanmadan “Çalıştırılabilir” etiketi verilmez. [R36 · Yeni Oracle Kolon Yeteneği Sınıflandırması](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/backend/src/main/java/tr/com/innova/akis/oracle/OracleColumnCapability.java) [R37 · Güncel DiscoveryColumn API Tipi](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/frontend/src/features/topology/api.ts)

Bu ekleme, katalog yeteneği gösterimi için yeni alan tasarlama ihtiyacını azaltır. Ancak ayrıntılı gerekçe kodu ve bütün işin çalıştırılabilirlik sonucu ayrı C05 sözleşmesinde kalır. Eski kayıtta alan yoksa `Bilinmiyor` gösterilir; eksik bilgi otomatik olarak destekleniyor sayılmaz.

## 9. Prosedür ve sınırsız sıralı adım editörü

### 9.1 Üç bölge, tek editör — WF07

```text
+--------------------------------------------------------------------------------+
| Procedures / Customer Load                [Validate] [Save Draft] [Actions...] |
| Draft · Changes not saved                 Environment for validation: TEST v  |
+--------------------------+-----------------------------------------------------+
| Steps                    | 02  Write Customers                                |
| [Add Step v]             | Role: TARGET       Operation: Insert Rows          |
|                          | Logical schema: LOGICAL_TARGET                     |
| 01 Read Customers        | Resolved: GPU / TARGET_SCHEMA / revision v2        |
|    SOURCE · SELECT       | Source rowset: 01 Read Customers                   |
| > 02 Write Customers     | Statement timeout: From connection policy          |
|    TARGET · INSERT       +-----------------------------------------------------+
| 03 Gather Statistics     | SQL editor                                         |
|    TARGET · Approval     | 1  INSERT INTO TARGET_SCHEMA.CUSTOMER_DATA (...)    |
|                          | 2  VALUES (:CUSTOMER_ID, ...)                       |
| [Move Up] [Move Down]    +-----------------------------------------------------+
|                          | Diagnostics: named binds / columns / compatibility |
+--------------------------+-----------------------------------------------------+
| Runtime compatibility: Controlled Oracle V1 · No blocking diagnostics          |
+--------------------------------------------------------------------------------+
```

Örnekteki SQL tamamlanmış yürütülebilir komut değil, düşük sadakatli yerleşim metnidir. Gerçek template, seçilen snapshot kolonlarıyla sunucu doğrulama sözleşmesine göre üretilmelidir.

**Amaç/görev:** Sıralı işlemleri güvenli düzenlemek ve hangi kaynak/hedefte uygulanacağını anlamak. **Bölgeler:** Adım listesi; seçili adımın bağlam/formu; SQL ve diagnostics. **Eylemler:** Taslağı Kaydet primary; Doğrula secondary; ekle/çoğalt/sırala adım listesinde; sil ve ham tanım overflow’da. **Durumlar:** Boş prosedürde işlem şablonu; eksik bağda seçim yardımı; runtime sınırını aşan taslakta kaydetme ile çalışma uygunluğu farklı; izin yoksa read-only.

**Klavye/SR:** Adım listesinde ok/Enter; Yukarı Taşı/Aşağı Taşı düğmeleri; silme sonrası önceki veya sonraki adıma kontrollü odak; diagnostics’e ve SQL satırına geçiş. Editör Tab tuşunu yakalarsa çıkış kısayolu ve görünür yardım gerekir; klavye tuzağı olmaz. **Dar ekran:** Adım listesi açılır panel; seçili adım tam genişlik; form/SQL alt alta. **API:** C04, C05, C10; mevcut draft/version/binding kaynakları. **Kabul:** Aynı anda yalnız bir ağır SQL editörü; 1.000 adımda tüm editörlerin render edilmemesi; sıra değişiminde bağlantı veya SQL sessizce kaybolmaması; read-only runtime bilgisi JSON’a tekrar yazılmaması.

### 9.2 Mevcut Oracle V1 yetenek matrisi

Bu tablo genel Oracle SQL desteğini değil, **incelenen Akış runtime resolver’ını** anlatır. Genel `validateCommandContract` kontrolü daha geniş görünse bile, daha sonra çalışan `validateBoundCommands` gerçek yürütülebilir şekli daraltır. [R29 · ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java)

| İşlem | Gerçek V1 sözleşmesi | UI sonucu |
|---|---|---|
| Kaynak okuma | Tek SOURCE connection; bağlı nesneden açık kolonlu `SELECT`; output rowset zorunlu; kaynak named bind’i yok | “Kaynak Veriyi Oku”; kolon seçimi; `SELECT *`, WHERE/JOIN/serbest WITH desteği varmış gibi sunulmaz |
| Hedef yazma | Tek TARGET nesne/bağlantı kimliği; bitişik rowset tüketimi; kolonlar ile named bind adları/sırası eşleşen INSERT | “Satırları Ekle”; kaynak rowset ve kolon/bind eşleşmesi görünür |
| TRUNCATE | Yalnız bağlı hedefte TRUNCATE; destructive risk ve açık onay | “Hedef Tabloyu Boşalt”; geri alınamazlık uyarısı ve exact hedef özeti |
| İstatistik toplama | Yalnız bağlı hedef için izinli DBMS_STATS çağrı şekli; PLSQL ve yüksek risk/onay koşulu | Genel PL/SQL editörü yerine “İstatistik Topla” işlemi |
| UPDATE/MERGE/DELETE/genel DDL | Ön keyword kontrolünde bazı türler bulunabilse de final bağlı hedef kontrolü bu genel biçimleri yürütülebilir kabul etmiyor | Genel “SQL Çalıştır” yeteneği diye sunulmaz |
| STORED_PROCEDURE / keyfi PL/SQL | Final target şekli genel çağrıları desteklemiyor | Tasarım kataloğunda tür bulunması çalışma desteği sayılmaz |
| Rowset akışı | Üretici ve tüketici hemen ardışık; rowset işlemlerinde STOP gerekir | Çift birlikte eklenir; aralarına geçersiz adım konulamaz; Continue seçeneği gösterilmez |

**Kaynak dosya konumları:** `parseTasks` ve `validateCommandContract` için [resolver 262–379](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java#L262-L379); topoloji ve bağlı komut kontrolleri için [resolver 604–720](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java#L604-L720).

### 9.3 “Sınırsız adım” ile kaynak sınırının uzlaştırılması

Ürün kararı “en fazla dört kart” gibi yapay bir editör sınırı olmamasıdır. Bu, HTTP payload, tanım boyutu, runtime task sayısı veya bellek sınırlarının kaldırılması değildir. Hedef, sınırsız liste etkileşimi ve sürüme bağlı açıklanmış çalışma limitidir.

| Sınır | Editör / tanım tarafı | Runtime V1 | İlk güvenli davranış |
|---|---|---|---|
| Adım sayısı | Tanım doğrulayıcı: 10.000 | 1.000 | 1.001 adımlı draft kaydedilebilirliği domain’e göre ayrı; V1 için çalıştırılabilir etiketi verilmez |
| Rowset satır sınırı | UI yeni output: 10.000; validator: 100.000’e kadar | 1.000 | Yeni V1 rowset başlangıcı en fazla 1.000; mevcut 10.000 değeri sessizce 1.000’e kırpılmaz |
| Timeout | Adımda; validator en fazla 3.600 sn | 300 sn | Geçici UI doğrulaması 300 sınırını açıklar; kalıcı çözüm connection policy’ye sürümlü geçiş |
| Komut boyutu | Genel JSON/content sınırları ayrıca var | 65.536 UTF-8 byte | Karakter sayısı değil byte sayısı açıklanır; çok byte’lı metin için doğru uyarı |

Kaynaklar: [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R28 · ProcedureRuntimePlan.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlan.java) [R30 · DefinitionContentValidator.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/metadata/DefinitionContentValidator.java). Burada önerilen UI doğrulaması backend kontrolünün yerine geçmez. Tek runtime capability kaynağı geldiğinde tekrar edilen hard-coded frontend limitleri kaldırılır; geçişte kullanılan geçici limit testi aynı backend sabitleriyle eşleşmek zorundadır.

### 9.4 Adım işlemleri ve semantik koruma

**Ekle:** Runtime’ın desteklediği işlem şablonlarından seçilir. Kaynak okuma eklenirken uyumlu hedef tüketicisiyle bir çift hazırlama seçeneği sunulur. Uygun bağlantı/şema yoksa gerekçeli yönlendirme yapılır; kullanıcıdan UUID istenmez.

**Çoğalt:** Yeni kararlı adım kimliği üretir; görünen ad ile teknik kimlik ayrıdır. Tek adımı çoğaltma, başka tüketicilerin referansını kendiliğinden yeni adıma yönlendirmez. Çift çoğaltmada yalnız kopyanın kendi iç referansları yeniden bağlanır. Henüz kaydedilmemiş bir kopya geri alınabilir.

**Sırala:** Önce önerilen yeni sıra üzerinde dependency ve V1 adjacency kontrolü yapılır. Geçersiz hareket tek bir state alanını bile değiştirmeden reddedilir: “Bu okuma adımı, Satırları Ekle adımının hemen önünde kalmalıdır.” Geçerli çiftin birlikte taşınması desteklenir. Mevcut `normalizeTasks` içindeki bağı kaldırarak normalleştirme yaklaşımı kullanılmaz. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R29 · ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java)

**Sil:** Kullanılan rowset’i veya parametreyi etkiliyorsa bağımlı adımlar listelenir. “Kaynak adım silindi, hedef bağı da sessizce silindi” davranışı yoktur. Draft içinde undo mümkündür; çalışmış veya immutable sürüm silme/geri alma ile karıştırılmaz.

**Devre dışı bırak:** Hedef sözleşmede `enabled` ve `SKIPPED` semantiği, dependency etkisi, hash/derleme ve audit tanımlanmadan aktif özellik olmaz. UI’da saklanan satırı runtime’dan atmak geçerli bir uygulama değildir. Üretici devre dışıysa tüketicinin davranışı explicit validation hatasıdır; otomatik boş rowset üretmez.

**Hata politikası:** STOP ve CONTINUE aynı güvenceyi vermez. Row transfer için V1 STOP zorunluluğu UI’da korunur. Diğer işlemler için de yalnız server capability’nin izin verdiği seçenekler açılır.

### 9.5 Riskli komut ve onay

Oracle 19c’de `TRUNCATE TABLE` geri alınamaz. Kullanıcıya “prosedür iptal edilirse bütün değişiklikler geri döner” veya “başarısızsa eski veriler kalır” güvencesi verilemez. [S26 · Oracle Database 19c: TRUNCATE TABLE](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/TRUNCATE-TABLE.html)

Onay özeti nesne sürümü, ortam, bağlantı takma adı, fiziksel hedef ve riskli adımları içerir. Üretimde yıkıcı işlem için hedef adını doğrulatan ek etkileşim önerilir; bu yalnız UX sürtünmesidir, backend permission/onay kaydının yerine geçmez. Plan veya binding değiştiğinde önceki onay geçersizdir. Risk sınıfı yalnız kullanıcı dropdown’ından alınmaz; backend komut/operasyon tipinden doğrular. İstatistik toplama V1’de onaylı özel işlem olarak ele alınır; bu ürün sınıflandırması her Oracle istatistik işleminin aynı veri yıkıcılığına sahip olduğu iddiası değildir.

### 9.6 SQL editörünün minimum profesyonel kapsamı

| İlk ürün diliminde gerekli | Bilinçli olarak dışında |
|---|---|
| Satır numarası, syntax highlight, undo/redo, arama/değiştirme, indentation, seçili satır diagnostic’i | SQL’i keyfi çalıştıran worksheet veya terminal |
| Snapshot’taki izinli kolon/ad/bind önerileri; desteklenmeyen gramer açıklaması | Genel PL/SQL debugger, bütün Oracle grammar’ına çalışma vaadi |
| Salt okunur/dirty durum, büyük metin limiti, erişilebilir editör adı, görünür klavye yardımı | AI’ın otomatik kod üretip çalıştırması veya gizli preview |
| Kullanıcı isteğiyle biçimleme; mevcut SQL girintisini kendiliğinden değiştirmeme | Kaydetme sırasında otomatik biçim değişikliği |
| Structured diagnostics listesi ve ilgili alana odak | Her hata için toast; yalnız renkli kırmızı alt çizgi |

**Uygulama önerisi:** `SqlEditorAdapter` component’i domain validation ve runtime’dan ayrılmalıdır. Mevcut textarea geçişte çalışmaya devam eder. Profesyonel editör paketi, mevcut React/TypeScript sürümleri, klavye çıkışı, screen reader ve 65.536-byte sınır profiliyle prototipte seçilir. Bu araştırmada CodeMirror resmi dokümantasyonuna erişim başarısız olduğundan, belirli bir paket sürümünün uyumlu veya daha hızlı olduğu iddia edilmemiştir. Paket seçimi ürün davranış sözleşmesini değiştirmez.

## 10. Interface/mapping, paket, değişken ve sekans yerleşimi

### 10.1 Interface ile Mapping ayrımı

**Mevcut:** Canonical nesne kataloğunda MAPPING var; bağımsız INTERFACE enum’u bulunmuyor. **Hedef öneri:** İlk aşamada Interface, basit kaynak → hedef mapping hazırlama deneyiminin kullanıcı adı olabilir; aynı canonical MAPPING kimliği ve sürümleme hattı kullanılır. Ayrı bir runtime nesnesi varmış gibi gösterilmez. [R06 · Nesne Kataloğu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/NESNE_KATALOGU.md)

İki farklı kullanıcı giriş noktası verilebilir: **Interface Oluştur** basit kaynak/hedef ve kolon tablosunu; **Mapping Oluştur** daha ayrıntılı aynı domain editörünü açar. Ancak aynı nesne ağaçta iki ayrı kayıt olarak listelenmez. Authoring mode’un kalıcı kaydı gerekiyorsa UI metadata alanının sürümleme/hash kuralları açıkça belirlenir. Tamamen bağımsız Interface domain tipi istenmesi ayrı backend kararına bağlıdır; mevcut tipe sessizce yeni enum eklenmez.

### 10.2 Nesne türüne göre çalışma yüzeyi

| Nesne | Amaç ve ana yüzey | Eylem / veri ihtiyacı | Durum ve kabul |
|---|---|---|---|
| Interface / basit Mapping | Kaynak/hedef seçimi + kolon eşleme tablosu | Taslağı Kaydet, Doğrula; katalog/snapshot/binding verisi | Kaynak/target yoksa yönlendirme; eşleşmeyen zorunlu kolon açık; otomatik eşleme önerisi kullanıcı onayı olmadan kalıcılaşmaz |
| Mapping | Grid-first kolon eşleme; gerektiğinde küçük ana akış görünümü | Sürümleme, diagnostics, izinli dönüşüm kataloğu | Graph üzerindeki düzen domain tanımıyla karışmaz; 500 kolonun tüm edge’leri aynı anda zorla çizilmez |
| Paket | Nesne çağrıları ve koşullu kontrol akışı; seçili düğüm detay paneli | Nesne/sürüm referans seçimi, graph validation | Paket graph’ı prosedürün doğrusal sırası gibi modellenmez; compile edilebilme runtime desteği sayılmaz |
| Değişken | Ad/tür/scope/değer kaynağı/sensitive formu | Tanım kaydetme ve doğrulama; domain schema | Hassas değer normal önizlemeye/ham JSON’a taşınmaz; desteklenmeyen refresh/çalışma davranışı açıkça kapalı |
| Sekans | NATIVE / repository / table stratejisine uygun form | Bağlantı ve schema referansı yalnız strateji gerektiriyorsa | “Boşluksuz numara” güvencesi yok; tanım oluşturmak kaynak/hedef DB’de otomatik sequence oluşturmaz |
| Diğer ileri nesneler | Reusable Mapping, User Function, Knowledge Module, Load Plan | Katalogdaki gerçek tipe uygun editör veya açıklayıcı salt okunur yüzey | Normal kullanıcı JSON düzenlemek zorunda bırakılmaz; çalıştırılmayan tipte Çalıştır düğmesi etkin olmaz |

Mevcut türler ve kavramsal sınırlar nesne kataloğundan; mevcut workspace/editör yüzeyi kaynak koddan doğrulanmıştır. Yukarıdaki tamamlanmış structured editor’lar **Hedef**tir. [R06 · Nesne Kataloğu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/NESNE_KATALOGU.md) [R13 · DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx)

### 10.3 Ortak ekran sözleşmesi

Bu nesnelerin hepsi Bölüm 7.4’teki explorer/başlık/tab/diagnostics düzenini paylaşır. **Klavye:** Kolon tablosunda hücre gezinmesi ile native form kontrollerinin Tab davranışı çelişmez; graph işlevlerinin liste/form alternatifi vardır. **Dar ekran:** Kaynak/hedef panelleri sekmeye dönüşür; eşleme tablosu kendi yatay kaydırma alanında kalabilir, bütün sayfa taşmaz. **Loading:** Katalog yüklenirken önceki nesnenin kolonları yeni nesnede görünmez. **Error:** Snapshot veya type bilgisi alınamadığında tahmini eşleme yapılmaz. **Permission:** Salt okunur tanım açıkça etiketlenir; formu kilitlemek backend denetiminin yerine geçmez.

**Kabul:** Prosedür, mapping ve paket farklı execution semantiğini korur; bütün nesneler aynı Kaydet/Doğrula/Sürüm isimlerini kullanır; unsupported runtime tipi kaydedilmiş olsa dahi runnable sayılmaz. Yeni editor geliştirmeden önce ilgili JSON schema/domain validation alanlarıyla birebir kapsam testi hazırlanır.

## 11. Çalıştırma ağacı, step detail ve operasyon deneyimi

### 11.1 Ağaç ve detay — WF08

```text
+--------------------------------------------------------------------------------+
| Operations   [Object...] [Environment...] [Status...] [Date range...] [Refresh] |
| Updated 8 seconds ago                                                          |
+--------------------------------------+-----------------------------------------+
| Execution tree       State  Duration | Run #42 / Step 02: Write Customers       |
| v Customer Load                      | Failed · TARGET · INSERT                 |
|   v Run #42          Failed    12 s  | Environment: TEST                         |
|     01 Read          Success    2 s  | Runnable version: v7                     |
|   > 02 Write         Failed   10 s  | Duration: 10 s                           |
|     03 Statistics    Not started     | Rows read: 800  Rows committed: Unknown   |
|   > Run #41          Success   11 s  |                                         |
| > Daily Package                      | Error: Target write could not complete   |
|                                      | Suggested action: Inspect outcome        |
|                                      | [Summary] [Events] [Technical Details]    |
+--------------------------------------+-----------------------------------------+
```

**Amaç/görev:** Hangi nesnenin hangi sürümünün hangi ortamda, hangi adımda olduğunu anlamak. **Bölgeler:** Filtre/son güncelleme; typed execution tree veya treegrid; seçili node detail. Liste düğümü nesne, run ve step için farklı özet gösterir. **Eylemler:** İlk görev incelemedir; Refresh secondary. İptal/yeniden çalıştırma yalnız seçili run’da server izinliyse; adım satırına yetkisiz retry düğmesi eklenmez.

**Durumlar:** İlk açılış loading; sıfır run için Çalıştırılabilir Sürümleri Gör; filtre sıfır için Filtreyi Temizle; ağ kaybında son veriyi timestamp ile stale göster; yetki kaybında hassas detay temizle; bilinmeyen final sonuçta başarı veya başarısızlık tahmin etme. **Klavye/SR:** Status/duration ayrı kolonlarsa APG treegrid; sade tek kolon listede tree. Seçili düğümün detail’i kendi başlığıyla ilişkilidir. Polling odak/selection/expanded state’i değiştirmez. [S16 · APG: Treegrid Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treegrid/)

**Dar ekran:** Ağaç ve detay iki aşamalı görünüm; detaydan geri dönünce aynı node görünür ve odaklıdır. **API:** C07 projection ve C08 actions; geçmiş event listesi ayrı kaynak. **Kabul:** Her step’in stable ID’si, ordinal’ı, plan sürümü ve kanıt temeli vardır; node’lar ham event JSON parse edilerek üretilmez; false zero veya false success yoktur.

### 11.2 Hiyerarşi ve geçmişin korunması

Temel hiyerarşi **tasarım nesnesi → çalışma → sıralı adımlar**dır. Paket runtime’ı gerçekten alt çalışmaları üretmeye başladığında paket run altında child object run gösterilebilir; böyle bir backend ilişkisi yokken paket içeriğinden sahte child run türetilmez. Prosedür adımlarının sırası immutable çalışma planından gelir, güncel draft sırasından gelmez.

Silinmiş/yeniden adlandırılmış bir tasarım nesnesinin geçmişi, run sırasında sabitlenmiş görüntüleme adı ve türüyle okunabilmelidir. Yetki politikası izin veriyorsa “Nesne artık mevcut değil” etiketi gösterilir. Step ağacı yalnız gerçekten başlamış adımları değil, planlanan ama başlamayan adımları da gösterebilir; bu durumda “Başlamadı” plan bilgisi olup event varmış gibi sunulmaz.

ExecutionController şu an run ve event listelerini sunuyor; step projection yok. Bununla birlikte `ProcedureExecutionJournalPort` task index, task/binding ve runtimePlanHash taşıyan typed evidence üzerinden started/succeeded/failed/outcomeUnknown olaylarını ayırıyor. Hedef projection bu güvenilir typed sınır ve pinned planı temel almalıdır; mevcut port doğrudan bir read API değildir. [R31 · ExecutionController.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java) [R35 · Prosedür Typed Execution Journal Sınırı](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureExecutionJournalPort.java)

### 11.3 Durum ve eylem matrisi

| Anlam | Görünür EN / TR | Renk dışı gösterim | Güvenli eylem |
|---|---|---|---|
| Kuyrukta | Queued / Bekliyor | Clock ikonu + etiket | Bugünkü backend izin veriyorsa iptal |
| Hazırlanıyor / çalışıyor | Preparing / Hazırlanıyor; Running / Çalışıyor | Evre metni, spinner isteğe bağlı | İncele; çalışan iptali yalnız gerçek sözleşme varsa |
| Başarı | Succeeded / Başarılı | CheckCircle + bitiş zamanı | Yeni çalışma ancak exact sürüm/ortam onayıyla |
| Başarısızlık | Failed / Başarısız | CircleX + güvenli hata özeti | Sunucunun önerdiği inceleme/yeniden deneme |
| İptal isteniyor | Cancellation requested / İptal İstendi | Stop/clock + henüz bitmedi metni | Bekle/incele; iptal tamamlandı denmez |
| İptal | Cancelled / İptal Edildi | Square/stop + bitiş bilgisi | Sonraki işlemin veri etkisi ayrıca değerlendirilir |
| Sonuç belirsiz | Outcome uncertain / Sonuç Belirsiz | CircleHelp + açık uyarı | Mutabakat/inceleme; kör retry yok |
| Mutabakat | Reconciling / Mutabakat Yapılıyor | RefreshCw + açıklama | Kanıt tamamlanıncaya kadar destructive eylem yok |
| Müdahale gerekli | Intervention required / Müdahale Gerekli | OctagonAlert + neden | Yetkili işletim yolu; manuel başarılı işaretleme önerilmez |
| Onay gerekli | Approval required / Onay Gerekli | Shield/lock + onay bilgisi | Release hazırlık durumu; run sonucuymuş gibi gösterilmez |
| Veri eski | Stale / Güncel Değil | Timestamp + bağlantı uyarısı | Yenile; underlying run state’i değiştirmez |

Prefect’in state ayrımı yararlı bir örnektir, fakat buradaki iş kuralları Akış’ın kendi canonical durumları ve backend action policy’siyle belirlenir. [S07 · Prefect 3: States](https://docs.prefect.io/v3/concepts/states) [R08 · Manuel Çalıştırma Kontrol Düzlemi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/MANUAL_RUN_CONTROL_PLANE.md) [R31 · ExecutionController.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java)

### 11.4 Metriklerin anlamı

`rowsRead`, `rowsWritten`, `rowsCommitted`, `rowsRejected` birbirinin yerine kullanılmaz. Kaynak satır sayısı, veritabanının fiziksel olarak taradığı tüm satır sayısı değildir. Yalnız genel `rowCount` biliniyorsa hangi evreyi temsil ettiği açıklanmadan “commit edilen satır” diye etiketlenmez. Bilgi yoksa `null` + “Bilinmiyor”; gerçek sıfır için `0` gösterilir. Büyük sayılar JSON precision kaybını önleyen sözleşmeyle taşınır; gösterim locale’e göre formatlanır.

Çalışan süre sunucu başlangıç zamanı ve serverTime ile hesaplanabilir; final duration backend tarafından sabitlenir. Adımların süresini toplamak her zaman run süresine eşit değildir; kuyruk, hazırlık ve mutabakat ayrı süreler olabilir. İlerleme yüzdesi denominator bilinmiyorsa uydurulmaz.

### 11.5 Olay, teknik audit ve güvenli ayrıntı

Step detail’de olaylar typed `stepId`/`taskId` korelasyonuyla filtrelenir. Backend ilişki sağlayamıyorsa olay bütün run’a ait gösterilir; SQL metninden veya event adı benzerliğinden ilişki tahmin edilmez. Teknik ayrıntı; correlation ID, plan hash, revision kimliği, güvenli error code ve kanıt durumunu gösterebilir. Parola, secret path, satır içeriği, bind değerleri veya sınırsız ham SQL normal operasyon yüzeyinde yer almaz.

**Yeniden çalıştırma ile devam etme ayrımı:** Yeni run oluşturmak, kaldığı adımı yeniden yürütmek veya checkpoint’ten devam etmek aynı işlem değildir. Bugünkü controller’da genel retry/resume API’si bulunmuyor. Tasarımda bu eylemler ancak yeni backend protokolü, idempotency ve risk değerlendirmesi tamamlanırsa açılır; kullanıcıya mevcut yetenek olarak vaat edilmez. [R31 · ExecutionController.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java)

## 12. Design system foundation ve component inventory

### 12.1 Görsel yaklaşım ve ölçüler

**Hedef:** Nötr/slate yüzeyler, mevcut teal kimliğin sınırlı vurgu olarak sürmesi, küçük semantik durum alanları. Kartlar beyaz/çok açık yüzey + border ile ayrılır; yoğun dashboard, bütün kartı boyayan status renkleri, glow ve gereksiz gradient kullanılmaz. Material 3’ün renk ile “on-color” eşleştirmesi tasarım ilkesidir; Android component ölçüleri aynen web’e taşınmaz. [S13 · Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)

Aşağıdaki sayısal ölçüler **PROTOTİPLE DOĞRULANACAK başlangıç değerleridir**; WCAG tarafından zorunlu kılınmış genel UI ölçüleri değildir.

| Token / ölçü | Önerilen değer | Gerekçe ve doğrulama |
|---|---|---|
| `space.1/2/3/4/6/8` | 4 / 8 / 12 / 16 / 24 / 32 px | 4 px temel; alan içi 8/12, alan grupları 16/24, bölüm ayrımı 32 |
| `type.body` | 14 px / 20 px; normal yoğunlukta 16/24 seçeneği | Teknik tabloda bilgi yoğunluğu; uzun yardım metninde daha rahat satır |
| `type.label` | 14/20, medium | Form label’larını küçük/açık gri dekorasyona düşürmemek |
| `type.caption` | 12/16, yalnız ikincil metaveri | Kritik hata veya ana eylem için kullanılmaz |
| `type.h1/h2` | 24/32 ve 20/28 | Workspace başlığı landing-page başlığı kadar alan tüketmez |
| `type.code` | 14/21, sistem monospace | SQL’in hizası; font büyütme kontrolüyle okunabilirlik |
| Font ailesi | Segoe UI/Aptos/sistem sans; Consolas/Cascadia Mono/sistem monospace | Mevcut kurumsal görünüme yakın; dış font indirme zorunluluğu yok |
| Form kontrolü / buton | 40 px normal; touch/coarse pointer’da 44 px | Giriş kolaylığı; 44 px ürün hedefidir, AA’nın genel alt sınırı değildir |
| Compact tree satırı | 32 px; rahat mod 40 px | Büyük ağaçta yoğunluk; klik alanı ikon çiziminin boyutu değildir |
| Tablo satırı | 36 px compact; 44 px normal | Uzun operasyon listesi ile dokunma kullanımı arasında tercih |
| Global header | 56 px; dar ekranda en az 56 px | Proje/dil/tema görünür, dikey alan tüketimi kontrollü |
| Ana navigasyon | 208 px açık; 72 px kompakt | Üç etiket için yeterli alan; çalışma içeriğine yer açma |
| Project explorer | Varsayılan 280; min 240; max 360 px | Uzun Türkçe/teknik nesne adı; kullanıcı yeniden boyutlandırabilir |
| Prosedür adım listesi | Varsayılan 240; min 220; max 320 px | Sıra/ad/rol/diagnostic sayısını göstermek için |
| Detay paneli | En az 360 px veya kalan alan | Metin etiketlerini sürekli ellipsis’e sokmamak |
| SQL editörü | En az 280 px yükseklik; alan uygunsa 560 px genişlik | Çok satırlı işlemi görebilmek; dar ekranda kendi scroll’u |
| Radius | Kontrol 6 px; panel 8 px; dialog 12 px | Birbirine yakın, işlevsel köşe ölçeği; pill her yerde kullanılmaz |
| Border / elevation | 1 px; normal panel shadow yok veya çok hafif; overlay’de tek shadow | İçerik hiyerarşisini derin kart yığınlarıyla kurmamak |
| Hareket | 100–150 ms basit geçiş; reduced-motion’da zorunlu olmayan hareket kapalı | Hover’da bütün butonun zıplaması gerekmez |

### 12.2 Responsive panel bütçesi

| Genişlik | Yerleşim kararı |
|---|---|
| ≥1600 px | Açık ana navigasyon + proje ağacı + adım listesi + editör; yalnız görev gerçekten gerektiriyorsa dört bölge |
| 1280–1599 px | Navigasyon kompakt; explorer/adım listesi daraltılabilir; editör minimum alanı korunur |
| 1024–1279 px | Aynı anda en fazla bir workspace yan paneli; klasör ağacı drawer, adım listesi içerikte kalabilir |
| 768–1023 px | Liste/detail geçişli düzen; yan paneller açılıp kapanır; context header sabit |
| <768 px | Tek kolon; explorer/step list seçimi ayrı panel; eylemler overflow; form alanları alt alta |
| 320 CSS px / 400% zoom denemesi | Shell, formlar ve metinler reflow; iki boyutlu tablo/SQL yalnız kendi bölgesinde yatay scroll |

Bu eşikler cihaz marka/modeline değil, panellerin minimum genişlik toplamına dayanır. Kullanıcı panel ölçüsünü değiştirdiğinde min/max korunur. Resize separator klavyeyle ayarlanabilir olmalı; sadece dar bir fare çizgisi değildir. WCAG reflow istisnası bütün sayfayı yatay kaydırma bahanesi yapılmaz. [S23 · Understanding 1.4.10: Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html)

### 12.3 Semantik renk token’ları ve hesaplanan kontrast

**Hesap yöntemi:** Opak sRGB foreground/background çiftleri için WCAG relative luminance oranı; iki ondalığa yuvarlama. Bunlar tarayıcıda render edilmiş ekran ölçümü değil, aşağıdaki hex çiftlerinin matematiksel kontrolüdür. Normal metin hedefi ≥4,5:1; büyük metin ve gerekli non-text sınır/ikon için ilgili kriterde ≥3:1. [S20 · Understanding 1.4.3: Contrast (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) [S21 · Understanding 1.4.11: Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html)

| Semantik token / kullanım | Light foreground / background | Dark foreground / background | Oran light / dark |
|---|---|---|---|
| `action.primary.fg/bg` — ana eylem | `#FFFFFF` / `#147D75` | `#0F172A` / `#5EEAD4` | 4,98 / 12,07 |
| `text.secondary` / `surface.base` | `#334155` / `#FFFFFF` | `#CBD5E1` / `#111827` | 10,35 / 11,95 |
| `status.success.fg/bg` — tamamlandı | `#166534` / `#F0FDF4` | `#BBF7D0` / `#14261C` | 6,81 / 13,09 |
| `status.warning.fg/bg` — onay/dikkat | `#854D0E` / `#FFFBEB` | `#FDE68A` / `#2B2415` | 6,61 / 12,35 |
| `status.danger.fg/bg` — hata/yıkıcı | `#991B1B` / `#FEF2F2` | `#FECACA` / `#321B20` | 7,60 / 11,05 |
| `status.info.fg/bg` — hazırlık/çalışıyor | `#155E75` / `#ECFEFF` | `#A5F3FC` / `#142830` | 6,99 / 12,23 |
| `status.neutral.fg/bg` — kuyruk/iptal | `#475569` / `#F1F5F9` | `#CBD5E1` / `#1E293B` | 6,92 / 9,85 |
| `border.control` / `surface.base` | `#64748B` / `#FFFFFF` | `#94A3B8` / `#111827` | 4,76 / 6,92 |
| `focus.ring` / `surface.base` | `#115E59` / `#FFFFFF` | `#5EEAD4` / `#111827` | 7,58 / 11,99 |

Temel zemin: light `#F8FAFC`, dark `#0B1220`; ana yüzey: light `#FFFFFF`, dark `#111827`. Birincil metin: light `#0F172A`, dark `#F8FAFC`. İnce dekoratif ayraçlar light `#CBD5E1`, dark `#334155` olabilir; bunlar form kontrolünün tanınması için gerekli sınır rengi yerine kullanılmaz. Focus ring, renkli butonlarda yüzey renginde ayırıcı boşluk/ikinci halka ile test edilmelidir.

**Mevcut bug’a doğrudan karşılık:** `.button.primary` beyaz metni dark temada da koruyor; `--accent` ise `#49B9AD` oluyor. Bu çift 2,38:1. Çözüm sadece teal tonunu değiştirmek değil, `action.primary.fg` token’ını da temaya göre tanımlamaktır. [R23 · Ortak CSS ve Tema Token’ları](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/styles.css)

Renkler yalnız badge, küçük ikon, seçili satır göstergesi veya kısa uyarı alanında kullanılır. Kaynak ve hedef rolü yalnız renk ile ayrılmaz; SOURCE/TARGET label ve yön/rol ikonu zorunludur. Light/dark/system mevcut olduğu için bütün yeni bileşenlerin iki tema testi aynı kabul kapısındadır.

### 12.4 Component inventory

| Component | Sorumluluk | Mutlaka kapsayacağı durum / davranış |
|---|---|---|
| `ProjectBoundary` | Proje doğrulama ve stale state izolasyonu | Loading, erişim kaybı, invalid ID, eski yanıtı reddetme |
| `ProjectSwitcher` | Arama ve proje seçimi | Combobox, current/recent, dirty guard |
| `WorkspaceNavigation` | Üç ana destination | Aktif link, compact/dar ekran, erişilebilir isim |
| `ObjectExplorer` / `ProviderSchemaTree` | Hiyerarşik keşif | Roving focus, lazy child, filter ancestors, expansion persistence |
| `ExecutionTree` | Nesne/run/step hiyerarşisi | Typed node, status/duration, stable selection |
| `ResizablePanels` | Master–detail alan bütçesi | Keyboard resize, min/max, collapse, persisted preference |
| `Field` / `FormSection` | Ortak form standardı | Label, required açıklaması, help, invalid, readonly |
| `AsyncCombobox` | Katalog seçimi | Debounce, loading/error, pagination, no UUID fallback |
| `Tabs` | İçerik grubu değişimi | Roving focus, aria-controls, manual activation gerektiğinde |
| `Dialog` / `Drawer` | Kısa kritik iş / bağlamlı detay | Focus trap gerekiyorsa, Escape, return focus, dirty close |
| `ActionMenu` / `ContextMenu` | İkincil işlemler | Sağ tık + görünür düğme + klavye eşdeğerliği |
| `StatusBadge` / `CapabilityBadge` | Sonuç ile yeteneği ayrı sunma | Renk + ikon + metin; unknown fallback |
| `CredentialWriteForm` | Write-only kimlik bilgisi | No echo/storage/log; failed submit ve yeniden giriş |
| `ResolutionSummary` | Logical/environment → physical/revision | Eksik/ambiguous/stale/supported durumları |
| `ProcedureStepList` / `StepEditor` | Sıra + seçili adım | Dependency-aware move/delete; tek ağır editör |
| `SqlEditorAdapter` | Editör paketi soyutlaması | Readonly, key escape, diagnostics, byte limit |
| `ValidationSummary` | Alan/adım doğrulamaları | Severity, field pointer, issue count, Go to Issue |
| `RunnableVersionPanel` | Sürüm/ortam hazırlığı | Immutable plan, approval, unsupported/disabled ayrımı |
| `RunStepDetail` / `EventTimeline` | İşletim ayrıntısı | Typed evidence, null metrics, safe logs, freshness |
| `ProblemState` / `StatusAnnouncer` | Hata ve geri bildirim | Code→translation, correlation, live-region gürültü kontrolü |

Her feature’ın ayrı button/dialog/dropdown CSS’i üretmesi yerine bu inventory için tek ortak davranış ve token katmanı kurulmalıdır. Bu, bütün ürünün Carbon/Primer/Material paketlerinden biriyle yeniden yazılmasını gerektirmez. Lisanslı bileşen kodu veya yeni paket alınacaksa kendi lisansı ve tam sürüm uyumluluğu ayrıca incelenir; doküman örneği görmek sınırsız yeniden dağıtım hakkı sayılmaz.

## 13. İngilizce/Türkçe terminoloji ve metin standardı

### 13.1 Terim sözlüğü

| English | Türkçe | Kullanım notu |
|---|---|---|
| Project | Proje | Workspace’in kimlik sınırı |
| Project Overview | Proje Genel Bakış | Sade giriş; dashboard değil |
| Development | Geliştirme | Tanım ve tasarım alanı |
| Operations | Operasyonlar | Çalışma geçmişi ve işletim |
| Connections | Bağlantılar | UI’da Topoloji kullanılmaz |
| Provider | Sağlayıcı | Örnek Oracle |
| Connection Revision | Bağlantı Sürümü | Backend revision/version numarasıyla eşleşir |
| Physical Schema | Fiziksel Şema | Gerçek DB şeması |
| Logical Schema | Mantıksal Şema | Ortamdan bağımsız bağ |
| Environment Mapping | Ortam Eşlemesi | Logical → physical/revision |
| Data Catalog | Veri Kataloğu | Tablo/kolon/snapshot bilgisi |
| Object | Nesne | Tasarım nesnesi |
| Folder | Klasör | Kullanıcı hiyerarşisi |
| Interface | Interface | Basit mapping deneyimi; ayrı canonical tip mevcut değil |
| Mapping | Mapping | Kolon eşleme bağlamı yardım metninde açıklanır |
| Procedure / Step | Prosedür / Adım | DB stored procedure ile Akış Prosedürü ayrımı yardımda belirtilir |
| Package / Variable / Sequence | Paket / Değişken / Sekans | Aynı explorer’daki gerçek nesne ailesi |
| Draft | Taslak | Değiştirilebilir içerik |
| Definition Version | Tanım Sürümü | Immutable içerik |
| Runnable Version | Çalıştırılabilir Sürüm | Kullanıcı adı; publication/release backend’de kalır |
| Definition Only | Yalnız Tanım | Runtime desteklenmiyor; silinmiş veya hatalı anlamına gelmez |
| Run / Run Attempt | Çalıştırma / Çalıştırma Denemesi | Nesne ile örneği ayrılır |
| Source / Target | Kaynak / Hedef | Yalnız renk ile anlatılmaz |
| Rowset / Batch | Satır Kümesi / Toplu Yazım | Gerekirse teknik terim parantez içinde |
| Preflight | Ön Kontrol | Metadata ve bağlantı açan probe türleri açıklanır |
| Credentials Configured | Kimlik Bilgisi Yapılandırıldı | Secret değeri veya referansı göstermez |
| Outcome Uncertain | Sonuç Belirsiz | Başarısız ile eşanlamlı değildir |
| Stale | Güncel Değil | Kaynak veri tazeliği; çalışma sonucu değil |

### 13.2 Eylem metinleri

| English action | Türkçe eylem |
|---|---|
| Create Project | Proje Oluştur |
| Export Project | **Projeyi Dışa Aktar** |
| Import as New Project | Yeni Proje Olarak İçe Aktar |
| Save Draft | Taslağı Kaydet |
| Create Version | Sürüm Oluştur |
| Prepare Runnable Version | Çalıştırılabilir Sürüm Hazırla |
| Run | Çalıştır |
| Cancel Run | Çalıştırmayı İptal Et |
| Validate | Doğrula |
| Test Connection | Bağlantıyı Test Et |
| Activate Revision | Sürümü Aktifleştir |
| Change Password | Parolayı Değiştir |
| Move Up / Move Down | Yukarı Taşı / Aşağı Taşı |
| View Raw Definition | Ham Tanımı Görüntüle |
| Save and Continue | Kaydet ve Devam Et |
| Discard Changes / Stay Here | Değişiklikleri Bırak / Burada Kal |

Türkçe butonlar ürün kararı gereği yukarıdaki başlık düzeninde yazılır. Açıklama ve hata cümleleri doğal Türkçe cümle düzenindedir. `SQL`, `JSON`, `JDBC`, `JNDI`, `SID`, `UUID` korunur. Genel `text-transform: capitalize` veya bütün metne üst harf dönüşümü kullanılmaz; özellikle Türkçe i/ı ve canonical kodlar bozulmamalıdır. [R03 · Ürün Deneyimi V2 Kararları](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PRODUCT_EXPERIENCE_V2_DECISIONS.md)

### 13.3 Dil, tarih, sayı ve hata testleri

Varsayılan dil mevcut implementasyondaki gibi **İngilizce** kalır; kaydedilmiş `tr` tercihi Türkçe açar. `document.documentElement.lang` değişimi mevcut temelde bulunuyor ve korunmalıdır. Dil değiştirmek sunum tercihi olup draft’ı reload ederek kaybetmemelidir. [R24 · Dil Başlatma ve Tercihi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/core/i18n/index.ts) [R33 · Uygulama Temeli Testleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/App.test.tsx)

Tarih depolama/API gösterimi UTC/offset içeren kararlı biçimde; kullanıcı gösterimi seçilen locale ve açık saat dilimiyle yapılır. Örneğin `12 Eyl 2026 00:13 · Europe/Istanbul` ile İngilizce karşılığı aynı anı temsil etmelidir. “Az önce” tek başına yeterli değildir; tam timestamp erişilebilir açıklamada bulunur. Run’ın hedef ortamı ile kullanıcı zaman dilimi birbirine karıştırılmaz.

Sayılar `Intl` tabanlı ortak formatter’dan geçer; sayısal SQL literal veya canonical JSON içerik locale’e göre değiştirilmez. Büyük row count için precision korunur. Ondalıklı form girdisinde Türkçe virgül desteği açık parse kuralıyla değerlendirilir; bir yazımın sessizce binlik ayıracı sayılması önlenir.

**Kabul:** EN/TR anahtar eşitliği; görünür ham translation key sıfır; hata kodu çevirileri; uzun nesne adları ve Türkçe eylemlerle en az %40 metin genişlemesi denemesi; tarih/sayı fixture’ları; dil geçişinde dirty SQL’in byte-identical korunması. `en-GB` gibi ekran içine dağılmış sabit formatter’lar merkezi tercihe taşınır. [R16 · TopologyPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/TopologyPage.tsx)

Hata içeriği şu sırayı izler: **Ne oldu? → Ne etkilenmedi/etkilendi? → Ne yapılabilir?** Örnek: “Hedef şema çözümlenemedi. Taslağınız korunuyor. TEST ortam eşlemesini seçin.” Teknik correlation ID ayrı kopyalama alanındadır; SQL/credential içeren exception metni normal hata cümlesi olarak kullanılmaz.

## 14. WCAG 2.2 AA ve klavye erişilebilirliği kontrol listesi

Aşağıdaki liste uygulama kabul kapısıdır; araştırma sırasında bu testlerin geçtiği iddia edilmez. APG etkileşim örüntüleri yardımcı tasarım rehberidir; WCAG uygunluğu yalnız bunların rollerini ekleyerek kazanılmaz. [S14 · Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/)

| Kontrol | Test ve beklenen sonuç | Kaynak |
|---|---|---|
| Metin kontrastı | Normal metin ≥4,5:1; büyük metin için ilgili ≥3:1 kuralı; light/dark ve tüm state’ler | [S20 · Understanding 1.4.3: Contrast (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) |
| Kontrol/ikon kontrastı | İşlevin tanınması için gerekli sınırlar, seçim/focus göstergeleri uygun komşu renkte ≥3:1 | [S21 · Understanding 1.4.11: Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html) |
| Reflow / zoom | 320 CSS px eşdeğerinde form/shell yatay taşmaz; tablo/SQL istisnası bölgesel | [S23 · Understanding 1.4.10: Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html) |
| Hedef boyutu | 24×24 CSS px minimum veya standardın geçerli istisnası; touch için ürün hedefi 44 px | [S22 · Understanding 2.5.8: Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) |
| Odak örtülmesi | Sticky header/footer/overlay odaklanan kontrolü tamamen gizlemez | [S24 · Understanding 2.4.11: Focus Not Obscured (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html) |
| Sürükleme alternatifi | Drag yapılan sıralama/taşıma için tek pointer ile tıklanabilir düğme/dialog alternatifi; klavye de kullanılabilir | [S25 · Understanding 2.5.7: Dragging Movements](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html) |
| Tree klavyesi | Tek giriş odağı; görünür düğümler arasında oklar, aç/kapat, Home/End; selection ile focus ayrımı | [S15 · APG: Tree View Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/) |
| Tree sanallaştırma | Görünmeyen aktif item yok olmaz; position/level/set size yalnız doğru biliniyorsa; lazy durum anlaşılır | [S15 · APG: Tree View Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/) |
| Treegrid | Satır/sütun ilişkisi ve hierarchical genişleme; seçili step detayına erişim; görünür status metni | [S16 · APG: Treegrid Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treegrid/) |
| Tabs | `tablist/tab/tabpanel`, aria-selected/controls; oklar; maliyetli tab’da manuel aktivasyon | [S17 · APG: Tabs Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/) |
| Modal dialog | Accessible title; gerekli modal odak sınırı; Escape; güvenli initial focus; açana geri dönüş | [S18 · APG: Dialog (Modal) Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/) |
| Combobox | Label, expanded, aktif seçenek; Enter kesinleştirir; Escape eski seçimi korur | [S19 · APG: Combobox Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/) |
| Renkten bağımsız anlam | Hata/başarı/kaynak/hedef etiketi ikon ve metinle anlaşılır | [S14 · Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) |
| Form ilişkileri | Label/help/error programatik bağlanır; invalid alan açıklaması okunur; yalnız placeholder kullanılmaz | [S14 · Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) |
| Sayfa yapısı | Tek main, anlamlı heading sırası, skip links, amaçlı bağlantı isimleri | [S14 · Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) |
| Durum duyurusu | Kaydetme ve arama sonucu odağı çalmadan duyurulur; her poll ekran okuyucuyu tekrar konuşturmaz | [S14 · Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) |
| Dil | HTML lang doğru; canonical terimler bozulmaz; yabancı uzun açıklama gerekiyorsa dil işaretleme | [S14 · Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) |
| Klavye tuzağı | SQL editörü ve resizable panel’den belgelenmiş klavye yolu ile çıkılabilir | [S14 · Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) |

**Düzey karışıklığı yapılmaz:** 44 px hedef genel AA zorunluluğu değildir; burada daha rahat ürün hedefidir. `Focus Appearance` 2.4.13 AAA kriteri, `Focus Not Obscured (Minimum)` 2.4.11 AA ile aynı şey değildir. Ayrıca dragging kriterinin pointer alternatifi yalnız “klavyeyle yapılabiliyor” denilerek kapatılmaz; tıklanabilir taşıma alternatifi de gerekir. [S14 · Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) [S22 · Understanding 2.5.8: Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) [S24 · Understanding 2.4.11: Focus Not Obscured (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html) [S25 · Understanding 2.5.7: Dragging Movements](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html)

**Test düzeni önerisi:** Otomatik DOM erişilebilirlik kontrolü + gerçek klavye akışları + Windows’ta NVDA ile Chrome/Edge, macOS’ta VoiceOver/Safari örneklemesi. Bunlar minimum önerilen ürün test kombinasyonlarıdır; bu araştırmada çalıştırılmadı. En az WF01, WF04, WF06, WF07, WF08 ve destructive dialog iki dil/iki temada incelenmelidir. Otomatik taramanın sıfır hata vermesi manuel görev testinin yerine geçmez.

## 15. Loading, empty, error, permission, stale ve destructive durum matrisi

| Durum | Kullanıcıya gösterim | Eylem / state davranışı | Yasak davranış |
|---|---|---|---|
| İlk yükleme | Bölge boyutunu koruyan skeleton veya açıklayıcı yükleniyor | Aynı bölge `aria-busy`; iptal/yeniden deneme uygun yerde | Önceki projenin nesnesini yeni başlık altında göstermek |
| Arka plan yenileme | Mevcut veri + küçük güncelleme göstergesi | Selection/scroll/expanded korunur | Bütün sayfayı skeleton’a çevirmek |
| Gerçek boş liste | Bağlama özel boş açıklama ve yetkili create eylemi | İlk kullanımı yönlendir | Ağ hatasını boş listeye çevirmek |
| Filtre sonucu boş | “Bu filtrelerle sonuç yok” | Filtreyi Temizle | Projeyi boş veya silinmiş göstermek |
| Alan doğrulama hatası | Alan altında neden + özet listesi | Go to Issue alan/adıma götürür | Sadece toast veya kırmızı border |
| API / ağ hatası | Güvenli açıklama, yeniden deneme, correlation | Kirli içerik korunur; sonuç bilinmiyorsa yeni write kör tekrarlanmaz | Raw exception/SQL/credential göstermek |
| 401 / oturum bitti | Oturum yenileme/giriş | Güvenli dönüş yolu; hassas cache temizliği | Başka kullanıcıya eski cache’i göstermek |
| Yetki yok / 403 | Güvenli açıklama; izinli içerik salt okunur | Server her eylemi yeniden doğrular | Butonu gizleyip backend yetkisini kaldırmak |
| Bulunamadı / erişilemez | Genel güvenli durum; uygun üst bağlama dönüş | Politika uyarınca varlık bilgisi açıklanmaz | UUID’ye göre isim tahmini yapmak |
| Optimistic lock / 409–412 | Başka değişiklik bilgisi; karşılaştırma | Beklenen sürümle bilinçli karar | Otomatik reload ile draft’ı ezmek |
| Stale snapshot / binding | Son doğrulama zamanı ve hangi bağ değişti | Yeniden çözümle; mevcut plan onayı geçersiz olabilir | Eski resolved label’ı güncelmiş gibi bırakmak |
| Runtime kapalı | “Bu ortamda çalıştırma etkin değil” | Tanım düzenleme devam eder; izinli hazırlık ayrı | Başlat düğmesini defalarca 503’e göndermek |
| Desteklenmeyen işlem | “Tanım saklanabilir; bu çalışma profili desteklemiyor” | Uyumluluk detayına geçiş | Başarılı Kaydet’i Çalıştırılabilir diye sunmak |
| Approval required | Gerekli onay ve hangi plan için olduğu | Backend akışı varsa onaya gönder | Frontend checkbox’ını backend onayı saymak |
| Destructive | Nesne, ortam ve hedef açık risk özeti | Güvenli varsayılan odak; explicit confirmation | Genel “Emin misiniz?” ile hedefi gizlemek |
| Outcome uncertain | Sonuç Belirsiz; bilinen/bilinmeyen kanıt | Mutabakat/inceleme; action policy | Otomatik retry veya sahte başarısızlık |
| Bilinmeyen metric | “Bilinmiyor” / `—` + gerekçe | Null semantiğini koru | `null` → `0` dönüşümü |
| Kısmi/paged sonuç | Yüklü aralık, kalan sayfa; kesin değilse toplam bilinmiyor | Daha Fazla Yükle / sayfa | İlk sayfayı bütün proje diye sunmak |

**Ortak davranış:** Başarılı işlem bildirimi kısa ve polite; engelleyici hata kalıcı ve ilgili alandadır. Disabled ile busy farklıdır: yetki/yetenek eksikliğinde `cursor:wait` kullanılmaz. Bir write isteğinin bağlantı kesintisinde sunucuya ulaşıp ulaşmadığı bilinmiyorsa aynı idempotency key ile güvenli sonuç sorgulama/tekrar protokolü uygulanır; yeni key üretip duplicate oluşturulmaz. Mevcut ortak API istemcisinde code ve correlationId bulunması bu standarda iyi bir başlangıçtır. [R25 · Ortak API İstemcisi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/core/api/client.ts)

## 16. Mevcut component → hedef component etki haritası

| Mevcut dosya / sorumluluk | Hedef ayrıştırma | Korunacak | Değişim / bağımlılık |
|---|---|---|---|
| `App.tsx` | AppRouter + ProjectBoundary + compatibility redirects | Auth/route temeli | C01; eski deep link testleri. [R10 · App.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/App.tsx) |
| `AppShell.tsx` | ProjectWorkspaceShell + 3 alan nav + ProjectSwitcher | Tema/dil/hesap erişimi | Proje state izolasyonu, menü sadeleşmesi. [R11 · AppShell.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/AppShell.tsx) |
| `ProjectOverviewPage.tsx` | CalmProjectHome | Proje bilgisi | Run fetch/metrics kaldır; export menu. Backend gerektirmez. [R12 · ProjectOverviewPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/projects/ProjectOverviewPage.tsx) |
| `DefinitionsWorkspace.tsx` | ObjectWorkspace, DraftController, VersionPanel, BindingPanel, ValidationPanel | Draft/version/optimistic lock ve var olan API | Monolitik state’in ayrımı; C04/C05/C10. [R13 · DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx) |
| `ProjectExplorer.tsx` | ObjectExplorer + ortak Tree | Kararlı folder/object UUID, cycle savunması, move temeli | Expansion/klavye; ölçek için C02. [R14 · ProjectExplorer.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProjectExplorer.tsx) |
| `ProcedureEditor.tsx` | ProcedureStepList + StepEditor + SqlEditorAdapter | Sıralı tasks, mevcut yukarı/aşağı eylemi | Sessiz normalization kaldır; C04/C05/C10. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) |
| `TopologyPage.tsx` | ConnectionsWorkspace + ProviderTree + SchemaMappingMatrix + Catalog | Mevcut entity API’leri | Secret kaynak bağımlılığını ayır; C03/C04. [R16 · TopologyPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/TopologyPage.tsx) |
| `OracleConnectionVersionForm.tsx` | RevisionWizard + CredentialWriteForm + LifecyclePanel | JDBC/JNDI, Service/SID ve lifecycle ayrımı | Write-only credential/policy API gelmeden sahte password formu yok. [R26 · Oracle Bağlantı Sürümü Formu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/OracleConnectionVersionForm.tsx) |
| `PublicationsPage.tsx` | ObjectRunnableVersions; legacy advanced detail | Immutable publication kaydı, hash/onay | C06; ana menü kaldırılabilir ama çözümleme tamamlanmadan mevcut kayıt erişimi koparılmaz. [R17 · PublicationsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/operations/PublicationsPage.tsx) |
| `RunsPage.tsx` | OperationsWorkspace + RunFilters + typed tree | Mevcut manuel run idempotency | C07/C08; geçişte dürüst liste görünümü korunur. [R18 · RunsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunsPage.tsx) |
| `RunDetailPage.tsx` | RunSummary + StepDetail + EventTimeline | Olay ve cancellation temeli | C07; raw JSON advanced; unknown state/metric. [R19 · RunDetailPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunDetailPage.tsx) |
| `core/api/client.ts` | Tiplenmiş Problem adapter ve request cancellation desteği | code/correlationId, merkezi header | C09; feature’ların network error’ı `[]` yapması engellenir. [R25 · Ortak API İstemcisi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/core/api/client.ts) |
| `core/i18n` ve feature copy | Ortak terimler + feature namespace + formatter | Varsayılan EN ve TR tercihi | Key parity, action case, hard-coded locale temizliği. [R24 · Dil Başlatma ve Tercihi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/core/i18n/index.ts) |
| `styles.css` ve feature CSS | Semantic token + shared primitives | Var olan light/dark/system tercihi | Primary on-color, density, focus, responsive bütçe. [R23 · Ortak CSS ve Tema Token’ları](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/styles.css) |

Refactor hedefi her eski dosyayı silmek değildir. API davranışını değiştirmeyen ayrıştırmalar önce karakterizasyon testleriyle korunur. Mevcut temel üzerine küçük, doğrulanabilir dilimler uygulanır; bütün UI’ın tek seferde değiştirilmesi önerilmez.

## 17. Gerekli backend/API contract değişiklikleri

### 17.1 Mevcut sınır ve sözleşme yaklaşımı

Bugünkü API’lerde bağlantı lifecycle, schema binding, tanım/sürüm ve manuel run kaynakları mevcut. ExecutionController yalnız run list/detail/events/cancel yüzeyini sunuyor; frontend’de kullanılabilirlik durumunu önceden açıklayan kapsamlı bir context/capability veya typed step API’si doğrulanmadı. Topology istemcisinde write-only credential değil secret-reference seçimi bulunuyor. [R27 · Bağlantılar API İstemcisi ve Tipleri](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts) [R31 · ExecutionController.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java)

Aşağıdaki endpoint isimleri **önerilen sözleşmedir**, mevcut çağrılar değildir. `/api/v2` kullanımı UI route’uyla karıştırılmaz. Yeni endpoint eklenirken mevcut v1/v2 istemcilerin davranışı sessizce değiştirilmez. Bütün uçlar proje yetkisi, doğru HTTP hata semantiği, optimistic concurrency ve gerekiyorsa idempotency uygular. UI’dan buton kaldırılması authorization kaldırılmasını gerektirmez.

**Son commit farkı:** Kolon düzeyindeki `canonicalType` ve `executionCapability` alanları artık mevcut discovery DTO’sundadır. C05 bunları yeniden icat etmez; iş/işlem profili, runtime readiness, limitler ve validation gerekçelerini tamamlar. Temiz `akis` baseline geçişi nedeniyle C01–C11’in kalıcı depolama değişiklikleri ilgili domain’in kabul edilmiş yeni şema sözleşmesiyle eşgüdümlü olmalıdır; eski `entegrasyon` yapısına paralel yeni feature tabakası önerilmez. [R36 · Yeni Oracle Kolon Yeteneği Sınıflandırması](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/backend/src/main/java/tr/com/innova/akis/oracle/OracleColumnCapability.java) [R37 · Güncel DiscoveryColumn API Tipi](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/frontend/src/features/topology/api.ts) [R38 · Akış Temiz Başlangıç Şeması](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/database/akis-baseline/README.md) [R39 · Veritabanı Yeniden Temellendirme Kararı](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/docs/architecture/DATABASE_REBASE_AUDIT.md)

### 17.2 Sözleşme kataloğu

| Kimlik | Önerilen yüzey | Asgari veri / davranış | Kabul |
|---|---|---|---|
| C01 | `GET /api/v2/projects/{p}/workspace-context` | Proje özeti, kullanıcının izinli eylemleri, kullanılabilir çalışma alanları, runtime readiness/reasonCode; credential yok | Yetki kaldırma ve feature flag kapatma UI’a açıklanır; tenant/proje izolasyonu korunur |
| C02 | `GET /api/v2/projects/{p}/explorer/nodes` ve `.../search` | Parent/cursor/type/query, stable node IDs, ancestry, hasChildren, nextCursor; snapshot veya tutarlı sıralama | Sayfa geçişinde duplicate/eksik node yok; 20.000 nesne tek response zorunlu değil |
| C03 | `POST .../connections/{c}/revisions` yönetim cephesi; `GET .../connection-policy-schema` | Typed endpoint + write-only credential/provisioning choice + typed policy; backend secret referansını kendi üretir | Response/log/export’ta secret/value/path yok; ENV-only kurulumda unsupported provisioning açık |
| C04 | `POST .../binding-resolutions:preview` | Draft/version/task binding referansları + environment → typed resolved labels/IDs, pinned revision, policy, snapshot, diagnostics | Tanım task JSON’una physical/revision/timeout kopyalanmaz; stale fingerprint reddedilir |
| C05 | `GET .../runtime-capabilities`; `POST .../definitions/{o}/validations` | Schema/ruleset/runtime profile sürümü; supported operations, limits; draft-safe validation; field/task diagnostics | Editor, compiler ve runtime limitleri tutarlı; metadata validation örtülü DB I/O yapmaz |
| C06 | `POST .../definitions/{o}/runnable-versions`; GET list/detail | Immutable definition version + environment + expected binding fingerprint → release/readiness/approval/plan hash | UUID elle girilmez; aynı idempotent hazırlık duplicate release üretmez; eski release korunur |
| C07 | `GET .../operations/objects`; `GET .../runs/{r}/steps`; GET step detail | Nesne/run/step read projection; typed metrics, state/evidence, source timestamps, pagination | Tree raw events’ten kurulmaz; planlanmış/başlamış/kanıtsız adımlar doğru ayrılır |
| C08 | Run/step detail’de `allowedActions`; gerekiyorsa ayrı action-query | CANCEL/START_NEW_ATTEMPT gibi eylemler için allowed, reasonCode, expected stateVersion ve risk özeti | Mevcut olmayan retry/resume aktifleşmez; action POST sunucuda tekrar kontrol edilir |
| C09 | Ortak ProblemDetails ve liste zarfı | `code`, `correlationId`, `violations[]`, `nextCursor`, `serverTime`, `snapshotVersion` | Hata alan/adıma bağlanır; ağ arızası boş diziye dönüştürülmez |
| C10 | Sürümlü draft + binding kaydetme / migration sözleşmesi | Draft body ile ayrı task-binding koleksiyonu aynı expectedVersion altında; enabled semantiği ve bağlantı politikasına geçiş | Eski immutable definition/manifest değiştirilmez; hash ve uyumluluk matrisi test edilir |
| C11 | İleriki nesne bundle validate/dry-run/import/export sözleşmesi | Kök nesne, açık dependency closure, referans eşlemesi, çakışma planı, secret taraması | Proje bundle V1 “nesne import” diye yeniden etiketlenmez; bu uçlar gelmeden menü aktif olmaz |

### 17.3 Capability ve doğrulama DTO’su

Örnek aşağıdaki alanlar **tasarım sözleşmesidir**, backend’de bulunduğu iddia edilmez:

```text
RuntimeCapabilities
  profileCode, contractVersion, rulesetVersion
  definitionSchemaVersions[]
  operations[]: code, role, executable, reasonCode, requiresApproval
  limits: maximumTasks, maximumRowsetRows, maximumCommandBytes
  connectionPolicySchemaVersion
  runtimeReadiness: READY | DISABLED | UNAVAILABLE | UNKNOWN
  readinessReasons[]

ValidationResult
  inputRevision, inputHash, rulesetVersion, validatedAt
  definitionValid
  runtimeCompatible
  issues[]:
    code, severity, taskId?, fieldPath?, messageKey, safeArguments,
    suggestedAction?, relatedTaskIds[]
```

`definitionValid=true` ile `runtimeCompatible=false` mümkün ve anlamlıdır. Sadece taslak saklanması için bütün runtime koşulları zorunlu kılınmaz; kullanıcıya eksikliği açıkça gösterilir. Validation sonucu hangi draft hash/revision’a ait olduğunu taşır; kullanıcı sonradan bir karakter değiştirdiğinde önceki “Geçerli” rozeti güncel sonuç sayılmaz.

C05 metadata validation, SQL/shape/snapshot ve bağ referansları üzerinden çalışır. Gerçek Oracle kaynak ön kontrolü mevcut preflight kavramı gibi ayrı açık kullanıcı eylemidir; kullanıcı her SELECT karakterini yazdığında veritabanına gidilmez. [R07 · Prosedür Kaynak Ön Kontrolü](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md)

### 17.4 Bağlar, timeout ve immutable geçiş

Önerilen ayrı draft binding kaydı:

```text
DraftTaskBinding
  taskId
  logicalSchemaUuid
  dataObjectUuid
  bindingRole

ResolutionResult
  taskId, environmentUuid
  logicalSchema: uuid, displayName
  physicalSchema: uuid, displayName
  connection: uuid, displayName, providerCode
  connectionRevision: uuid, versionNumber, lifecycle, runtimeCapability
  snapshot: uuid, fingerprint, capturedAt, compatibilityProfile
  effectivePolicy: version, statementTimeoutSeconds, ...
  resolutionFingerprint, diagnostics[]
```

Task tanımı komut/işlem/sıra/input-output ilişkisini taşır; catalog UUID’leri ve çözümleme koleksiyonu ayrı kayıtlardır. `effectivePolicy` salt okunur sonuçtur. Draft ve binding atomik kaydedilmeli veya başarısız ara durumun açık revizyon modeli olmalıdır; UI’da bir adımı kaydedip eski binding ile “Hazır” göstermeye izin verilmez.

**Migration ilkesi:** Var olan immutable Procedure schema 2 ve release manifestleri yeniden yazılmaz. Yeni tanım/derleyici/manifest sözleşmesi numarası ADR’de ayrılır; eski `ORACLE_PROCEDURE_V1` strict resolver’ı sessizce gevşetilmez. Yeni taslak için önceki timeout tercihi kaybolmaz; “Eski adım politikası” geçiş uyarısı ve seçilen bağlantı politikası farkı sunulur. Geçiş explicit yeni sürüm üretir. Eski run, eski plan ve onay kanıtıyla açıklanabilir kalır. [R28 · ProcedureRuntimePlan.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlan.java) [R29 · ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java)

Fiziksel adların kullanıcı SQL metninde bulunması ile connection host/secret/timeout/revision metadata’sını task JSON’una gömmek farklı konulardır. SQL’deki nesne kullanımı kontrollü compiler/resolver tarafından seçilen katalog bağına karşı doğrulanmalıdır; kullanıcı girdiği için güvenilir kabul edilmez.

### 17.5 Typed çalışma ve adım projection’ı

```text
RunStepView
  stepId, runUuid, parentStepId?
  taskId, ordinal, displayName, operationCode, connectionRole
  definitionVersionUuid, runtimePlanHash
  state, stateVersion
  startedAt?, finishedAt?, durationMs?
  metrics:
    rowsRead?: decimal-string | null
    rowsWritten?: decimal-string | null
    rowsCommitted?: decimal-string | null
    bytesProcessed?: decimal-string | null
  metricEvidence: KNOWN | PARTIAL | UNKNOWN | NOT_APPLICABLE
  commitOutcome: CONFIRMED | ROLLED_BACK | UNKNOWN | NOT_APPLICABLE
  error?: code, safeSummary, correlationId
  planned: boolean
  allowedActions[]
  projectionVersion, observedAt
```

Sıra ve adlar pinned plan’dan; gerçekleşen state ve kanıt typed journal/control-plane verisinden gelmelidir. `succeeded` journal sinyali tek başına bütün hedef transaction’larının commit olduğuna çevrilmez. Commit kanıtı farklıysa ayrıca gösterilir. Eski kayıtta yeterli step kanıtı yoksa `UNKNOWN` veya “Adım ayrıntısı bu çalışma için mevcut değil” kullanılır; event payload’ları tahmin edilerek doldurulmaz. [R28 · ProcedureRuntimePlan.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlan.java) [R35 · Prosedür Typed Execution Journal Sınırı](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureExecutionJournalPort.java)

Ağaç için bütün run/step geçmişi tek response olarak zorunlu değildir. Nesne genişletilince paged run’lar; run genişletilince paged/typed adımlar yüklenebilir. `ordinal` kullanıcıya bir tabanlı sunulacaksa backend internal task index dönüşümü explicit yapılır. Polling aynı request’in üst üste binmesine izin vermez; terminal run’da yavaşlar/durur; görünmeyen sekmede gereksiz trafik azaltılır. Başlangıç için 5 saniyelik aktif-run yenileme **PROTOTİPLE DOĞRULANACAK** öneridir, mevcut SLA değildir. SSE/WebSocket ilk gereksinim değildir.

### 17.6 Çalıştırma cephesi ve izinli eylemler

C06, kullanıcıya scenarioUuid veya publicationUuid ezberletmeden mevcut compile/publish güvenlik zincirini orkestre eder. Birden fazla metadata adımı varsa hazırlama durumları ve idempotent tekrar açık tanımlanır; yarım kalmış kayıt “Çalıştırılabilir” diye sunulmaz. Exact definition version, environment, binding snapshot ve plan hash approval kapsamına girer.

Start isteği server tarafında yetki, feature flag, publication capability, release integrity ve durum uygunluğunu yeniden kontrol eder. `allowedActions` yalnız UI rehberidir, authorization token değildir. UI’dan gönderilen `allowed=true` gibi bir değer kabul ölçütü olmaz. Cancel sonrası tekrar sorgulama yapılır; kullanıcı tıklamasının kabulü ile işlem etkisinin tamamlanması ayrıdır. Gerçek retry/resume protokolü olmadan C08 yalnız bugünkü güvenli eylemleri açıklar; yeni çalışma ile checkpoint devamı birleştirilmez. [R31 · ExecutionController.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java) [R32 · ExecutionFeatureFlags.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionFeatureFlags.java)

### 17.7 API kabul ve güvenlik testleri

**Zorunlu negatif testler:** Başka proje UUID’si; erişimi kaldırılmış nesne; stale expectedVersion; değişmiş binding fingerprint; kapalı runtime; unsupported Procedure shape; 1.001 rowset sınırı; üretici-tüketici arasına adım; farklı target kimliği; eksik onay; aynı idempotency key ile farklı payload; credential içeren validation/log/export örneği. Hepsi açık code ile fail-closed sonuçlanmalıdır.

**Read-model testleri:** Silinmiş nesnenin geçmiş adı, başlamamış adım, NULL metrics, ters sırayla gelen güncelleme, kısmi journal, unknown commit, sayfalar arası duplicate ve yetki değişimi. Projection hiçbir koşulda operator yetkisini aşan SQL/credential/row içeriğini aktarmamalıdır.

## 18. Aşamalı uygulama planı, riskler ve ölçülebilir kabul kriterleri

### 18.1 Uygulama fazları

| Faz | Kapsam | Çıkış kapısı |
|---|---|---|
| 0 — Davranışı sabitle | F06/F07/F08 limit, sıra ve yanlış runtime beklentisi; mevcut akış karakterizasyonu | Sessiz bağı kaybı sıfır; V1 sınırlarının boundary testleri |
| 1 — Ürün kabuğu ve ortak temel | Proje sınırı, üç alan, sade overview, dirty guard, a11y primitives, tema/dil | Projeler arası stale state sıfır; WF01–WF04 klavye geçişi |
| 2 — Sunucu gerçekliğini görünür kıl | C01/C05/C09; connection policy/credential ve resolution | UI yetenek etiketi server contract ile eşleşir; secret echo sıfır |
| 3 — Tasarım ve sürüm hazırlığı | Yeni bağ/timeout sözleşmesi, selected-step editor, object forms, runnable-version facade | Draft/sürüm/release ayrımı; unsupported işlemin çalıştırılamaması |
| 4 — Operasyon ağacı | Typed projection, step detail, event korelasyonu, allowedActions | Ham event parsing olmadan ağaç; belirsiz sonuçta kör retry yok |
| 5 — Ölçek ve uçtan uca kalite | Lazy/paged ağaç, büyük veri fixture’ları, iki dil/tema, manuel AT testleri | Kapasite ve kritik görev kabulü; release geçmişi/deep link regresyonu |
| 6 — Nesne taşınabilirliği | İhtiyaç doğrulandığında C11 dependency-aware nesne bundle | Mevcut proje bundle davranışı bozulmadan güvenli dry-run/import |

Fazlar CI/CD veya GitHub workflow açılmasını, deployment yapılmasını veya kapalı runtime flag’lerinin araştırma adına etkinleştirilmesini içermez. Testler kontrollü geliştirme ortamında ve ayrı onaylı uygulama işi olarak yürütülür. Bu rapor bunları çalıştırmış değildir.

### 18.2 Ölçülebilir kabul profili

| Alan | Hedef / test fixture’ı | Geçiş ölçütü |
|---|---|---|
| Proje izolasyonu | A→B hızlı geçiş; A yanıtı gecikmeli | B başlığı altında A verisi sıfır; eski write gönderimi sıfır |
| Dirty içerik | Nesne/proje/route/dil/tema değişimi | Onaysız içerik kaybı sıfır; Save conflict’inde draft korunur |
| Limit uyumu | Rowset 1/1.000/1.001; task 1.000/1.001; UTF-8 byte sınırı | UI açıklaması ve backend code tutarlı; eski veri sessiz kırpılmaz |
| SQL yeteneği | SELECT *, WHERE/JOIN, serbest PL/SQL, farklı target, nonadjacent input | V1 için runnable sonucu verilmez; reason doğru alana gider |
| Büyük explorer | 20.000 nesne; karmaşık klasörler; 100 derinlik fixture’ı domain limitleriyle uyumlu | Pagination/lazy çalışır; sonsuz recursion yok; odak kaybolmaz |
| Prosedür listesi | 1.000 runnable-limit adım; 10.000 draft-limit görüntüleme fixture’ı | Tek ağır SQL editörü; listeye ulaşma/sıralama bloke etmez; runtime uygunluğu ayrı |
| Eşleme tablosu | 500 kolon; uzun EN/TR adları | Satır/sütun arama, keyboard ve edit doğruluğu korunur |
| Etkileşim performansı | Sabitlenmiş test bilgisayarı/tarayıcıda p95 seçim ve local filtre | Başlangıç hedefi ≤200 ms; donanım ve ölçüm koşulu raporlanmadan “geçti” denmez |
| Ağ/arama | 300 ms debounce başlangıcı; pagination/sıralama | Eski query sonucu yeni query’yi ezmez; her keypress DB probe üretmez |
| Erişilebilirlik | İki dil/tema, keyboard, 320 px, NVDA/VoiceOver örnekleri | Kritik görevde keyboard trap/erişilemeyen kontrol sıfır |
| Renk | Token çiftleri + rendered hover/focus/disabled/read-only | Normal metin gereken yerde ≥4,5:1; gerekli UI sınırları ≥3:1 |
| Gizlilik | Sentetik canary secret, SQL bind değerleri, import/export/log denemeleri | Response/telemetry/export/browser storage’da canary eşleşmesi sıfır |
| Geçmiş doğruluğu | Rename/move/new revision sonrasında eski run | Pinned nesne/sürüm/plan/ortam aynı kalır; eski deep link çalışır |
| Import | Hatalı checksum, fazla boyut, unsupported conflict, dry-run | Write olmayan denemede kayıt oluşmaz; false-success sıfır |

Performans süreleri ve kapasite fixture’ları ürün hedefidir; bu raporda ölçülmüş baseline veya test sonucu değildir. Öncelikle mevcut sürümde baseline alınır, sonra aynı koşullarda değişim karşılaştırılır. Prosedür draft üst sınırı ile runnable üst sınırı ayrı fixture’dır; 10.000 adım runtime’da yürütülmüş gibi test planı yazılmaz.

### 18.3 Risk kaydı

| Risk | Etki | Önlem |
|---|---|---|
| UI sadeleşirken güvenlik sınırının kaldırılması | Yanlış/izinsiz yürütme | Publication, immutable revision, approval, hash ve backend authorization aynen korunur |
| Yeni policy’ye geçerken eski manifestin değişmesi | Geçmişin tekrar üretilememesi | Yeni versioned contract; eski immutable kayıtlar read-compatible kalır |
| Credential formunun ENV provider’da uygulanamaması | Kullanıcıyı çalışmayan akışa sokma | C03 capability; provisioning mümkün değilse açık kurum yönetimli durum |
| Ağaç sanallaştırmasının erişilebilirliği bozması | Klavye/AT kullanım engeli | Stable node identity, aktif node’un DOM yaşamı, gerçek AT denemeleri |
| Typed projection’ın event tahminine dönüşmesi | Yanlış çalışma sonucu | Pinned plan + typed evidence; veri yoksa unknown |
| “Sınırsız” ifadesinin güvenlik limitini kaldırması | Bellek/payload/çalışma riski | Mantıksal UI ölçeği ile server limitlerini ayırma |
| Interface ve Mapping’in iki domain kopyası olması | Sürüm/binding karmaşası | Tek canonical MAPPING; authoring mode açık; ayrı tip için ayrı karar |
| Büyük refactor’da mevcut akışların kaybı | Regresyon | Karakterizasyon testleri, compatibility route, küçük dilimler |
| Ürün belgelerinin farklı tarihlerde kalması | Yanlış sonraki geliştirme | V2 önceliği ve capability tablosunun doküman/servis testleriyle güncellenmesi |

## 19. Kaçınılacak anti-pattern’ler

**Dashboard ile başlamak:** Genel bakışa her şeyi taşımak V2 kararını bozar. Operasyon bilgisinin yeri Operasyonlar’dır; girişte metrik kartı sayısı kalite ölçüsü değildir.

**Güzel görünen ama yalan söyleyen editör:** Kaydedilmiş her SQL’i çalıştırılabilir göstermek; genel PL/SQL textarea’sını runtime capability saymak; 10.000 satır varsayılanını 1.000 sınırının arkasına saklamak kabul edilmez. [R15 · ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) [R28 · ProcedureRuntimePlan.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlan.java) [R29 · ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java)

**Sıralamayı veri silerek düzeltmek:** Geçersizleşen rowset input’unu sessizce kaldırmak veya disabled adımı runtime’dan gizlemek semantik kayıptır. Hareket explicit doğrulanır; bağ korunur ya da değişiklik kullanıcıya açık bir işlem olarak sunulur.

**UI’dan gizleneni backend’den de silmek:** Publication, secret manager, immutable connection revision, approval ve target identity mekanizmaları görsel sadeleşmenin kurbanı olamaz. Kullanıcı adı değişir, güvenlik sınırı değişmez.

**Ham UUID/JSON’u normal kullanıcı yüzeyi yapmak:** UUID route ve API kimliğidir; form girdisi değil. Event JSON, typed step API’sinin yerine geçmez. Ham tanım Advanced altında ve yetkili bağlamdadır.

**Her yerde kart veya ağaç:** Bağlantı listesinde kart, hiyerarşide tree, sütun karşılaştırmasında tablo, kısa kritik kararda dialog kullanılır. Ana navigasyonun tamamı tree’ye dönüşmez; project tree ile run tree aynı anda gereksiz yere gösterilmez.

**Her problemi aynı kırmızı banner’a indirgemek:** Yetki, unsupported capability, kapalı runtime, validation, stale snapshot ve outcome uncertainty farklıdır. Özellikle “Bilinmiyor” ile “0”, “İptal İstendi” ile “İptal Edildi” ayrılmalıdır.

**Frontend onayını yeterli görmek:** Checkbox veya hedef adını yazma, backend authorization ve exact plan approval yerine geçmez. Eylemin görünürlüğü ile eyleme yetki aynı şey değildir.

**Hata olunca boş dizi döndürmek:** Böylece ağ/izin sorunu gerçek boş proje gibi görünür. Hata state’i korunur; kullanıcı eksik verinin farkında olur.

**Tema ve erişilebilirliği final cilası saymak:** Light-only component ekleyip mevcut dark desteğini bozmak, odak yönetimini sonraya bırakmak ve yalnız ARIA rolü koymak kabul edilmez. Mevcut renk çiftindeki 2,38:1 sorun bu yaklaşımın somut örneğidir. [R23 · Ortak CSS ve Tema Token’ları](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/styles.css)

**Sürümler arasında destek varsaymak:** ODI 12c metadata’sını Oracle Database 12c runtime uyumluluğu gibi göstermek; `current-version` ürün sayfasını bütün lisans/sürümlere genellemek; derlenebilir paketi çalıştırılabilir saymak yanlıştır. [R34 · ODI 12c Metadata Envanter Belgesi](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/research/ODI12C_REPOSITORY_METADATA_INVENTORY.md) [R06 · Nesne Kataloğu](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/NESNE_KATALOGU.md)

**Araştırmadan deployment çıkarmak:** Workflow/CI etkinleştirme, runtime flag açma, migration uygulama veya veritabanı üzerinde deneme bu araştırmanın sonucu olarak otomatik başlatılmaz.

## 20. Kaynakça ve erişim tarihi

Bütün dış kaynaklara erişim tarihi **12 Eylül 2026**. Repository bağlantıları tam inceleme başlangıç commit’ine veya açıkça belirtilen son fark kontrolü commit’ine sabittir. Dış kaynaklarda belirli sürüm veya sayfa güncellemesi görüldüğünde kapsamı buna göre sınırlandırılmıştır. Uzun alıntı yapılmamış; ürün özellikleri ile Akış için özgün tasarım önerileri ayrılmıştır.

### 20.1 Repository kaynakları

| Kimlik | Kaynak | Commit kapsamı |
|---|---|---|
| R01 | [README.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/README.md) | `9cf018d` |
| R02 | [docs/IMPLEMENTATION_STATUS.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/IMPLEMENTATION_STATUS.md) | `9cf018d` |
| R03 | [docs/architecture/PRODUCT_EXPERIENCE_V2_DECISIONS.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PRODUCT_EXPERIENCE_V2_DECISIONS.md) | `9cf018d` |
| R04 | [docs/architecture/PROFESSIONAL_PRODUCT_EXPERIENCE.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROFESSIONAL_PRODUCT_EXPERIENCE.md) | `9cf018d` |
| R05 | [docs/architecture/PROJECT_BUNDLE_FORMAT.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROJECT_BUNDLE_FORMAT.md) | `9cf018d` |
| R06 | [docs/architecture/NESNE_KATALOGU.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/NESNE_KATALOGU.md) | `9cf018d` |
| R07 | [docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md) | `9cf018d` |
| R08 | [docs/architecture/MANUAL_RUN_CONTROL_PLANE.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/architecture/MANUAL_RUN_CONTROL_PLANE.md) | `9cf018d` |
| R09 | [docs/research/ODI_BENZERI_ETL_PLATFORMU_TEKNIK_ARASTIRMA_RAPORU.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/research/ODI_BENZERI_ETL_PLATFORMU_TEKNIK_ARASTIRMA_RAPORU.md) | `9cf018d` |
| R10 | [frontend/src/app/App.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/App.tsx) | `9cf018d` |
| R11 | [frontend/src/app/AppShell.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/AppShell.tsx) | `9cf018d` |
| R12 | [frontend/src/features/projects/ProjectOverviewPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/projects/ProjectOverviewPage.tsx) | `9cf018d` |
| R13 | [frontend/src/features/definitions/DefinitionsWorkspace.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/DefinitionsWorkspace.tsx) | `9cf018d` |
| R14 | [frontend/src/features/definitions/ProjectExplorer.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProjectExplorer.tsx) | `9cf018d` |
| R15 | [frontend/src/features/definitions/ProcedureEditor.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/definitions/ProcedureEditor.tsx) | `9cf018d` |
| R16 | [frontend/src/features/topology/TopologyPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/TopologyPage.tsx) | `9cf018d` |
| R17 | [frontend/src/features/operations/PublicationsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/operations/PublicationsPage.tsx) | `9cf018d` |
| R18 | [frontend/src/features/execution/RunsPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunsPage.tsx) | `9cf018d` |
| R19 | [frontend/src/features/execution/RunDetailPage.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/execution/RunDetailPage.tsx) | `9cf018d` |
| R20 | [frontend/package.json](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/package.json) | `9cf018d` |
| R21 | [pom.xml](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/pom.xml) | `9cf018d` |
| R22 | [frontend/src/main.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/main.tsx) | `9cf018d` |
| R23 | [frontend/src/styles.css](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/styles.css) | `9cf018d` |
| R24 | [frontend/src/core/i18n/index.ts](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/core/i18n/index.ts) | `9cf018d` |
| R25 | [frontend/src/core/api/client.ts](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/core/api/client.ts) | `9cf018d` |
| R26 | [frontend/src/features/topology/OracleConnectionVersionForm.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/OracleConnectionVersionForm.tsx) | `9cf018d` |
| R27 | [frontend/src/features/topology/api.ts](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/features/topology/api.ts) | `9cf018d` |
| R28 | [backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlan.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlan.java) | `9cf018d` |
| R29 | [backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureRuntimePlanResolver.java) | `9cf018d` |
| R30 | [backend/src/main/java/tr/com/innova/akis/metadata/DefinitionContentValidator.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/metadata/DefinitionContentValidator.java) | `9cf018d` |
| R31 | [backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionController.java) | `9cf018d` |
| R32 | [backend/src/main/java/tr/com/innova/akis/execution/ExecutionFeatureFlags.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ExecutionFeatureFlags.java) | `9cf018d` |
| R33 | [frontend/src/app/App.test.tsx](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/frontend/src/app/App.test.tsx) | `9cf018d` |
| R34 | [docs/research/ODI12C_REPOSITORY_METADATA_INVENTORY.md](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/docs/research/ODI12C_REPOSITORY_METADATA_INVENTORY.md) | `9cf018d` |
| R35 | [backend/src/main/java/tr/com/innova/akis/execution/ProcedureExecutionJournalPort.java](https://github.com/mehmet-karacan/akis/blob/9cf018ddcdfdef0c9878ce42401bdac648eb1f41/backend/src/main/java/tr/com/innova/akis/execution/ProcedureExecutionJournalPort.java) | `9cf018d` |
| R36 | [backend/src/main/java/tr/com/innova/akis/oracle/OracleColumnCapability.java](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/backend/src/main/java/tr/com/innova/akis/oracle/OracleColumnCapability.java) | `05cffdc` |
| R37 | [frontend/src/features/topology/api.ts](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/frontend/src/features/topology/api.ts) | `05cffdc` |
| R38 | [database/akis-baseline/README.md](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/database/akis-baseline/README.md) | `05cffdc` |
| R39 | [docs/architecture/DATABASE_REBASE_AUDIT.md](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/docs/architecture/DATABASE_REBASE_AUDIT.md) | `05cffdc` |
| R40 | [compare/9cf018ddcdfdef0c9878ce42401bdac648eb1f41...05cffdcf2170c81eb43ee19115f059d557ad50e2](https://github.com/mehmet-karacan/akis/compare/9cf018ddcdfdef0c9878ce42401bdac648eb1f41...05cffdcf2170c81eb43ee19115f059d557ad50e2) | `9cf018d → 05cffdc` |

### 20.2 Resmi ürün ve standart kaynakları

| Kimlik | Yayıncı ve doğrudan kaynak | Erişim tarihi |
|---|---|---|
| S01 | Oracle — [ODI 12c: Creating and Using Mappings](https://docs.oracle.com/middleware/1221/odi/develop/mappings.htm) | 12 Eylül 2026 |
| S02 | Oracle — [ODI 14.1.2: User Interface](https://docs.oracle.com/en/middleware/fusion-middleware/data-integrator/14.1.2/quick-ref/oracle-data-integrator-user-interface.html) | 12 Eylül 2026 |
| S03 | Informatica — [Cloud Data Integration: Explore Page](https://docs.informatica.com/integration-cloud/data-integration/current-version/introduction/data-integration-tools/explore-page.html) | 12 Eylül 2026 |
| S04 | Microsoft — [Azure Data Factory: Visually Monitor Pipelines](https://learn.microsoft.com/en-us/azure/data-factory/monitor-visually) | 12 Eylül 2026 |
| S05 | Apache Software Foundation — [Airflow: UI / Screenshots](https://airflow.apache.org/docs/apache-airflow/stable/ui.html) | 12 Eylül 2026 |
| S06 | Dagster — [Dagster Webserver and UI](https://docs.dagster.io/guides/operate/webserver) | 12 Eylül 2026 |
| S07 | Prefect — [Prefect 3: States](https://docs.prefect.io/v3/concepts/states) | 12 Eylül 2026 |
| S08 | Apache Software Foundation — [NiFi User Guide](https://nifi.apache.org/docs/nifi-docs/html/user-guide.html) | 12 Eylül 2026 |
| S09 | dbt Labs — [About the Studio IDE](https://docs.getdbt.com/docs/platform/studio-ide/develop-in-studio) | 12 Eylül 2026 |
| S10 | GitHub — [Primer: TreeView](https://primer.style/product/components/tree-view/) | 12 Eylül 2026 |
| S11 | IBM — [Carbon: Tree View Usage](https://carbondesignsystem.com/components/tree-view/usage/) | 12 Eylül 2026 |
| S12 | IBM — [Carbon: Tree View Accessibility](https://carbondesignsystem.com/components/tree-view/accessibility/) | 12 Eylül 2026 |
| S13 | Google — [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) | 12 Eylül 2026 |
| S14 | W3C — [Web Content Accessibility Guidelines 2.2](https://www.w3.org/TR/WCAG22/) | 12 Eylül 2026 |
| S15 | W3C WAI — [APG: Tree View Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/) | 12 Eylül 2026 |
| S16 | W3C WAI — [APG: Treegrid Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treegrid/) | 12 Eylül 2026 |
| S17 | W3C WAI — [APG: Tabs Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/) | 12 Eylül 2026 |
| S18 | W3C WAI — [APG: Dialog (Modal) Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/) | 12 Eylül 2026 |
| S19 | W3C WAI — [APG: Combobox Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/) | 12 Eylül 2026 |
| S20 | W3C WAI — [Understanding 1.4.3: Contrast (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) | 12 Eylül 2026 |
| S21 | W3C WAI — [Understanding 1.4.11: Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html) | 12 Eylül 2026 |
| S22 | W3C WAI — [Understanding 2.5.8: Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) | 12 Eylül 2026 |
| S23 | W3C WAI — [Understanding 1.4.10: Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html) | 12 Eylül 2026 |
| S24 | W3C WAI — [Understanding 2.4.11: Focus Not Obscured (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html) | 12 Eylül 2026 |
| S25 | W3C WAI — [Understanding 2.5.7: Dragging Movements](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html) | 12 Eylül 2026 |
| S26 | Oracle — [Oracle Database 19c: TRUNCATE TABLE](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/TRUNCATE-TABLE.html) | 12 Eylül 2026 |
| S27 | OWASP — [Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html) | 12 Eylül 2026 |

### 20.3 Kanıt ve erişim sınırlamaları

Girdi: `AKIS_UI_UX_ARASTIRMA_PROMPTU.md` adlı yüklenen araştırma kapsamı. Bu dosyadaki bağlayıcı ürün kararları korunmuştur. Web karşılaştırması bir ürün satın alma/lisans uygunluğu görüşü değildir. Uygulama çalışma testi, gerçek Oracle bağlantısı, ham yerel ODI envanter dosyalarının doğrulanması, kullanıcı görüşmesi ve ekran okuyucu testi yapılmamıştır.

Material 3 ana sayfa içeriği JavaScript gerektirdiği için Google’ın resmi Compose dokümantasyonu kullanılmıştır. CodeMirror’ın resmi site/rehber erişimi başarısız olduğu için sürüm/uyumluluk/performans iddiası kurulmamıştır. Kaynak kapsamının dışına çıkan öneriler Hedef, VARSAYIM veya PROTOTİPLE DOĞRULANACAK olarak işaretlenmiştir.

**Teslim kontrolü:** `git diff --no-index --check /dev/null /mnt/data/docs/research/AKIS_UI_UX_ARASTIRMA_RAPORU.md` çalıştırıldı; whitespace hatası raporlanmadı. Bu, yalnız oluşturulan rapor dosyasının kontrolüdür; erişilemeyen yerel repository çalışma ağacında `git diff --check` çalıştırıldığı iddia edilmez. Dosya sandbox’ta oluşturuldu; GitHub’da değişiklik, commit veya push yapılmadı.

## Ek — Uygulama iş listeleri ve tek önerilen sıra

Bu listeler araştırma sonucudur; otomatik onaylanmış kod geliştirme talimatı değildir. **S/M/L/XL** göreli büyüklüktür: S dar düzenleme, M birkaç bileşen/sözleşme, L çoklu ekran veya servis, XL domain geçişi ve çapraz katman testleri. Süre ya da takvim tahmini değildir. “Backend gerektirmez” ifadesi backend güvenlik doğrulamasının kaldırılması anlamına gelmez.

### A. Hemen uygulanabilecek, backend değişikliği gerektirmeyen işler

| Sıra / iş | Bağımlılık | Risk | Boyut | Test edilebilir kabul |
|---|---|---|---|---|
| A01 — V1 limit ve SQL uyumluluğunu dürüst göster | Yok | Orta; eski değerleri kırpmamak | M | Yeni rowset varsayılanı V1 sınırında; 1.001 satır uyarılı; mevcut içerik sessiz değişmez; unsupported SQL runnable etiketi almaz |
| A02 — Sıralamada rowset bağı kaybını kaldır | Yok | Yüksek; semantik değişiklik | M | Illegal reorder hiçbir task/input’u değiştirmez; legal pair move tüm bağı korur; undo/kimlik testleri |
| A03 — Overview ve ana navigasyonu sadeleştir | Yok | Düşük | S | Üç alan; “Bağlantılar”; overview run isteği/metrics yok; export proje menüsünde; eski release URL erişimi korunur |
| A04 — Ortak erişilebilir primitives ve tema token’ları | Yok | Orta; bütün ekranlara yayılır | L | Dialog odak/geri dönüş; tabs/menu klavye; dark primary kontrast; iki tema component testleri |
| A05 — Proje geçişi, eski yanıt ve dirty guard | A04 | Yüksek; draft kaybı | L | A→B gecikmeli istek B’yi ezmez; route/proje/dil değişiminde onaysız draft kaybı yok |
| A06 — Terim ve formatter birliği | A04 | Düşük | M | EN/TR key/label tutarlı; Projeyi Dışa Aktar doğru; canonical kısaltmalar ve SQL içeriği değişmez |
| A07 — Explorer expansion, filtre ve context menu düzeltmesi | A04, A05 | Orta | M | Refresh kapalı dalı açmaz; filtre öncesi durum geri döner; menü mouse/keyboard eşdeğer |
| A08 — Selected-step yerleşimi, mevcut semantiği koruyarak | A01, A02, A04, A05 | Orta | L | Tek ağır editör; kaydetme/selection/sıra kimliği testleri; binding/policy entegrasyonu gelmeden eski bağlantı akışı bozulmaz |
| A09 — Bundle akışını anlaşılır ve explicit yap | A04, A06 | Orta; write onayı | M | Validate→dryRun=true→açık import; mevcut projeye merge vaadi yok; unsupported policies aktif değil |
| A10 — Var olan domain alanları için structured form ve Advanced ayrımı | A04, A06, A07 | Orta | L | Desteklenen nesne alanları round-trip kayıpsız; form tamamlanmadan mevcut düzenleme yolu sessizce kaldırılmaz; JSON ana yüzey olmaz |

A01’deki frontend limit kontrolü geçici ve belirli V1 profile’a sabittir; B01 sonrasında C05 capability’den beslenir. Bu geçiş, aynı limitin iki yerde bağımsız kalmasını engelleyen kabul testine dahildir.

### B. Önce backend/API sözleşmesi gerektiren işler

**Ortak ön koşul:** İlgili domain için temiz `akis` baseline/API geçiş sınırı ve mevcut kayıtların korunma yaklaşımı netleşmiş olmalıdır. Bu kapı, aşağıdaki işleri eski şemaya yeni bağımlılık ekleyerek uygulamayı engeller; bağımsız veritabanı yeniden temellendirme görevi bu raporun kapsamına alınmaz. [R38 · Akış Temiz Başlangıç Şeması](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/database/akis-baseline/README.md) [R39 · Veritabanı Yeniden Temellendirme Kararı](https://github.com/mehmet-karacan/akis/blob/05cffdcf2170c81eb43ee19115f059d557ad50e2/docs/architecture/DATABASE_REBASE_AUDIT.md)

| Sıra / iş | Bağımlılık | Risk | Boyut | Test edilebilir kabul |
|---|---|---|---|---|
| B01 — C01/C05/C09 context, capability ve diagnostics | Faz 0 bulguları; A01 testleri | Yüksek; yanlış yetenek sunumu | L | Aynı ruleset UI/compiler/runtime’da; disabled vs unsupported ayrımı; field/task pointer; proje yetki negatif testleri |
| B02 — C03 credential/policy ve revision yönetim cephesi | B01 | Yüksek; secret ve immutable policy | L | Write-only credential; ENV-only provider’da açık unsupported; test≠activate; secret sızıntı fixture’ı sıfır |
| B03 — C04 typed schema/connection resolution | B01 | Yüksek; yanlış hedef | L | Logical+environment exact physical/revision’a çözülür; stale fingerprint reddedilir; UI label’ları aynı DTO’dan |
| B04 — C10 draft binding ve timeout sözleşme geçişi | B02, B03 | Yüksek; eski manifest uyumu | XL | Yeni versioned contract; eski immutable kayıt byte/hash değişmez; draft ve binding tutarlılığı; disabled step semantiği net |
| B05 — C06 nesne bağlamlı runnable-version facade | B01, B03, B04 | Yüksek; onay/yayın | L | UUID girişi yok; idempotent hazırlık; exact plan onayı; DEFINITION_ONLY çalıştırılamaz |
| B06 — C07 typed run/step read projection | B01 | Yüksek; yanlış sonuç kanıtı | L | Pinned plan+typed journal; raw event parse yok; unknown/null/commit ayrımı; geçmiş run doğru |
| B07 — C08 action availability ve güvenli run UX | B05, B06 | Yüksek; duplicate/yanlış retry | L | Mevcut queued cancel doğru; runtime kapalıysa açıklama; desteklenmeyen retry/resume disabled; POST yeniden authorization |
| B08 — C02 lazy/paged explorer ve operasyon ölçeği | B01, B06 | Orta | L | 20.000 nesne ve çok run fixture’ında cursor tutarlılığı; keyboard selection sayfa değişiminde korunur |
| B09 — Bağlamlı Procedure/Connections/Operations UI bütünleştirmesi | A08, B02–B08 | Yüksek; çapraz ekran tutarlılığı | XL | WF06/WF07/WF08 bütün kabul kriterleri; aynı resolved target; typed tree; iki dil/tema/AT uçtan uca |
| B10 — C11 dependency-aware nesne taşınabilirliği | B04, B05, A09 | Yüksek; eksik bağımlılık/yanlış import | L | Nesne dependency closure açık; dry-run; çakışma/secret testi; proje bundle V1 regresyonu yok |

B07, genel retry/resume motoru yazılmasını otomatik kapsamına almaz. Var olmayan eylemler için açıklayıcı availability ve kapalı durum yeterlidir; gerçek runtime genişlemesi ayrı güvenlik tasarımı gerektirir. B10 tamamlanıncaya kadar bağımsız nesne import/export özellikleri aktif gösterilmez.

### Tek önerilen uygulama sırası

**A01 → A02 → A03 → A04 → A05 → A06 → A07 → A08 → A09 → A10 → B01 → B02 → B03 → B04 → B05 → B06 → B07 → B08 → B09 → B10.**

Her iş kendi kabul testini geçmeden sonraki bağımlı iş tamamlanmış sayılmaz. Aşama boyunca erişilebilirlik ve güvenlik testleri yürür; B09’da ilk kez düşünülmez. Bu sıra mevcut uygulamayı kullanılabilir tutarken önce yanlış yönlendirmeyi ve veri kaybı riskini azaltır, ardından yeni API’lerle nihai deneyimi kurar. Çalışma bitiş ölçütü daha çok ekran veya daha güzel kartlar değil; kullanıcının **doğru projede, doğru nesne sürümünü, doğru bağlantı/ortamda, desteklenen işlem sınırları içinde hazırlayıp çalıştırması ve sonucun kanıtını anlayabilmesidir**.
