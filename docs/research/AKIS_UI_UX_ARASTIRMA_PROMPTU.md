# Akış Kurumsal UI/UX ve Ürün Deneyimi Araştırma Promptu

## Önerilen çalışma profili

- Model: **GPT-5.6 Sol**
- Mod: **Pro**
- Reasoning effort: ortam destekliyorsa `max`, değilse `xhigh`
- Web araştırması: **açık ve zorunlu**
- Çıktı: kaynaklı, uygulanabilir, tek bir Markdown araştırma raporu

---

# Görev

Sen kıdemli bir kurumsal ürün tasarımcısı, UX araştırmacısı, bilgi mimarı,
frontend mimarı ve veri entegrasyonu uzmanısın. Oracle Data Integrator benzeri,
fakat daha sade ve öğrenilebilir bir veri entegrasyon platformu olan **Akış** için
kurumsal seviyede UI/UX araştırması yap ve uygulanabilir hedef tasarım raporu üret.

Bu görev bir görsel süsleme çalışması değildir. Hedef; kullanıcının bağlantı,
fiziksel/mantıksal şema, interface/mapping, prosedür, paket, değişken, sekans ve
çalıştırma kavramlarını güvenli ve tutarlı biçimde yönetebildiği profesyonel bir
ürün deneyimi tanımlamaktır.

Kod geliştirme. Uygulama kaynaklarını değiştirme. Veritabanına bağlanma ve DDL/DML
çalıştırma. Yalnız repoyu salt okunur incele, güncel web araştırması yap ve rapor
dosyasını oluştur.

## 1. Önce repository'nin güncel durumunu incele

Repository:

```text
https://github.com/mehmet-karacan/akis
branch: main
```

Çalışmaya başlamadan önce:

1. `git status`, `git remote -v`, `git branch --show-current` ve kısa log ile ortamı
   doğrula.
2. `git fetch origin main` ile uzaktaki son durumu salt okunur getir.
3. Kullanıcının çalışma ağacını resetleme, checkout etme, temizleme veya üzerine
   yazma. Yerel HEAD farklıysa `origin/main` içeriğini `git show`/`git diff` ile
   incele ve raporda hangi commit'i esas aldığını açıkça yaz.
4. En az şu dosyaları tamamen oku:

```text
README.md
docs/IMPLEMENTATION_STATUS.md
docs/architecture/PRODUCT_EXPERIENCE_V2_DECISIONS.md
docs/architecture/PROFESSIONAL_PRODUCT_EXPERIENCE.md
docs/architecture/PROJECT_BUNDLE_FORMAT.md
docs/architecture/NESNE_KATALOGU.md
docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md
docs/architecture/MANUAL_RUN_CONTROL_PLANE.md
docs/research/ODI_BENZERI_ETL_PLATFORMU_TEKNIK_ARASTIRMA_RAPORU.md
frontend/src/app/App.tsx
frontend/src/app/AppShell.tsx
frontend/src/features/projects/ProjectOverviewPage.tsx
frontend/src/features/definitions/DefinitionsWorkspace.tsx
frontend/src/features/definitions/ProjectExplorer.tsx
frontend/src/features/definitions/ProcedureEditor.tsx
frontend/src/features/topology/TopologyPage.tsx
frontend/src/features/operations/PublicationsPage.tsx
frontend/src/features/execution/RunsPage.tsx
frontend/src/features/execution/RunDetailPage.tsx
```

Dosya adları değişmişse `rg --files` ile karşılıklarını bul. Yalnız dokümanlara
güvenme; mevcut route, component, API client, localization, CSS/token ve test
yapısını kaynak koddan doğrula. Mevcut davranış ile hedef kararları açıkça ayır.

## 2. Değiştirilemez ürün kararları

Aşağıdaki kararları tartışmaya açma; araştırma bunların en iyi nasıl uygulanacağını
belirlesin:

1. Girişten sonra proje bağlamı zorunludur. Son geçerli proje açılabilir; yoksa
   proje seçme/oluşturma kapısı gösterilir. Proje daha sonra değiştirilebilir.
2. Proje Genel Bakış ekranı şimdilik sade bir giriş ekranıdır; operasyonel metrik,
   detaylı durum ve yoğun kart panosu göstermez.
3. Dışa aktarma ana CTA değildir. Proje işlemleri/Ayarlar altında bulunur. Türkçe
   buton metni **Projeyi Dışa Aktar** biçimindedir. Görünür proje durumu yalnız
   dikkat gerektiğinde gösterilir.
4. Kullanıcı arayüzünde “Topoloji” yerine **Bağlantılar** kullanılır. Sağlayıcı
   odaklı yapı hedeflenir: örneğin `Oracle > SKY > fiziksel şemalar`; mantıksal
   şemalar ve ortam eşlemeleri aynı anlaşılır model içinde yönetilir.
5. “Gizli bilgi referansları” kullanıcı arayüzünden kaldırılır. Parola/secret
   güvenliği backend'de referans/secret mekanizmasıyla sürer; değerler UI'a geri
   dönmez ve export edilmez.
6. Proje altında kalıcı klasör/nesne ağacı bulunur. Interface, Mapping, Prosedür,
   Paket, Değişken ve Sekans gibi nesneler klasörlerde yer alabilir.
7. Uzun “Yapılandır / Tasarla / İşlet” sidebar grupları yerine az sayıda geniş ve
   anlaşılır çalışma alanı tasarlanır. Tanım/geliştirme tarafı ile çalışma/operasyon
   tarafı net ayrılır.
8. Prosedürler sınırsız sayıda sıralı adımdan oluşabilir. Her adımda ad, işlem türü,
   kaynak/hedef rolü, bağlantı, mantıksal şema, çözümlenen fiziksel şema ve rolüne
   uygun SQL editörü anlaşılır biçimde sunulur.
9. Kaynak SELECT ile hedef INSERT/TRUNCATE/istatistik toplama komutları görsel ve
   semantik olarak ayrıdır. Timeout adımın değil bağlantı politikasının parçasıdır.
10. “Publications/Yayınlar” ana navigasyonda gösterilmez. Immutable yayın/release
    mekanizması backend güvenlik sınırı olarak kalır; kullanıcı bunu nesne bağlamında
    **Çalıştırılabilir Sürüm** veya benzeri anlaşılır bir kavramla görür.
11. Çalıştırmalar ağaç görünümündedir: nesne/paket/prosedür/interface → run → sıralı
    adımlar. Seçilen düğümün ayrıntısı ayrı panelde gösterilir.
12. JSON normal çalışma yüzeyi değildir. Proje/nesne import-export, sağ tık bağlam
    menüsü veya **Gelişmiş > Ham Tanımı Görüntüle** altında bulunur.
13. Varsayılan dil İngilizcedir; Türkçe tam desteklenir. Canonical kısaltmalar SQL,
    JSON, JDBC, JNDI, SID, UUID olarak korunur. Türkçe eylem butonları ürün kararı
    gereği başlık düzeninde tutarlı yazılır.
14. CI/CD ve GitHub workflow'ları varsayılan olarak kapalı kalır. Araştırma bunları
    etkinleştirmeyi veya deployment yapmayı önermesin.

## 3. Mevcut teknik gerçekler

- Kontrol/metadata veritabanı PostgreSQL'dir.
- Backend Java/Spring Boot, frontend React/TypeScript/Vite tabanlıdır; repodan
  sürümleri ve kullanılan kütüphaneleri doğrula.
- İlk gerçek kullanım Oracle → Oracle'dır. Oracle 19c kullanımda olsa da 12c
  metaverisi ve uyumluluğu da desteklenmelidir.
- SKY kaynak, GPU hedef bağlantı örnekleridir; gerçek host, kullanıcı, parola veya
  secret değerlerini rapora alma.
- Runtime bugün kontrollü Oracle V1 işlemleri uygular. UI desteklenmeyen “serbest
  SQL/PLSQL” yeteneği varmış gibi davranmamalıdır.
- Bağlantı/provider, fiziksel şema, immutable bağlantı sürümü ve timeout bilgileri
  prosedür adımı JSON'una kopyalanmamalı; katalog/şema/ortam bağlarından
  çözümlenmelidir.
- Kaynak ve hedef arasında veri, sınırlı bir rowset/batch sözleşmesiyle taşınır;
  tek bir cross-database SQL olarak modellenmez.
- Raw event JSON'undan step ağacı tahmin edilmemelidir; typed run-step backend
  projection/API gereklidir.

Bu gerçeklerle çelişen mevcut UI alanlarını, doğrulama sınırlarını veya API
eksiklerini dosya ve mümkünse satır referanslarıyla belirt.

## 4. Zorunlu web araştırması

Yalnız model belleğine dayanma. Güncel ve doğrudan sayfaları açıp oku. Öncelik:

1. resmi ürün dokümantasyonu ve tasarım sistemleri,
2. WCAG/WAI ve platform standartları,
3. resmi açık kaynak repository ve güncel ürün ekran dokümantasyonu,
4. gerekirse güvenilir bağımsız UX analizi.

En az şu ürün/kaynak ailelerini karşılaştır:

- Oracle Data Integrator 12c/14c navigasyon ve nesne modeli,
- Informatica Cloud/Data Integration,
- Azure Data Factory veya Microsoft Fabric Data Factory,
- Apache Airflow,
- Dagster,
- Prefect,
- Apache NiFi,
- dbt Cloud veya benzer bir veri geliştirme çalışma alanı,
- GitHub Primer, IBM Carbon, Material Design 3 ve erişilebilir kurumsal tasarım
  sistemi örnekleri,
- WCAG 2.2 AA ve WAI-ARIA tree/treegrid, tabs, dialog, combobox kalıpları.

Bir ürünü kopyalama. Her örnek için yalnız Akış'a aktarılabilecek desenleri,
kaçınılması gereken karmaşıklıkları ve lisans/marka bağımsız tasarım sonucunu yaz.
Ekran görüntüsü veya pazarlama sayfasından çıkarım yapıyorsan bunu **Çıkarım** diye
etiketle.

Değişebilen her ürün yeteneğini güncel resmi kaynaktan doğrula. Her önemli iddianın
yanına doğrudan Markdown bağlantısı ekle. Arama sonuç sayfasını kaynak gösterme.
Araştırma kesim tarihini yaz. Uzun alıntı yapma.

## 5. Araştırma soruları

### 5.1 Bilgi mimarisi

- Proje kapısı, global shell ve proje workspace sınırı nasıl olmalı?
- Ana navigasyon kaç öğeden oluşmalı ve hangi adlarla sunulmalı?
- Development/Operations/Connections ayrımı kullanıcı görevleriyle uyumlu mu?
- Proje folder tree hangi ekranlarda kalıcı, hangi ekranlarda gizlenebilir olmalı?
- Breadcrumb, deep link, arama, favori/recent ve sağ tık menüsü nasıl davranmalı?
- Büyük nesne sayısında sanallaştırma, lazy loading ve filtreleme nasıl olmalı?

### 5.2 Bağlantılar ve şemalar

- Provider → connection → revision → physical schema → logical schema →
  environment mapping modeli kullanıcıya hangi seviyede gösterilmeli?
- Oracle JDBC/JNDI, Service Name/SID, test, taslak/aktif revision ve hata durumları
  en az bilişsel yükle nasıl yönetilmeli?
- Kart ile ağaç birlikte nasıl kullanılmalı; hangi bilgi kartta, detay panelinde ve
  gelişmiş alanda olmalı?
- Secret güvenliği korunurken parola giriş/değiştirme deneyimi nasıl olmalı?

### 5.3 Prosedür ve adım editörü

- Kaynak ve hedef adımlar nasıl ayırt edilmeli?
- Sıralı adım listesi, seçilen adım editörü ve SQL alanı için en uygun master-detail
  yerleşimi nedir?
- Bağlantı/mantıksal şema/fiziksel şema seçim ve çözümleme durumu nasıl anlatılmalı?
- TRUNCATE gibi riskli işlemler nasıl işaretlenmeli ve onaylanmalı?
- Adım ekleme, çoğaltma, sıralama, devre dışı bırakma, doğrulama ve hata gösterimi
  nasıl tasarlanmalı?
- SQL editöründe hangi minimum profesyonel özellikler gerekir; hangileri MVP dışıdır?

### 5.4 Çalıştırma ve gözlemlenebilirlik

- Execution tree ve detail pane hangi hiyerarşiyi göstermeli?
- Running/success/failed/cancelled/uncertain/approval-required durumları renk dışında
  nasıl ayrıştırılmalı?
- Adım süresi, satır sayısı, hata, retry, iptal ve yeniden çalıştırma eylemleri nasıl
  yerleştirilmeli?
- Event timeline, step tree ve teknik audit detayları birbirine nasıl bağlanmalı?
- SQL/credential sızdırmadan ne kadar operasyonel ayrıntı gösterilmeli?

### 5.5 Görsel sistem ve içerik standardı

- Masaüstü ağırlıklı kurumsal uygulama için grid, spacing, yoğunluk, panel genişliği,
  tipografi, radius, border, elevation ve responsive breakpoint önerileri nedir?
- Light/dark tema gerekli mi; gerekiyorsa hangi aşamada?
- Semantic color token'ları ve durum ikonları nasıl tanımlanmalı?
- Buton hiyerarşisi, destructive eylem, overflow menüsü, drawer/modal seçimi ve form
  standardı nedir?
- İngilizce/Türkçe metin genişliği, tarih/sayı biçimi ve terminoloji nasıl test edilir?

## 6. Zorunlu rapor çıktısı

Raporu şu dosyaya yaz:

```text
docs/research/AKIS_UI_UX_ARASTIRMA_RAPORU.md
```

Rapor tek başına okunabilir olmalı ve şu bölümleri içermelidir:

1. **Yönetici özeti ve kesin öneri**
2. **İncelenen commit, repository mevcut durum envanteri**
3. **Kullanıcı rolleri, ana görevler ve kritik kullanıcı akışları**
4. **Rakip/benzer ürün karşılaştırma matrisi**
5. **Hedef bilgi mimarisi ve route haritası**
6. **Global shell, proje seçici ve navigasyon sözleşmesi**
7. **Ekran bazlı ayrıntılı tasarım spesifikasyonları**
8. **Bağlantılar/fiziksel-mantıksal şema deneyimi**
9. **Prosedür ve sınırsız sıralı adım editörü**
10. **Interface/mapping, paket, değişken ve sekans yerleşimi**
11. **Çalıştırma ağacı, step detail ve operasyon deneyimi**
12. **Design system foundation ve component inventory**
13. **İngilizce/Türkçe terminoloji ve metin standardı tablosu**
14. **WCAG 2.2 AA ve klavye erişilebilirliği kontrol listesi**
15. **Loading, empty, error, permission, stale ve destructive durum matrisi**
16. **Mevcut component → hedef component etki haritası**
17. **Gerekli backend/API contract değişiklikleri**
18. **Aşamalı uygulama planı, riskler ve ölçülebilir kabul kriterleri**
19. **Kaçınılacak anti-pattern'ler**
20. **Kaynakça ve erişim tarihi**

## 7. Görsel ve spesifikasyon beklentisi

Rapor en az şu düşük sadakatli wireframe'leri Mermaid veya hizalı metin bloklarıyla
göstermelidir:

- proje seçme kapısı,
- global shell ve proje değiştirici,
- sade proje giriş ekranı,
- Connections provider/schema ağacı + kart/detay paneli,
- Development folder/object explorer,
- prosedür adım listesi + kaynak/hedef SQL editörü,
- Operations execution tree + step detail,
- proje/nesne context menu içindeki import/export ve advanced actions.

Her ekran spesifikasyonunda şunlar açıkça yazılmalıdır:

- amaç ve birincil kullanıcı görevi,
- bölgeler ve bilgi hiyerarşisi,
- primary/secondary/overflow eylemleri,
- empty/loading/error/permission durumları,
- klavye ve screen reader davranışı,
- mobil/dar ekran davranışı,
- kullanılan veya ihtiyaç duyulan API verisi,
- kabul kriterleri.

Önerilen route ağacını, component hiyerarşisini ve design token tablosunu somut ver.
Renkler için yalnız hex kodu değil semantic token adı, kullanım amacı ve light/dark
kontrast hedefini belirt. Sayısal spacing, boyut ve breakpoint önerilerini gerekçeli
olarak yaz; keyfi “modern görünmeli” ifadeleri kullanma.

## 8. Karar ve kalite kuralları

- Mevcut uygulamada bulunan ile önerileni birbirine karıştırma: **Mevcut**,
  **Hedef**, **Boşluk** olarak ayır.
- Repo kanıtı olmayan davranışı varmış gibi yazma.
- Backend'in güvenlik sınırlarını görsel sadeleştirme adına kaldırma.
- “Her şeyi dashboard'a koy”, “her yerde kart kullan” veya “ham JSON göster” gibi
  yüzeysel öneriler verme.
- Kullanıcıya raw UUID, internal enum veya secret reference girdisi yaptırma.
- Publication/release kaydını backend'den kaldırmayı önerme.
- Typed run-step API olmadan event JSON'dan ağaç üretmeyi önerme.
- Gerçek serbest SQL desteğini mevcutmuş gibi göstermeme.
- Erişilebilirliği sonradan yapılacak dekoratif bir faza erteleme.
- Genel tavsiye yerine repo dosyalarına, component'lere ve API boşluklarına bağlanan
  uygulanabilir karar üret.
- Önemli bir konuda kanıt yetersizse `DOĞRULANAMADI`, `VARSAYIM` veya
  `PROTOTİPLE DOĞRULANACAK` etiketi kullan.

Raporun sonunda iki ayrı liste ver:

1. **Hemen uygulanabilecek, backend değişikliği gerektirmeyen işler**
2. **Önce backend/API sözleşmesi gerektiren işler**

Her iş için sıra, bağımlılık, risk, yaklaşık göreli büyüklük (`S/M/L/XL`) ve test
edilebilir kabul kriteri belirt. Son olarak tek bir önerilen uygulama sırası ver;
birbiriyle çelişen alternatif yol haritaları bırakma.

## 9. Teslim şekli

- Yalnız araştırma raporu dosyasını oluştur veya güncelle.
- Uygulama koduna, migration'lara, `.env` dosyasına, workflow'lara ve CI/CD
  ayarlarına dokunma.
- Rapor tamamlanınca `git diff --check` çalıştır.
- Commit veya push yapma; değişikliği kullanıcı incelemesine bırak.
- Son mesajda rapor dosyasının tam yolunu, esas alınan commit'i ve en önemli beş
  kararı kısa biçimde yaz.
