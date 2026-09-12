# AKIŞ — Nihai UI/UX ve Bilgi Mimarisi Raporu

**Araştırma ve karar tarihi:** 12 Eylül 2026  
**Repository:** `mehmet-karacan/akis` · **Dal:** `main`  
**İncelenen commit:** `5759b69cb5747793db410209ddcd71244d7975f6`  
**Kapsam:** UI/UX, bilgi mimarisi, kurumsal ürün tasarımı ve bunların gerektirdiği arayüz sözleşmeleri.  
**Teslim:** Uygulama kodu değişikliği, commit veya push içermeyen araştırma ve nihai tasarım şartnamesi.

> **Ana karar:** AKIŞ; proje bağlamı sürekli görünen, **Geliştirme / Operasyonlar / Bağlantılar** çalışma alanlarından oluşan bir ürün olacaktır. Bağlantılar ekranı iş akışı ve yerleşim açısından yeniden tasarlanacaktır. Paket editörü diyagram tabanlı olacaktır. Operasyonun giriş yüzeyi çalıştırma tablosu, bir çalıştırmanın inceleme yüzeyi ise adım ağacı ve seçili adım detayı olacaktır.

**Kanıt sınırı:** Güncel AKIŞ kaynakları GitHub üzerinden commit’e sabitlenerek okundu. Windows çalışma dizinine erişilemedi; Git klonlama denemesi DNS hatasıyla sonuçlandı. Bu nedenle “yerel main çekildi” veya “uygulama çalıştırılarak görüldü” iddiası yoktur. GPU için geçmiş yüklemelerdeki gerçek React/CSS dosyaları incelendi; bunlar güncel Windows kopyasının kanıtı değildir. OSB HTML dosyasına ulaşılamadı. ODI paket dokümanının metni ve yerleşim tablosu okundu; ekran görüntüsü dosyasına erişilemedi. Bu sınırlar aşağıda açıkça ayrılmıştır.

---

## 1. Yönetici Özeti

### 1.1 Ürünün sorunu ve korunacak temel

AKIŞ’ı yeniden isimlendirmek veya her ekranı daha fazla kartla doldurmak yeterli değildir. Esas ihtiyaç; **nesne tasarlama, fiziksel kaynak hazırlama ve çalıştırmayı izleme işlerinin birbirinden ayrılması**, fakat kullanıcının aynı proje içinde kaldığını sürekli görebilmesidir. Mevcut sürüm bu yönde önemli mesafe almıştır: tek proje otomatik seçilmekte, proje üst bardan değiştirilebilmekte, genel bakış sakin bir karşılama ekranı olarak kalmakta ve proje ağacında Akışlar → Ortak bileşenler → Modeller sırası bulunmaktadır. Bunlar yeniden icat edilmeyecek, tamamlanacaktır. [K02] [K03] [K17] [K18]

Buna karşılık `TopologyPage.tsx` bağlantılar, fiziksel/mantıksal şemalar, ortamlar, eşleştirmeler, keşif ve modelleri aynı çalışma alanı içinde taşımaktadır. Mapping’in görsel editöründe JSON ifade girişi, nesne araç çubuğunda şema sürümü ve JSON geçişi bulunmaktadır. Paket editörü akış ilişkilerini göstermeyen bir adım listesidir. Operasyon girişinde çalışmalar önce nesne gruplarının içine gizlenmektedir. Bunlar renk değişikliğiyle çözülemez. [K05] [K10] [K12] [K13] [K14]

### 1.2 Bağlayıcı tasarım kararları

| Karar | Nihai yön | Kullanıcının kazanımı |
|---|---|---|
| Üç çalışma alanı | **Development / Geliştirme**, **Operations / Operasyonlar**, **Connections / Bağlantılar** | Nerede tasarlayacağını, nerede izleyeceğini ve kaynakları nerede yöneteceğini ayırır. |
| Topoloji adı | Ana navigasyonda **kullanılmayacak**; kesin ad **Bağlantılar** | İç mimari terimi yerine aranan işi söyler. |
| Bağlantı yüzeyi | Ana katalog **tablo**; seçili bağlantı ayrı detay sayfasında **özet kart + alt sayfalar** | Çok sayıda bağlantıyı karşılaştırır; tek bağlantıda ayrıntıya iner. |
| Şema hiyerarşisi | Fiziksel şema bağlantının altında; mantıksal şema proje düzeyinde; eşleştirme mantıksal şemanın ortam matrisi içinde | Sahiplik ile kullanım ilişkisini karıştırmaz. |
| Ortam | Proje düzeyinde; çalıştırmada açıkça seçilir ve doğrulanır | Üretime yanlışlıkla geçiş riskini azaltacak görünür bağlam sağlar. |
| Modeller | Geliştirme ağacının son kökü; bağımsız çalışma alanı | Metadata keşfi bağlantı yönetimine sıkıştırılmaz. |
| Mapping | Veri setleri ve kolon eşleştirmeleri odaklı editör; ham JSON yerine desteklenen ifade düzenleyicisi | Teknik serileştirme formatını öğrenmeden eşleştirme yapar. |
| Prosedür | Solda adımlar, içerikte yalnız seçili adım ve SQL; çözümlenen bağlantı/şema görünür | Uzun prosedürlerde bağlamını kaybetmez. |
| Paket | Sol palet, orta diyagram, alt özellik paneli; varsayılan **Diagram / Diyagram** | Sıra, dallanma, başlangıç ve hata geçişlerini birlikte görür. |
| Operasyon | Zaman ve durum odaklı çalıştırma tablosu; detayda gerçek hiyerarşik ağaç | Başarısız çalışmayı bulmak için nesne klasörlerini açmak zorunda kalmaz. |
| Teknik ayrıntılar | UUID, hash, JSON, bağlantı politikası sürümü ikincil alanda | Normal kullanım ile teşhis ayrılır. |
| Dil ve tema | Varsayılan İngilizce; eksiksiz Türkçe; cümle düzeninde başlıklar; nötr/slate yüzey ve ölçülü teal vurgu | Ekranlar tek bir ürünün parçaları gibi davranır. |

Bu tablo **AKIŞ için tasarım kararıdır**; rakiplerin aynı kararları verdiği veya bu düzenin kullanılabilirlik testinde doğrulandığı iddiası değildir.

### 1.3 Uygulamanın ön koşulu

**İlk grup tasarım sistemi, uygulama kabuğu ve yanlış yönlendiren etkileşimlerin düzeltilmesidir. İkinci grup bağlantılar çalışma alanıdır.** Çünkü bağlantıları mevcut dört farklı buton/form/header diliyle yeniden yazmak kısa süre sonra ikinci kez düzenleme gerektirir. Bununla birlikte temel grup bir “tasarım sistemi projesine” dönüşmeyecek; yalnız sonraki ekranların kullandığı bileşenleri ve güvenli durum sözleşmelerini üretecektir.

Tasarım hedefi ile yürütme yeteneği ayrı tutulacaktır. Güncel UI sözleşmesinde bağlantı için `EXECUTABLE` ve `TEST_DISCOVERY_ONLY` ayrımı vardır. Çalıştırma detayında yeniden deneme/devam yetkilerinin desteklenmediği durumlar ifade edilmektedir. Prosedür kaynak ön kontrolü hedefe yazmayan özel bir işlem olup paket simülasyonu değildir. Yeni ekranlar desteklenmeyen işlevleri çalışıyormuş gibi sunmayacaktır. [K09] [K15] [K21]

## 2. Araştırma Yöntemi ve İncelenen Kaynaklar

### 2.1 Yöntem, güncellik ve kanıt türleri

İnceleme dört geçişte yürütüldü: commit ve dosya envanteri; gerçek React/CSS davranışı; resmi ürün dokümantasyonu karşılaştırması; kararların ekran, bileşen, bağımlılık ve kabul testiyle eşleştirilmesi. Sonuç yalnız README’ye veya ekran adına dayanmaz.

| İşaret | Anlamı | Nasıl okunmalı? |
|---|---|---|
| **KOD** | Sabit commit’te okunan kaynakta açıkça görülen davranış | Çalıştırılmış test sonucu değildir. |
| **TARİHSEL REFERANS** | Önceki yüklemelerde bulunan gerçek GPU dosyası | Güncel yerel GPU sürümüne genellenmez. |
| **DIŞ KAYNAK** | Resmi ürün veya standart dokümantasyonu | Yalnız sayfanın desteklediği özellik için kullanılır. |
| **KARAR** | Bu raporun AKIŞ için seçtiği tasarım | Mevcut ürün yeteneği olarak okunmaz. |
| **SÖZLEŞME BOŞLUĞU** | Hedef UI için gereken, incelenen kaynakta doğrulanmayan veri/işlem | Desteklenmeden etkin buton veya sahte veri gösterilmez. |
| **DOĞRULANAMADI** | Dosya, ekran görüntüsü veya canlı davranışa erişilemedi | Boşluk tahminle doldurulmaz. |

`main`, inceleme başında istenen tam SHA ile eşleşmiştir. Commit mesajı `feat: organize project objects into virtual groups`; commit zamanı 12 Eylül 2026 10:43:43 UTC’dir. Tüm kod değerlendirmeleri bu sürüme sabittir. Araştırma sırasında sonradan gelebilecek commit’ler bu raporun kapsamına otomatik girmez. [K00]

### 2.2 AKIŞ kaynak envanteri

| İncelenen alan | Gerçek dosyalar / kaynak | İnceleme kapsamı |
|---|---|---|
| Route ve kabuk | `frontend/src/app/App.tsx`, `AppShell.tsx`, `ProjectSidebarTree.tsx` | Route haritası, proje bağlamı, menüler, ağacın grupları, seçili nesne ve daraltılmış menü. [K01] [K02] [K03] |
| Genel stil | `frontend/src/styles.css` | Tokenlar, light/dark, butonlar, tipografi ve sidebar kuralları; özellikle ilk 165 satır. [K04] |
| Bağlantı çalışma alanı | `frontend/src/features/topology/TopologyPage.tsx`, `topology.css` | Sekmeler, kartlar, listeler, yükleme, keşif, seçili sürüm ve ilgili formlar; CSS’nin ilk 90 satırı özellikle incelendi. [K05] [K06] |
| Oracle formları | `OracleConnectionCreateForm.tsx`, `OracleConnectionEndpointEditForm.tsx` | Sağlayıcı, JDBC/JNDI, test parmak izi, kayıt, parola yenileme ve gelişmiş alanlar. [K07] [K08] |
| UI veri sözleşmesi | `frontend/src/features/topology/api.ts` | İlk 190 satırdaki bağlantı/sürüm, fiziksel/mantıksal şema, ortam, eşleştirme, model tipleri. [K09] |
| Nesne çalışma alanı | `DefinitionsWorkspace.tsx` | Özellikle başlangıç durumları ve 410–590 aralığındaki draft/sürüm/eşleştirme/JSON/form yerleşimi. [K10] |
| Gerçek editörler | `ProcedureEditor.tsx`, `StructuredDraftEditor.tsx`, `MappingGrid.tsx` | Adım listesi, ilişki davranışı, paket modeli, veri seti/kolon/ifade girişleri. [K11] [K12] [K13] |
| Operasyon | `RunsPage.tsx`, `RunDetailPage.tsx`, `OperationsUi.tsx` | Liste gruplama, çalıştırma, yetenek kontrolü, adım ve olay sunumu, ortak UI. [K14] [K15] [K16] |
| Proje ve dil | `ProjectsPage.tsx`, `ProjectOverviewPage.tsx`, `core/i18n/index.ts` | Tek proje, ilk seçim, karşılama, İngilizce varsayılanı ve dil tercihi. [K17] [K18] [K19] |
| İki dilin gerçek metinleri | `frontend/src/core/i18n/locales/en.json`, `tr.json` | Her iki dosyanın ilk 145 satırındaki adlandırma, büyük-küçük harf, teknik dil ve eski anahtarlar; render edilen metin ile yalnız sözlükte kalan metin ayrıldı. [K23] [K24] |
| Mimari ve önceki araştırma | `PRODUCT_EXPERIENCE_V2_DECISIONS.md`, `PROCEDURE_SOURCE_PREFLIGHT.md`, önceki UI/UX raporu | Önceki kararlarla çelişkiler, gerçek kaynak ön kontrolü, eski bulguların güncelliği. [K20] [K21] [K22] |

Bu envanter, repository’deki bütün dosyaların veya bütün çeviri anahtarlarının eksiksiz denetlendiği anlamına gelmez. Özellikle dinamik yerleşim, ekran okuyucu, tarayıcı yakınlaştırması ve çeviri anahtar eşitliği için çalıştırılmış test bulunmamaktadır.

### 2.3 Yerel referansların erişim durumu

| İstenen referans | Ulaşılan kanıt | Sınır ve rapora etkisi |
|---|---|---|
| `C:\innova\projeler\gpu-fusion` | File Library’de 22–23 Mayıs 2026 tarihli Audit Yönetimi, Audit Geçmişi, detay dialog ve ortak CSS yüklemeleri | Gerçek kod karşılaştırıldı; bugünkü yerel sürüm veya bütün uygulama kabuğu görülmüş sayılmaz. |
| `...\osb-dashboard\osb-dashboard.html` | Dosyanın kendisi bulunamadı; yalnız görev metnindeki adı ve beklenti var | HTML/CSS/JavaScript veya ilk bakış başarısı hakkında bulgu yazılamaz. Bölüm 17’deki yön bulma modeli AKIŞ için yeni karardır. |
| Downloads’taki önceki AKIŞ raporu | Git içindeki `docs/research/AKIS_UI_UX_ARASTIRMA_RAPORU.md` kopyası | Windows dosyasıyla bayt eşitliği doğrulanmadı; repository kopyası yardımcı kaynak olarak kullanıldı. |
| ODI paket ekran görüntüsü | Resmi paket dokümanı, yerleşim tablosu ve etkileşim açıklamaları | Görsel dosyası açılamadı. Yerleşim, dokümanın tarifinden incelendi; piksel/görsel hiyerarşi değerlendirmesi yapılmadı. |

### 2.4 GPU referansından gerçekten çıkarılabilenler

**TARİHSEL REFERANS:** `AuditTableConfigPage.tsx`, `AuditTableConfigDataTable.tsx`, `AuditHistoryPage.tsx`, `AuditHistoryDetailDialog.tsx`, `GpuCommon.module.css` ve `GpuDialog.tsx` incelenen geçmiş dosyalardır. Importlar ve eski route envanteri `gpu-ui/src/components/audit-management/`, `gpu-ui/src/components/audit-history/` ve `gpu-ui/src/components/common/gpu/` yollarını göstermektedir. Eski kök dizin kaydında ayrıca `...\gpu\gpu-fusion` geçmektedir; kullanıcının bugünkü diziniyle eşitliği doğrulanmamıştır.

| Kodda görülen ilke | Neden yerli yerinde bir çalışma düzeni oluşturabilir? | AKIŞ’a aktarım | Kopyalanmayacak taraf |
|---|---|---|---|
| `GpuPanel` başlık + amaç açıklaması + ayrı kriter alanı | Önce ekranın işi, sonra arama kapsamı okunur. | Ortak `PageHeader` ve `FilterBar`. | Her ekranda dekoratif “hero” veya ek bir özet kartı. |
| Audit veri listesinin başlığında `Add Audit` | Eylem, etkilediği veri kümesine yakın durur. | Liste sayfasında başlık sağında tek birincil işlem. | Sayfa ve kart başlığında aynı ekleme butonunu çoğaltmak. |
| Kriter → özet → kayıt listesi → seçili detay ayrımı | Kullanıcı tüm ayrıntıyı aynı anda görmek zorunda kalmaz. | Operasyonda filtreler, küçük durum sayaçları, tablo ve ayrı run detayı. | Her run’ı büyük kart yapmak. |
| `AuditHistoryDetailDialog` kolon / eski / yeni karşılaştırması | Aynı alanlar aynı yatay eksende kıyaslanır. | Seçili adımın tutarlı key-value ve log düzeni; sürüm karşılaştırmasında yatay alan tablosu. | Geçmiş dosyadaki `div` tablosunu erişilebilir HTML tablosu sanmak. |
| Nötr metin/yüzey tokenları ve küçük durum badge’leri | İçerik ile durum vurgusunun görevleri ayrılabilir. | Slate yüzeyler, küçük durum ikonu/etiketi. | Yaygın mavi, gradient, blur, 24–28 px yuvarlatma ve katmanlı büyük gölgeler. |
| Yükleniyor, boş sonuç ve detay yok metinleri | “Kayıt yok” ile “henüz gelmedi” ayrıştırılır. | Ortak durum sözlüğü ve yeniden deneme. | Hata ekranında yalnız “daha sonra deneyin” deyip eylem sunmamak. |

Geçmiş CSS’de 24 px sayfa boşluğu, 14 px açıklama, 38 px buton ve küçük ekranlarda tek sütuna geçiş örnekleri vardır. Aynı dosyalarda ağır radius, mavi tonlu gradient ve çok sayıda `!important` düzeltmesi de görülür. **Modern görünümün açıklaması yalnız renk değildir:** başlık, amaç, kriter, sonuç ve detayın görev ayrımıdır. Bu, kaynak yapısından yapılan tasarım değerlendirmesidir; ölçülmüş kullanılabilirlik sonucu değildir. [G01–G06: bölüm 28 kaynak kaydı]

### 2.5 Resmi ürün karşılaştırması — bilgi mimarisi ve kaynak bağlamı

Aşağıdaki hücrelerde **“birebir yok/doğrulanmadı” ürünün yetersizliği değil, kavramların eşitlenmemesi uyarısıdır**. Örneğin bir agent grubu, bir Fabric workspace’i ve ODI context’i aynı nesne değildir.

| Ürün | Doğrulanan kaynak/bağlantı/ortam yaklaşımı | Proje ve nesne yaklaşımı | AKIŞ için karar |
|---|---|---|---|
| Oracle ODI | Fiziksel ve mantıksal şema context üzerinden ilişkilendirilir. [D02] [D03] | Designer proje nesneleri ile paket çalışma alanı ayrıdır. [D01] | Kavramsal ayrımı koru, “Topology”yi kullanıcıya ana menü olarak taşıma. |
| Informatica IDMC | Bağlantı türüne özgü alanlar ve runtime environment referansı vardır; bu referans doğrudan Dev/Test/Prod eşanlamlısı değildir. [D05] | Explore örneğinde proje/klasör/varlık gezinimi ve bağlamsal eylemler bulunur. Bu kaynak Data Quality alanındadır. [D06] | Tür duyarlı bağlantı formu ve proje nesne araması; suite içindeki her ekranı aynı varsayma. |
| Azure Data Factory | Author yüzeyi ile bağlantıları/çalıştırma altyapısını barındıran Manage alanı ayrılır. [D08] | Pipeline, dataset ve data flow authoring içinde yer alır. [D08] | Kaynak yönetimini tasarım editöründen ayır; her iki alanı aynı proje içinde tut. |
| Microsoft Fabric Data Factory | Connector desteği kullanılan araç türüne göre farklılaşabilir. [D11] | Pipeline izleme merkezi monitoring yüzeyinden erişilir. [D10] | Sağlayıcı seçimi “her işlem destekleniyor” anlamına gelmesin; capability etiketleri görünür olsun. |
| AWS Glue Studio | Görsel job editörü source/transform/target düğümleri ve özelliklerini ayırır. [D13] | Job tasarımı ve job run incelemesi farklı görevlerdir. [D13] [D14] | Teknik kaynağı insan okuyabilir düğümle göster; çalıştırma kaydıyla taslağı karıştırma. |
| Apache Airflow | İncelenen UI kaynağı çalışma/DAG odaklıdır; ODI türü fiziksel–mantıksal şema eşlemesi bu kaynaktan doğrulanmadı. [D15] | DAG, run ve task yüzeyleri ayrılır. [D15] | Operasyon hiyerarşisini al; bunu sürükle-bırak veri mapping editörü sanma. |
| Dagster | Kaynakların yapılandırması ve kullanımları ayrı incelenebilir. [D16] | Asset/job/run bağlamları birbirinin yerine kullanılmaz. [D16] | Model metadata’sı, yürütülebilir nesne ve run’u ayrı kimliklerle sun. |
| Prefect | Block kataloğu tipli yapılandırma ve gizli alanlar taşır; değişken run girdileri parametre kavramıyla ayrılır. [D17] | Flow/task durumlarının farklı anlamları vardır. [D18] | Bağlantı formunu sağlayıcı sözleşmesinden üret; sırları sıradan JSON alanı yapma. |
| Talend | Context değişken kümeleri Job/Route veya Repository kapsamında tutulabilir. [D21] [D27] | Repository içinde ortak context kullanımı ve editörde palet bulunur. [D20] [D27] | Ortak ortam değerlerini nesne formlarında tekrar ettirme; paleti keşfedilebilir tut. |
| dbt Cloud / dbt platform | Environment; çalışma hedefi, kod ve yürütme ayarlarıyla ilişkilidir. [D23] | Studio dosya, editör ve komut sonuçlarını birlikte düzenler. [D22] | SQL çalışma alanında yazma ve sonucu ayır; dbt environment’ını AKIŞ schema binding’iyle eşitleme. |

### 2.6 Resmi ürün karşılaştırması — editör, operasyon ve eylem düzeni

| Ürün | Editör / diyagram / özellikler | Çalıştırma ve hata incelemesi | Alınan ilke; alınmayan varsayım |
|---|---|---|---|
| ODI | Paket paleti, diyagram, toolbar ve özellikler bölgesi; genel bilgi ve diyagram ayrımı. [D01] | Paket geçişleri ile çalışma sonucunu ayıran etkileşim dili. [D01] | Çalışma masası düzeni alınır; eski ikon, renk ve yoğun desktop chrome alınmaz. |
| Informatica | Mapping canvas ve seçili dönüşümün özellikleri ayrı görevler taşır. [D04] [D07] | Bu araştırmada taskflow monitoring ayrıntılarının tamamı doğrulanamadı. | Özellikleri seçime bağla; erişilemeyen ekranın buton konumlarını uydurma. |
| ADF | Üst nesne özellikleri ile alt aktivite özellikleri farklı alanlarda; desteklenen ifadeler için builder. [D08] | Run tablosundan aktivitelere inme ve filtreleme. [D09] | Alt özellik paneli ve durum tablosu alınır; debug işlemini zararsız simülasyon sayma. |
| Fabric | Pipeline yürütmesi ve debug semantiği dokümanda ayrıntılandırılır. [D12] | Monitoring hub üzerinden run bilgileri ve drill-down. [D10] | Operasyonun ortak giriş noktası olur; “Debug” adı güvenlik garantisi sayılmaz. |
| Glue Studio | Düğüm özellikleri, şema ve diğer job alanları ayrılır. [D13] | Job run tablosu ve run durum bilgileri. [D14] | Seçili düğüm odaklılık; veri preview’sini ücretsiz/yan etkisiz kabul etmeme. |
| Airflow | Grid ve graph farklı sorulara cevap verir; kod ayrı incelenir. [D15] | Run/task odaklı durum ve log incelemesi. [D15] | Grafik bağımlılık için, tablo tarama için; aynı nesneyi iki benzer “dashboard”da çoğaltmama. |
| Dagster | Job grafiği ve Launchpad farklı işlevlerdir. [D16] | Filtrelenebilir runs, Gantt ve yapılandırılmış/ham log ayrımı. [D16] | Run, adım ve log bağlamı birlikte kalır; ham log normal kullanıcıya ilk ekran olmaz. |
| Prefect | İncelenen kaynaklar kod/konfigürasyon ve durum yaşam döngüsü ağırlıklıdır. [D17] [D18] | Flow/task loglarının kapsamı ile worker kaynaklı sorunlar ayrıştırılır. [D19] | “Log yok”u “başarı” olarak sunmama; çalışma başlamadan hata olabileceğini anlatma. |
| Talend | Paletin görünürlüğü ve konumu kontrol edilebilir. [D20] | Bu kaynak kümesinde güncel Management Console run detay düzeni tam incelenmedi. | Gizlenebilir palet, fakat görünür açma düğmesi; doğrulanmayan operasyon ayrıntılarını taşımama. |
| dbt platform | Dosya gezgini, kod alanı, komut/çıktı yüzeyi. [D22] | Studio içindeki komut sonuçları ve log bağlamı. [D22] | SQL alanını küçük modal içine sıkıştırmama; Studio komut geçmişini bütün kurumsal run yaşam döngüsüne eşitlememe. |

**Sentez — KARAR:** AKIŞ tek rakibi kopyalamayacaktır. Kaynak soyutlamasında ODI, çalışma masası yerleşiminde ODI/ADF, operasyon taramasında ADF/Fabric/Airflow, seçili çalışma detayı ve log bağlamında Dagster, tipli konfigürasyonda Prefect, SQL alanında dbt’nin gösterdiği görev ayrımından yararlanacaktır. Bu sentez ürünün mevcut domain modeline uyarlanmıştır; bir “rakip puanlaması” veya satın alma önerisi değildir.

### 2.7 Önceki rapordan hangi sonuçlar geçerli, hangileri güncellendi?

Önceki repository raporunun ana incelemesi `9cf018d…`, son fark kontrolü `05cffdc…` sürümlerine dayanır. Bu rapor `5759b69…` üzerindedir. [K22]

| Önceki değerlendirme/yön | Güncel kontrol | Bu raporun tutumu |
|---|---|---|
| Genel bakış operasyon kartlarıyla kalabalık | Güncel `ProjectOverviewPage` yalnız karşılama gösteriyor. [K18] | Eski bulguyu kaldır. |
| Prosedür bütün adımları açık gösteriyor | Güncel editor seçili adım master–detail kullanıyor. [K11] | Temeli koru; bağlam, SQL ve bağımlılık etkileşimlerini tamamla. |
| Proje ağacını sanal gruplara ayır | Son commit bunu önemli ölçüde yapmış. [K03] | Yeni geliştirme gibi sayma; grup içi davranışı kesinleştir. |
| Bağlantı oluşturmayı drawer’da aç | Kullanıcının yeni sabit şartına aykırı. [K20] | Tam sayfa form ile değiştir. |
| Türkçede her kelimeyi büyük harfle başlat | Güncel raporun ortak dil standardı farklı. [K20] | Her iki dilde cümle düzeni; kısaltmalar korunur. |
| Teknik kimlikleri ikincil alana taşı | Run detayında bunun bir bölümü yapılmış. [K15] | Tamamlanmış kısmı koru, kalan ham enum/JSON alanlarını düzelt. |
| Runtime sınırlarını editöre yansıt | Güncel prosedür editorü capability limitlerini alıyor. [K11] | Eski 10.000/1.000 varsayılan farkını güncel hata diye tekrarlama. |

## 3. Mevcut AKIŞ UI/UX Denetimi

### 3.1 Kaynak koduna dayalı bulgular

Önem: **P0** yanlış hedefte işlem/veri kaybı riski; **P1** temel iş akışını belirgin zorlaştırma; **P2** tutarlılık ve verim sorunu. Bunlar statik inceleme önceliğidir; üretimde gerçekleşmiş olay sayısı değildir.

| ID | Ekran / gerçek bileşen | KOD bulgusu | Etki ve nihai müdahale | Önem |
|---|---|---|---|---|
| A01 | `AppShell.tsx` | Geniş menüde nesne ağacı var; daralınca ağaç kayboluyor, Geliştirme için eşdeğer belirgin çalışma alanı bağlantısı yok. [K02] | Üç workspace her genişlikte erişilebilir olacak. | P1 |
| A02 | `App.tsx` | `/models` yine `TopologyPage initialTab="catalog"`; `/connections` ve `/topology` aynı yüzeye açılıyor. [K01] | Model ve bağlantı sahipliği ayrı route bileşenlerine ayrılacak. | P1 |
| A03 | `ProjectSidebarTree.tsx` | Akış/ortak bileşen/model sırası doğru; flow nesneleri klasörde ad sırasıyla karışık. [K03] | Mevcut kök sırasını koru; tür alt gruplarını sanal görünümle düzenle. | P2 |
| A04 | `TopologyPage.tsx` | Bağlantı, şema, ortam, binding ve modeller beraber yükleniyor; bağlantı başına sürüm sorgusu var. [K05] | Alan bazlı yükleme ve özet veri ihtiyacı tanımla; katalog tek çalışma için gereksiz veriyi beklemesin. | P1 |
| A05 | `TopologyPage.tsx` | Kart ve düzenleme için `versions[0]` kullanılıyor. [K05] | “En yeni” ile “etkin” ayrılacak; etkin olmayan revizyon sessizce ana hedef olarak sunulmayacak. | P0 |
| A06 | `TopologyPage.tsx` | Test, düzenle ve sil kart üzerinde yan yana. [K05] | Test görünür; düzenleme/silme bağlam menüsüne, silme bağımlılık kontrollü onaya. | P1 |
| A07 | `OracleConnectionCreateForm.tsx` | Test geçerlilik izi ad, kod ve açıklamayı da kapsıyor. [K07] | Açıklama değişikliği bağlantı testini geçersiz yapmayacak; bağlantı etkileyen alanlar ayrı değerlendirilecek. | P2 |
| A08 | `OracleConnectionEndpointEditForm.tsx` | Düzenlemede parola boş başlıyor ve tekrar zorunlu. [K08] | “Mevcut parolayı koru / yeni parola” sözleşmesi; destek yokken sahte koruma davranışı yok. | P1 |
| A09 | `DefinitionsWorkspace.tsx` | JSON geçişi ve teknik `schemaVersion` draft araç çubuğunda. [K10] | Ana editörden kaldır; tanım görüntüleme gelişmiş menüye. | P1 |
| A10 | `MappingGrid.tsx` | İfade alanında JSON serileştiriliyor ve `JSON.parse` ile okunuyor. [K13] | Desteklenen AST ifadeleri için kullanıcı odaklı düzenleyici. | P1 |
| A11 | `MappingGrid.tsx` | Veri seti ID ve kolon adları serbest alan; dataset silme ayrı işlem. [K13] | Metadata seçici ve bağımlılık kontrolü; ilişkiler sessizce koparılmayacak. | P0 |
| A12 | `ProcedureEditor.tsx` | Adımlar seçili editorle gösteriliyor; bağlantı/şema isimleri görünür çözümleme özeti olarak yok. [K11] | Adım başlığında mantıksal bağlam, seçili ortam ve çözümlenen hedef. | P1 |
| A13 | `ProcedureEditor.tsx` | `normalizeTasks` uygun olmayan rowset bağlarını temizleyebiliyor; kaldırmada geri alma yok. [K11] | Bağımlı silme/yeniden sıralama etkisini göster; sessiz bağı koparma yok. | P0 |
| A14 | `StructuredDraftEditor.tsx` | Paket yalnız ilk adım + ID/tür listesi; görsel edge veya bağlı nesne seçici yok. [K12] | Paket çalışma alanını yeniden tasarla. | P1 |
| A15 | Aynı paket dalı | `STEP_${steps.length + 1}` tekrar ID riski; adım rename/delete ilk adım referansını birlikte düzeltmiyor. [K12] | Sabit teknik kimlik; başlangıç ve referans bütünlüğü doğrulaması. | P0 |
| A16 | `RunsPage.tsx` | Nesne grupları kapalı başlıyor; arama nesne adı/kodu; durum/ortam/tarih filtresi yok. [K14] | Zaman odaklı run tablosu ve açık filtreler. | P1 |
| A17 | `RunsPage.tsx` | “Daha fazla” istemcide grup sayısını artırıyor, gerçek sunucu sayfalaması değil. [K14] | Ölçeklenebilir liste sözleşmesi ve kontrollü sayfalama. | P1 |
| A18 | `RunDetailPage.tsx` | Adımlar düz map listesi; detayda hata kodu var, hata mesajı gösterilmiyor. [K15] | Gerçek parent/child ağacı ve güvenli hata açıklaması. | P1 |
| A19 | `RunDetailPage.tsx` | İlk adım varsayılan seçili; olay listesinde tree rolleri; yenileme manuel. [K15] | İlk başarısız yolu aç; olaylar liste; aktif run için kontrollü canlı yenileme. | P2 |
| A20 | CSS ve UI aileleri | `button`, `topology-button`, `definition-button`, `ops-button` ayrı görsel kurallar taşıyor. [K04] [K06] [K10] [K16] | Aynı semantik button/field/header sözleşmesine geç. | P2 |
| A21 | Prosedür/operasyon metinleri | SOURCE/TARGET, READ_ONLY, STOP, başlangıç türleri gibi kodlar doğrudan gösteriliyor. [K11] [K14] [K15] [K16] | Kanonik backend kodlarını değiştirmeden çeviri eşlemesi uygula. | P1 |
| A22 | Ağaç ve menü bileşenleri | Bazı ARIA rolleri mevcut, fakat bütün tree/menu klavye davranışı bu kaynakta tamamlanmış değil. [K03] [K14] [K15] | Role eklemekle yetinme; odak, yön tuşu, kapatma ve geri dönüş davranışlarını birlikte uygula. | P1 |

**Statik risklerin sınırı:** A05, A11, A13 ve A15 bir üretim hatasının tekrarlandığı iddiası değildir. Kaynakta görülen durum geçişleri bu senaryolarla test edilmeden güvenli kabul edilmemelidir.

### 3.2 Route düzeyinde ayrıştırılması gereken işler

Mevcut alias’lar bir süre yönlendirme olarak korunacak; kullanıcı bookmark’ları kırılmayacaktır. `/development?definition=...` çalışmaya devam ederken kanonik nesne detay URL’sine taşınacaktır. `/models` artık bağlantı sayfasının başka bir sekmesi olmayacaktır. `/publications` normal navigasyonda ayrı bir ürün kavramı olarak tekrar öne çıkarılmayacak; mevcut immutable kayıtlar nesnenin **Çalıştırılabilir sürümleri** altında açılacaktır. [K01] [K10] [K20]

### 3.3 İngilizce/Türkçe metin denetimi

**KOD:** Her iki dil dosyasında da cümle düzeni ile her kelimenin baş harfini büyüten düzen birlikte bulunmaktadır. Örneğin `nav.collapse` İngilizcede “Collapse Navigation”, `nav.expand` ise “Expand navigation”; Türkçede “Gezinmeyi Daralt” ve “Gezinmeyi genişlet” biçimindedir. Bu, hedef dil standardının yeni bir tercih olmasının yanında mevcut kaynakta doğrulanmış bir tutarsızlığı da giderdiğini gösterir. [K23] [K24]

| Anahtar / bağlam | Kaynakta görülen durum | Kesin hedef |
|---|---|---|
| `nav.createScenario`, `nav.createSubfolder`, `projects.new`, `common.retry` | İngilizce ve Türkçede baş harf büyütme farklı ekran metinleriyle tutarsız. | `Create scenario / Senaryo oluştur`, `Create subfolder / Alt klasör oluştur`, `New project / Yeni proje`, `Try again / Yeniden dene`. |
| `auth.proofDesignText` | Türkçe cümlede `sequence` kalmış. | Kullanıcı metninde `sıra üreteci`; teknik tür `SEQUENCE` korunur. |
| `projects.import` | İngilizce “Import Bundle”, Türkçe “Paket İçe Aktar”; workflow nesnesi olan Paket ile aktarım dosyası karışabilir. | Eylem `Import project / Proje içe aktar`. Dosya biçimi gerektiğinde yardımcı metinde `Project bundle / Proje aktarım paketi`. |
| `auth.connectionError` | Kullanıcıya backend’in çalıştığını kontrol etmesi söyleniyor. | Ana mesaj `Service unavailable / Servise ulaşılamıyor`; görünür `Try again / Yeniden dene`; teknik ayrıntı yetkili destek alanında. Yerel geliştirici ipucu normal kullanıcı hatasına karıştırılmaz. |
| `common.cancel` | Türkçe genel karşılık “İptal”. | Formdan vazgeçmek `Cancel / Vazgeç`; çalışan işi iptal etmek `Cancel run / Çalıştırmayı iptal et`. Bağlamlara ayrı anahtar verilir. |
| `pendingChanges.discard` | “Vazgeç ve Ayrıl” değişikliklerin kaybolacağını yeterince açık söylemiyor. | `Discard changes and leave / Değişiklikleri bırak ve ayrıl`; kal ve kaydet seçenekleri ayrı. |

**Kullanım kanıtı sınırı:** Sözlükte `nav.topology` veya eski operasyon sayaçlarına ait `overview.*` anahtarlarının bulunması, bu öğelerin güncel ekranda gösterildiğini kanıtlamaz. Mevcut `ProjectOverviewPage.tsx` sakin karşılama ekranıdır. Kullanılmayan çeviri anahtarları ancak gerçek import/renderer kullanım taramasından sonra temizlenir. İki dilde bütün anahtarların eşitliği bu araştırmada otomatik test edilmiş değildir; kabul testi kapsamına alınmıştır. [K18] [K23] [K24]

## 4. Güçlü Yönler

Mevcut ürünün temellerini yok etmek yerine aşağıdaki parçalar korunacaktır:

| Korunacak özellik | Kanıt | Tamamlanacak nokta |
|---|---|---|
| İngilizce varsayılanı, kalıcı dil tercihi, `html.lang` | [K19] | Ham enumları ve yeni metinleri aynı sözlüğe bağlamak. |
| Tek projede otomatik seçim | [K17] | Çoklu proje ilk girişini ve yetki kaybını kesinleştirmek. |
| Üst bardan proje değişimi, pending change koruması | [K02] | Çıkış, sekme kapatma ve form içi gezinmede aynı koruma. |
| Sade proje karşılama ekranı | [K18] | Sakin bir “Geliştirmeye git” sonraki adımı eklemek. |
| Doğru sıradaki sanal proje kökleri ve gizli reusable mapping | [K03] | Büyük ağaç ve klavye davranışı. |
| Prosedürde seçili adım master–detail | [K11] | Bağlam çözümleme, SQL ve bağımlı işlem güvenliği. |
| Oracle formunda sağlayıcı koşullandırması, test ve kapalı gelişmiş alanlar | [K07] | Alan bazlı hata, test kapsamı, sürüm yaşam döngüsü. |
| Bağlantıda fiziksel şema ve tekilleştirilmiş mantıksal şema sayımı | [K05] | Aynı sayımın sunucu özetinden ve doğru yetki kapsamından gelmesi. |
| Capability/allowedActions yaklaşımı | [K14] [K15] | Her yeni çalıştırma, devam ve simülasyon eyleminde aynı disiplin. |
| Teknik kimliklerin run detayında kapalı alana alınması | [K15] | Diğer ekranların da aynı düzeye gelmesi. |
| Light/dark token altyapısı ve ortak dialog kullanımına yönelim | [K04] [K16] | Yerel hardcoded renkleri ve farklı form stillerini birleştirmek. |

## 5. Kullanılabilirlik Sorunları

### 5.1 Bir ekranın birden fazla sahipliği var

Bağlantı tanımlamak, mantıksal şemayı ortama eşlemek ve veritabanından model metadata’sı almak aynı nesneyi düzenlemek değildir. Aynı büyük `TopologyPage` içinde bu işlemlerin sekmelerle yan yana durması sahiplik sınırını belirsizleştirir. Çözüm sekme başlıklarını güzelleştirmek değil, **hangi nesnenin hangi sayfanın sahibi olduğunu belirlemektir**. [K05]

### 5.2 Bilgi yoğunluğu yanlış yerde

Kart içindeki host, port, service ve kullanıcı için ayrı küçük kutular; sayfa içinde panel, panel içinde kart, kart içinde mini kart katmanları oluşturur. Bu alanların çoğu karşılaştırılabilir metin olduğundan katalog tablosuna taşınacaktır. Tersine, paket akışındaki bağlantı türleri ve prosedürün çözümlenen fiziksel hedefi görünür olması gereken yerde yeterince yer almamaktadır. [K06] [K11] [K12]

### 5.3 Teknik ayrıntı ile profesyonellik birbirine karıştırılıyor

Bir kullanıcıya UUID, JSON veya veri yapısının şema sürümünü göstermek ekranı kurumsal yapmaz. Profesyonellik; doğru hedefi, değişiklik etkisini, işlem durumunu ve uygulanabilir sonraki eylemi gösteren tutarlı bir sözleşmeden gelir. Ham formatlar teşhis için korunacak, gündelik authoring’in ön koşulu olmaktan çıkarılacaktır. [K10] [K13]

### 5.4 Desteklenen tasarım ile desteklenen çalıştırma aynı değil

Prosedür editöründe SQL/PLSQL/stored procedure seçeneklerinin bulunması, her ifadenin mevcut runtime tarafından çalıştırılabildiğini kanıtlamaz. Kullanıcı “Kaydedildi” ile “Bu ortamda çalıştırılabilir” durumlarını ayrı okuyacaktır. Bu raporun sınırsız adım hedefi, mevcut runtime profilinin sınırlarını gizlemek anlamına gelmez. [K11] [K20]

### 5.5 Görünürlük ile erişilebilirlik karıştırılıyor

Bir ağaca `role="tree"` vermek ok tuşlarıyla gezinmeyi sağlamaz. Yeşil çizgi tek başına başarıyı, kırmızı çizgi tek başına hatayı anlatmaz. Küçük üç noktanın yalnız hover’da görünmesi temel işlemleri erişilebilir yapmaz. Bunların davranış kabul kriterleri bölüm 22 ve 25’te birlikte tanımlanmıştır. [K03] [K14] [K15] [D24] [D25]

## 6. Bilgi Mimarisi İçin Nihai Karar

### 6.1 Üç ayrı iş, tek proje bağlamı

**KARAR:** Proje, çalışma alanlarının üst bağlamıdır. Geliştirme, Operasyonlar ve Bağlantılar birbirinin alt menüsü değildir. Her alanın kendi alt navigasyonu ve görev odaklı sayfaları olacaktır.

| Katman | Kullanıcının sorusu | Sorumlu yüzey |
|---|---|---|
| Uygulama | “Hangi üründeyim?” | Sol üstte sürekli AKIŞ marka adı. |
| Proje | “Hangi iş alanındayım?” | Üst barda proje adı ve değiştirme kontrolü. |
| Çalışma alanı | “Tasarlıyor muyum, izliyor muyum, kaynak mı hazırlıyorum?” | Üç sabit workspace bağlantısı. |
| Nesne | “Hangi bağlantı, akış veya çalıştırma üzerindeyim?” | Breadcrumb + sayfa başlığı + tür etiketi. |
| Durum | “Taslak mı, kaydedilmemiş mi, üretim mi, başarısız mı?” | Başlık yakınında metinli, küçük durum göstergeleri. |
| İşlem | “Şimdi ne yapabilirim?” | Birincil eylem, görünür ikincil eylemler ve gerekçeli engeller. |

Bu düzen, ekranın 3–5 saniyede anlaşılması hedefini desteklemek üzere seçilmiştir. Başarı oranı ölçülmemiştir; test yöntemi bölüm 25’te tanımlanmıştır.

### 6.2 Sahiplik ve ilişki ayrımı

```text
Proje
├── Geliştirme
│   ├── Akışlar / kullanıcı klasörleri / tasarım nesneleri
│   ├── Ortak bileşenler
│   └── Modeller / alt modeller / veri nesneleri
├── Operasyonlar
│   ├── Çalıştırmalar
│   └── Çalıştırma detayı / adım ağacı / loglar
└── Bağlantılar
    ├── Bağlantı kataloğu
    │   └── Bağlantı / fiziksel şemalar / revizyonlar / kullanım
    ├── Mantıksal şemalar
    │   └── Mantıksal şema / ortam eşleştirmeleri / kullanım
    └── Ortamlar
        └── Ortam / eşleştirme kapsamı / güvenlik bilgisi
```

**İlişki, sahiplik değildir:** Bir mantıksal şema bir bağlantının çocuğu değildir. Aynı mantıksal şema farklı ortamlarda farklı bağlantı/fiziksel şemaya çözümlenebilir. Modelin bağlantı ekranından açılabilmesi, modelin bağlantının sahibi altında bulunmasını gerektirmez. Bu ayrım AKIŞ’ın mevcut `LogicalSchema`, `Environment`, `SchemaBinding` ve `Model` tipleriyle uyumludur. [K09]

### 6.3 Bağlam değişiminin sözleşmesi

Proje değiştiğinde eski projenin nesne ağacı, ortam tercihi, yetkileri, arama sonuçları ve seçili run’ı yeni başlık altında bir an bile gösterilmeyecektir. Eski isteklerin sonuçları yeni proje state’ine yazılmayacaktır. Anahtar, yalnız nesne UUID’si değil **proje + nesne + gerekiyorsa sürüm** olacaktır.

Kaydedilmemiş değişiklik varken proje, nesne, çalışma alanı değiştirme veya çıkış işlemi aynı korumaya bağlanacaktır: **Kaydet ve devam et / Değişiklikleri bırak / Burada kal**. Kayıt başarısız olursa gezinme yapılmayacaktır. Bağlantı formundaki parolalar localStorage veya kalıcı tarayıcı taslağına yazılmayacaktır. Bu yeni davranışın amacı, mevcut pending-change temelini bütün authoring yüzeylerine yaymaktır. [K02] [K10]

## 7. Ana Navigasyon İçin Nihai Karar

### 7.1 Sabit sıra ve görünür alanlar

Sol navigasyonun üst kısmında daima **Geliştirme → Operasyonlar → Bağlantılar** bulunacaktır. Seçili çalışma alanının alt navigasyonu bunların altında açılacaktır. Geliştirme seçiliyken proje nesne ağacı görünür; Operasyonlar seçiliyken çalışma görünümleri; Bağlantılar seçiliyken katalog, mantıksal şemalar ve ortamlar görünür.

| Kontrol | Kesin yer | Davranış |
|---|---|---|
| AKIŞ marka adı | Sol üst | Proje varsa onun girişine, yoksa seçim kapısına gider. |
| Proje değiştirici | Üst bar, solda | Ad + aşağı ok; arama, erişilebilir projeler ve ikincil proje işlemleri. |
| Proje girişine git | Üst barda proje adının yanındaki etiketli/tooltip’li ev simgesi | Genel bakışı açar; bağımsız “Projeler” menüsü oluşturmaz. |
| Dil, tema, profil | Üst bar sağ | Dil seçeneklerinde `English` ve `Türkçe`; teknik EN/TR kodları yalnız dar görünümde yardımcı etiket olabilir. |
| Nesne oluştur | Geliştirme ağacının başlığı | Görünür `Yeni` menüsü; klasör bağlamı önceden seçilir. |
| Proje ayarları, içe/dışa aktarma | Proje menüsünün ikincil bölümü | Kaydet/çalıştır ile yarışmaz; yetkiye göre görünür. |
| Kimlik ve ekip yönetimi | Profil/yönetim menüsü ve proje ayarları | Normal geliştiriciye sürekli fazladan menü yükü oluşturmaz. |

Daraltılmış menü **64 px** olacaktır. Üç çalışma alanı simgesi, seçili göstergesi, tooltip ve erişilebilir adı korunacaktır. Ağaç daraltılmış menü içine sıkıştırılmayacak; Geliştirme simgesine basıldığında geçici gezinme paneli açılacaktır. Bu panel form drawer’ı değildir; nesne gezinimidir.

### 7.2 İşlem ve rol ayrımı

Geliştirici varsayılan olarak proje girişinden Geliştirme’ye yönlendirilir. Yalnız operasyon yetkisi olan kullanıcı aynı girişte **Operasyonlara git** eylemini görür. Üç alanın sırası değişmez; erişilemeyen alanların görünürlüğü sunucu yetkisine göre düzenlenir. UI’da bir öğeyi gizlemek yetkilendirme yerine geçmez.

Nesne kaydetme, çalıştırılabilir sürüm hazırlama ve çalıştırma ayrı eylemlerdir. “Run / Çalıştır” hiçbir durumda sessizce taslağı kaydetmeye, yeni senaryo üretmeye, üretim eşlemesini değiştirmeye ve sonra çalıştırmaya dönüşmeyecektir. Gerekli hazırlık eksikse kullanıcı eksik adımı açıkça görecektir. [K20]

## 8. “Topoloji” Konusundaki Nihai Karar

**Ana navigasyonda “Topoloji” adlı bölüm olmayacaktır. Kesin ad `Connections / Bağlantılar` olacaktır.** Çalışma alanının giriş açıklaması “Bağlantıları, şemaları ve ortam eşleştirmelerini yönetin.” olacaktır. Böylece kısa menü adı korunurken kapsam açıklanır.

Gerekçe, “topoloji”nin yanlış bir teknik terim olması değildir. AKIŞ kullanıcılarının temel görevi ağ/altyapı topolojisi çizmek değil, veri kaynaklarına erişim hazırlamak ve mantıksal adları ortamlara bağlamaktır. Terimin teknik doğruluğu, aranan işlem için en iyi navigasyon etiketi olduğunu göstermemektedir. Bu karar, mevcut ürün belgesinin Connections yönüyle de uyumludur. [K20]

**İç kod isimleri hemen değiştirilmek zorunda değildir.** `topologyApi` ve backend route’ları bu araştırmanın kapsamında topluca yeniden adlandırılmaz. Kullanıcıya açık `/topology` route’u eski bağlantılar için `/connections` yönlendirmesi olarak tutulur. Eski `TopologyPage` sorumlulukları küçük sayfalara ayrıldıktan sonra bileşen kaldırılır; teknik servis dosyasını yalnız isim tutarlılığı için bozmak gereksizdir.

## 9. Proje Nesne Ağacı

### 9.1 Kesin yapı ve isimler

Kullanıcının istediği kök sırası korunacaktır. **“Klasörler” adlı zorunlu ara seviye kaldırılacaktır**; kullanıcı zaten klasör ikonlarını ve gerçek klasör adlarını görecektir. Tür grupları veri modeline yeni klasör olarak kaydedilmeyecek, sanal görünüm olacaktır.

```text
Finance DWH                         [Proje; üst barda da görünür]
├── Flows / Akışlar
│   ├── Billing / Faturalama        [Gerçek kullanıcı klasörü]
│   │   ├── Daily / Günlük          [Varsa gerçek alt klasör]
│   │   ├── Data flows / Veri akışları
│   │   │   └── Customer load
│   │   ├── Procedures / Prosedürler
│   │   │   └── Prepare staging
│   │   ├── Packages / Paketler
│   │   │   └── Daily billing
│   │   └── Execution plans / Çalıştırma planları
│   │       └── Nightly orchestration
│   └── Unfiled / Klasörsüz         [Yalnız klasörsüz nesne varsa]
├── Shared components / Ortak bileşenler
│   ├── Variables / Değişkenler
│   ├── Sequence generators / Sıra üreteçleri
│   ├── User functions / Kullanıcı fonksiyonları
│   └── Execution modules / Yürütme modülleri
└── Models / Modeller
    └── Billing model
        ├── Tables / Tablolar
        └── Views / Görünümler
```

**Mapping terminolojisi:** Ana nesne adı **Data flow / Veri akışı** olacaktır; teknik type `MAPPING` korunacaktır. İlk kullanım yardımında “Data flow (mapping)” açıklanır. Ağaçta `Veri Akışı / Mapping` gibi iki eşanlamlı sürekli yan yana yazılmaz. `Flow / Akış` ise kök koleksiyonun genel kavramıdır; paketle mapping aynı nesne türü gibi sunulmaz.

**Modeller** ilk sürümde tıklandığında bağımsız modeller sayfasını açabilir; model ağaç verisi geldiğinde aynı kök altında lazy-load ile genişletilir. Bu iki ayrı bilgi mimarisi seçeneği değildir: kalıcı yer Modeller köküdür; ağaca alt metadata yüklenmesi veri sözleşmesine bağlı uygulama basamağıdır.

### 9.2 Sıralama, boş grup ve ölçek

Klasör içinde önce gerçek alt klasörler, sonra sabit sıradaki dört tür grubu gösterilecektir. Dolu tür grupları görünür; boş gruplar her klasörde dört gereksiz satır üretmeyecektir. Boş klasörde “Bu klasöre nesne ekleyin” ve görünür `Yeni` eylemi bulunur; yaratma menüsü bütün desteklenen türleri listeler.

Sıralama kullanıcı diline göre yapılır; Türkçe `I/İ/ı/i` ve aksan davranışı test edilir. İş nesnesinin adı kullanıcı metnidir; çevrilmez. Görünen tür etiketi çevrilir. Teknik kodların ASCII kuralı Türkçe ad alanına uygulanmaz.

Arama ağaç genişletme durumundan bağımsızdır. Sonuçta **ad + tür + klasör yolu** gösterilir. Sonuca gidildiğinde ilgili ebeveynler açılır, nesne seçilir ve içerik başlığına odak taşınır. Aramayı temizlemek eski açık/kapalı durumunu geri getirir. Otomatik yenileme bütün ağacı tekrar açmaz.

Büyük projede çocuklar ihtiyaç oldukça yüklenir; yüzlerce satırı çizmek yerine görünür satırlar sanallaştırılır. Ekran okuyucu için toplam/konum bilgileri sağlanır. Veri eksikken sayı `0` gösterilmez; yükleme işareti kullanılır.

### 9.3 İşlem menüleri

| Nesne | Görünür temel eylem | Sağ tık / üç nokta eşdeğerleri | Güvenlik |
|---|---|---|---|
| Klasör | Aç/kapat; başlıkta `Yeni` | Yeni nesne, alt klasör, yeniden adlandır, taşı, dışa aktar | Silme bağımlılık ve içerik onayıyla. |
| Veri akışı/prosedür/paket | Ada basarak aç; sayfada Kaydet/Doğrula/Çalıştır | Aç, çoğalt, taşı, sürümler, dışa aktar, sil | Çalıştır seçili kaydedilmemiş taslağı otomatik yayımlamaz. |
| Ortak bileşen | Aç; tür başlığından yeni | Çoğalt, kullanımını gör, düzenle, sil | Kullanım bağımlılığı görünür. |
| Model/veri nesnesi | Modeli aç; metadata’yı incele | Akışta kullan, yenile/keşfet, kullanımını gör | Metadata içe alma fiziksel tablo oluşturma değildir. |

Sağ tık menüsü ile üç nokta menüsü aynı action registry’den üretilecektir. Temel yaratma, açma, kaydetme ve çalıştırma yalnız sağ tıkta bırakılmayacaktır. Henüz uygulanmamış type’ın oluşturulması raw JSON’a düşüyorsa o type “hazır” sayılmayacak; gerekçesi görünür capability durumu olacaktır. [K10] [K12]

## 10. Bağlantılar ve Şemalar Çalışma Alanı

### 10.1 Mevcut ekran için karar

**Bağlantılar ekranı yerleşim, navigasyon ve işlem akışı bakımından yeniden tasarlanacaktır.** Ancak bağlantı API tipleri, optimistic concurrency ve değişmez revizyon yaklaşımı korunacaktır. “Sıfırdan tasarım”, güvenlik ve veri sözleşmelerini atıp yeni bir CRUD yazmak değildir.

Kart duvarı kaldırılır. Ana sayfada katalog tablosu, ayrı URL’de bağlantı detayı, detay içinde fiziksel şemalar/revizyonlar/kullanım alt sayfaları olur. Mantıksal şemalar ve ortamlar bağlantı detayının kardeşi değil, çalışma alanının ayrı girişleridir.

### 10.2 İstenen 15 sorunun kesin cevapları

| No | Soru | Nihai cevap |
|---|---|---|
| 1 | Ana navigasyonda Topoloji olmalı mı? | **Hayır.** |
| 2 | Kesin isim ne? | **Connections / Bağlantılar.** |
| 3 | Bağlantı, fiziksel/mantıksal şema, eşleştirme, ortam, model nerede? | Bağlantı kataloğu bu alanda; fiziksel şema bağlantı altında; mantıksal şema ve ortam proje düzeyindeki ayrı sayfalarda; eşleştirme mantıksal şemanın ortam matrisi; model Geliştirme → Modeller altında. |
| 4 | Sekme mi sayfa mı? | Çalışma alanında üç ayrı route: bağlantılar, mantıksal şemalar, ortamlar. Bağlantı detayında URL’li alt sayfalar; mantıksal detayda eşleştirmeler. Tek monolitik altı sekme yok. |
| 5 | Ortam hangi düzeyde? | **Proje düzeyinde.** Bağlantı bir veya birden çok ortamda kullanılabilir; ortam bağlantı formunun zorunlu sahiplik alanı değildir. |
| 6 | Oracle kartında ne görünür? | Ad/kod, Oracle etiketi, etkin revizyon, revizyon durumu, çalışma yeteneği, endpoint veya JNDI adı, yetkiye uygun kullanıcı adı, son test zamanı/sonucu, fiziksel ve tekilleştirilmiş mantıksal şema sayısı. Kart yalnız detay özetidir. |
| 7 | Kart hızlı işlemleri? | **Bağlantıyı test et**, **Fiziksel şemaları görüntüle**; üçüncü alan üç nokta menüsü. Ekrandaki ana işlem bağlama göre tek vurgulu butondur. |
| 8 | Düzenle/sil nerede? | `…` içinde **Bağlantı bilgilerini düzenle**, **Yeni revizyon oluştur**; ayırıcıdan sonra **Bağlantıyı sil**. Silme her zaman etki/onay ekranına gider. |
| 9 | Şema sayıları? | `Fiziksel şema: 4` ve `Mantıksal şema: 3` bağlantılı metinler. Mantıksal sayı binding sayısı değil benzersiz logical ID sayısıdır; ortam filtresi kullanılmışsa kapsam yazılır. |
| 10 | Silme bağımlılıkları? | Adları ve türleriyle fiziksel şemalar, eşleştirmeler, kullanılan sürümler/çalıştırılabilir nesneler gösterilir. Bağımlılık varken silme engellenir; otomatik cascade yok. Geçmiş run kanıtı korunur. |
| 11 | Test nerede? | Oluşturma formunun sabit alt eylem satırında; kayıt sonrasında detay başlığında ve katalog satırında görünür. Test edilen revizyon açıkça yazılır. |
| 12 | Provider/Oracle alanları/sürüm? | Önce sağlayıcı, sonra JDBC/JNDI koşullu alanları; JDBC’de host/port ve Service name veya SID, kullanıcı/parola; JNDI’de ad. Revizyon numarası sistem üretir; DB/driver sürümü algılanan bilgi veya yetkili gelişmiş ayardır. |
| 13 | Hassas bilgiler? | Kaydedilmiş parola geri doldurulmaz, gösterilmez ve kopyalanmaz. “Parola kayıtlı” bilgisi ve **Mevcut parolayı koru / Yeni parola kullan** seçimi. Gerçek yeni parola yalnız geçici form state’inde. |
| 14 | Liste/kart/karma? | **Sabit karma model:** katalog tablo, tek kayıt detayı özet kartı. Kullanıcıya alternatif card/list görünüm anahtarı yok; kayıt sayısına göre otomatik mod değiştirme yok. |
| 15 | Yüksek adet? | Sunucu arama/filtre/sıralama ve 50/100/200 satır sayfalama; toplu özet alanları; N+1 revizyon çağrıları yok. Detay ihtiyaç oldukça yüklenir. |

### 10.3 Katalog tablosunun kesin kolonları

| Kolon | Gösterim | Sıralama / filtre |
|---|---|---|
| Bağlantı | Ad birincil, kod küçük ikincil; ad bir bağlantıdır | Ad sıralaması ve ad/kod araması. |
| Sağlayıcı | Küçük sağlayıcı ikonu + `Oracle` | Desteklenen sağlayıcılar filtresi. |
| Uç nokta | JDBC: `host:port / service`; SID ise açık `SID` etiketi; JNDI adı ayrı etiketli | Metin aramasına katılır; hassasiyet/rol izinleri uygulanır. |
| Etkin revizyon | `r3 · Etkin`; yoksa `Etkin revizyon yok` | “En yeni r4” etkin r3’ü gizlemez. |
| Kullanım | `Çalıştırılabilir` veya `Yalnız test ve keşif` | Capability filtresi. |
| Şemalar | `4 fiziksel · 3 mantıksal` | Ayrı sayısal özet; bilinmiyorsa `—`. |
| Son test | Sonuç etiketi + tarih/saat; hiç yoksa `Test edilmedi` | Sonuç ve tarih sıralaması. |
| İşlemler | Etiketli `Test et` ve üç nokta | Satır bazlı yükleniyor; bütün tablo kilitlenmez. |

Varsayılan sıralama **ad A→Z**; hata durumunu incelemek için açık `Testi başarısız` filtresi bulunur. Her yenilemede satırları sessizce yeniden önem sırasına sokmak yoktur. Liste üstünde arama, sağlayıcı, test durumu ve kullanım/ortam filtreleri bulunur. Ortam filtresi doğrudan connection tablosundaki bir alanı değil, eşleştirmeler üzerinden kullanım ilişkisini ifade eder; etiketi **Kullanıldığı ortam** olacaktır.

Bağlantı `status` değeri ile canlı erişilebilirlik aynı şey değildir. `Etkin`, son testin bugün başarılı olduğu veya ilgili run’ın başlatılabildiği anlamına gelmez. En az üç bağımsız gösterim vardır: **revizyon yaşam döngüsü**, **son test sonucu**, **runtime yeteneği**. [K09]

### 10.4 Bağlantı detayı ve revizyonlar

Detay breadcrumb’ı `Proje / Bağlantılar / Oracle billing` olur. Başlık altında sağlayıcı ve açıklama; sağda `Bağlantıyı test et`, ikincil `…` bulunur. Özet kartında en fazla iki kolonluk okunabilir key-value alanları vardır; her bilgiye ayrı mini kart yapılmaz.

Alt sayfalar sırası: **Overview / Genel bilgiler → Physical schemas / Fiziksel şemalar → Revisions / Revizyonlar → Usage / Kullanım**. Sekme gibi görünürler, fakat ayrı URL taşırlar. Form doldururken sekme değiştirme pending-change kontrolüne tabidir.

| Kavram | Kullanıcıya gösterim | Gösterilmeyecek karışıklık |
|---|---|---|
| Bağlantı revizyonu | `r3`, `Taslak`, `Test edildi`, `Etkin`; oluşturma/test zamanı | Connection kaydının optimistic `version` alanını kullanıcı revizyonu sanmak. |
| Veritabanı sürümü | Son doğrulanmış testten `Oracle Database …`; zamanı yanında | Kullanıcıya gerçek DB sürümünü bir dropdown’dan değiştirebiliyormuş hissi vermek. |
| JDBC driver | Gelişmiş teşhis altında algılanan/seçili sürüm | Her kullanıcıdan serbest driver class/JAR girmesini istemek. |
| Politika sürümü ve hash | Teknik ayrıntılar; gerekirse kopyalanabilir | Normal formda zorunlu ID/hash kutuları. |

Yeni uç nokta değişikliği mevcut etkin revizyonu yerinde mutasyona uğratmaz; **yeni revizyon** olarak hazırlanır. Testi geçen taslak, etkin revizyonun yerine kendiliğinden geçmez. Etkinleştirme eylemi revizyon satırında ve etki özetiyle yapılır. Var olan schema binding’in `connectionVersionUuid` alanı ve yayımlanmış çalışma kayıtları **yeni etkin revizyona sessizce taşınmaz**. Kullanıcı eşleştirmeyi günceller, etkisini görür ve gerekiyorsa yeni çalıştırılabilir sürüm hazırlar. [K09] [K20]

### 10.5 Bağlantı oluşturma formu

**Yüzey:** `/connections/new` tam sayfa; en fazla 960 px form kabı, alanlar toplam 720–880 px kullanılabilir içerik. Form başlığı: `New connection / Yeni bağlantı`. Üç görsel bölüm vardır; yapay bir beş aşamalı wizard yapılmaz.

| Bölüm | Alanlar ve varsayılanlar | Davranış |
|---|---|---|
| Kimlik | Sağlayıcı; ad; otomatik önerilen kod; açıklama | Kod ayrıntı bağlantısıyla düzenlenir. Sağlayıcı seçilince ilgili form açılır. Güncel uygulamada yalnız Oracle doğrulanmıştır; diğer sağlayıcılar çalışır gibi sunulmaz. [K07] |
| Bağlantı | Bağlantı yöntemi; JDBC için host, port 1521, Service name/SID seçimi ve değer; kullanıcı/parola | Service name ilk seçim. SID adı, servis adı yerine sessizce kullanılmaz. Bağlantı string’i normal kullanıcıdan istenmez. |
| Gelişmiş | Bağlantı politikası ve zaman aşımları, yetkiye göre desteklenen ek seçenekler | İlk açılışta kapalı. Kullanıcıya saniye birimiyle açıklanır; API milisaniye bekliyorsa dönüşüm adapter’dadır. Desteklenmeyen TLS seçeneği eklenmez. |

JNDI seçildiğinde JDBC kullanıcı/parola/host/service alanları kaybolur ve gönderimden de çıkarılır. Açıklama, bunun yönetilen bir kaynak adına başvurduğunu söyler. JNDI’nin kullanılabilirliği ve çalıştırma yeteneği sunucu capability sonucuyla gösterilir; testin geçmesi tek başına runtime desteği değildir. [K09]

Form alt satırı soldan sağa **Vazgeç**, **Bağlantıyı test et**, **Bağlantıyı kaydet** olur. Kayıt öncesi test gereksinimi mevcut ürün güvenlik yaklaşımıyla korunur. Kaydet kapalıysa hemen yanında “Kaydetmeden önce bu bağlantıyı test edin” görünür; yalnız tooltip yeterli değildir. Form meşgulken kapanma/gezinme güvenliği korunur.

### 10.6 Testin anlamı ve durumları

Test sonucu alanı endpoint bloğunun hemen altında görünür. Başarıda test edilen yöntem, algılanan DB sürümü, zaman ve kapsam; hatada kullanıcı tarafından anlaşılabilir hata türü, güvenli ayrıntı ve tekrar test eylemi bulunur. Parola, secret locator veya ham bağlantı URL’sine gömülü gizli değer hata mesajına taşınmaz.

Test geçerliliğini etkileyen alanlar: sağlayıcı, yöntem, host/port/service/SID/JNDI, kimlik bilgisi, güvenlik/timeout politikasının testle ilgili kısmı. **Ad, kod ve açıklama değişikliği testi geçersiz kılmaz.** Test sırasında alan değişirse eski isteğin sonucu yeni form için başarı kabul edilmez; istek ve form revizyonu eşleştirilir. Mevcut formun tek fingerprint yaklaşımı bu yönde ayrıştırılacaktır. [K07]

Taslak bağlantı testi ile kaydedilmiş revizyon testi ayrı kayıt bağlamlarıdır. Kaydetme sırasında sunucu ilgili kanıtı yeniden doğrulayacak veya kayıtlı revizyon için test gerektirecektir. UI, geçici form başarısını kendiliğinden `ACTIVE` durumuna çevirmeyecektir.

### 10.7 Parola ve gizli bilgi düzenleme

Kaydedilmiş parola alanının gerçek değeri geri gelmez. Detayda **Parola kayıtlı**; editörde **Mevcut parolayı koru** varsayılanı bulunur. `Yeni parola kullan` seçildiğinde boş parola alanı açılır; `Parolayı göster` yalnız o oturumda yeni girilen değeri gösterir. Kayıtlı parolayı ortaya çıkarmaz. Boş değer “eski parolayı sil” diye yorumlanmaz; silme ayrı ve açık bir işlem olmadıkça gönderilmez.

**SÖZLEŞME BOŞLUĞU:** Güncel edit formu parolayı tekrar ister. Güvenli “koru” için sunucunun eski secret’ı kullanıcıya geri vermeden yeni revizyona referanslayabilmesi gerekir. Bu sağlanana kadar “korundu” yazıp boş parola göndermek yasaktır. Geçiş sürümünde yeniden giriş gerekiyorsa gerekçe açıkça gösterilir; hedef davranış değiştirilmez. [K08] [K09]

Endpoint, kullanıcı adı ve JNDI adı da her role sınırsız açılmaz. Operasyon rolü gerektiğinde **bağlantı takma adı + fiziksel şema** görür; yetkili geliştirici/ağ destek rolü endpoint ayrıntısına iner. Her maskeleme server-side yetkilendirmeyle tamamlanır. İstemcide gizlemek tek başına veri koruması değildir.

### 10.8 Fiziksel şema tanımı

**Sahibi bağlantıdır.** `/connections/:id/physical-schemas` tablosu şema adı, fiziksel referans, durum, ilişkili mantıksal şema/ortam sayısı ve kullanım eylemi gösterir. `Fiziksel şema ekle` 640 px modal açar. Bağlantı formda salt okunur bağlamdır; kullanıcı yeniden bağlantı seçmez.

Alanlar: görünen ad, teknik şema/owner adı, otomatik kod; gerekiyorsa açıklama yalnız sözleşme destekliyorsa. Keşfedilmiş schema listesi varsa combobox; yoksa izin verilen adın girildiği alan ve doğrulama. “Şemayı tanımlamak” Oracle’da `CREATE USER` veya yeni fiziksel şema yaratmak değildir; açıklama bunu açıkça söyler.

ODI’deki work schema kavramı mevcut AKIŞ `PhysicalSchema` tipinde ayrı alan olarak doğrulanmamıştır. Bu nedenle normal forma hayali zorunlu “Work schema” alanı eklenmeyecektir. Gerekirse gelecekte ayrı sözleşme ve UI gerekçesiyle ele alınır; bugünkü tasarım kararını engellemez. [K09] [D03]

### 10.9 Mantıksal şema ve ortam eşleştirmesi

Mantıksal şema ekranı proje düzeyinde tablo kullanır. Ad, kod, eşleştirilmiş ortam sayısı, eksik/gözden geçirilecek eşleştirmeler ve kullanım sayısı gösterir. `Yeni mantıksal şema` küçük modal açar. Bağlantı burada zorunlu alan değildir.

Detayda **ortam matrisi** bulunur. Her satır bir ortamdır; kolonlar **Ortam → Bağlantı → Fiziksel şema → Sabit revizyon → Durum → Düzenle**. Mantıksal şema başlıkta sabittir. Eşleştirme ekleme/düzenleme ilgili satırın altında içerik içi panel açar; kullanıcı farklı mantıksal şema seçemez.

Seçim sırası: ortam zaten satırdan gelir; bağlantı seçilir; yalnız o bağlantıya ait fiziksel şemalar gelir; çalıştırmaya uygun revizyonlar ve gerekçeli uygun olmayanlar gösterilir. Uygunluk veri gelmeden “uygun” varsayılmaz. Aynı mantıksal şema/ortam için birden fazla etkin eşleştirme kayıt edilemez; çakışma sunucuda da denetlenir. Bu kural ODI’nin çözümleme fikrinden yararlanır ve AKIŞ tuple’ına uygulanır. [D02] [K09]

Örnek kullanıcı özeti: **“BILLING_SOURCE, Test ortamında Oracle test / BILLING_OWNER / r4 üzerinden çözümleniyor.”** Üretim eşleştirmesi değişecekse aynı özet eski → yeni karşılaştırmasıyla gösterilir. Geçmiş ve çalışan run’ların pinlenmiş hedefi değişmez.

Bağlantı detayındaki `Kullanım` sayfası aynı eşleştirmelerin **salt okunur ilişkisel görünümüdür**; burada ikinci bir bağımsız binding editörü yapılmaz. “Eşleştirmeyi düzenle” asıl mantıksal şema detayına gider. Böylece bir ilişkinin iki farklı formda farklı kurallarla yönetilmesi önlenir.

### 10.10 Ortamlar

Ortam listesi **ad, kod, risk sınıfı, eşleştirme kapsamı** gösterir. Kullanıcıya ham `policyVersion` alanı açılmaz. Üretim işareti ortam adına bakarak türetilmez; sunucunun güvenilir risk/ortam sınıflandırması kullanılır. Mevcut tipte `risk` opsiyoneldir; boş değer “güvenli ortam” sayılmaz. [K09]

Ortam seçimi topbar’da küresel ve her ekranı sessizce değiştiren bir anahtar olmayacaktır. Geliştirmede **Çözümleme ortamı**, operasyonda **Ortam filtresi**, çalıştırma dialog’unda **Çalıştırılacak ortam** ayrı ve açık adlar taşır. Son seçilen düşük riskli ortam proje bazında önerilebilir; üretim seçimi kullanıcı tarafından yeniden doğrulanır. Route veya browser geçmişi, onay vermeden run başlatmaz.

### 10.11 Silme ve bağımlılık anlatımı

Silme dialog’u 720 px genişliğinde, şu sıra ile açılır: **nesne adı → neyin silineceği → bağımlılık özeti → ilgili kayıtların adları → çözüm yolu → onay eylemi**. Bağımlılık sorgulanırken kırmızı onay butonu etkin değildir. Hata halinde bağımlılık yokmuş gibi davranılmaz.

Örnek metin: “Oracle billing bağlantısı 4 fiziksel şema ve 3 ortam eşleştirmesinde kullanılıyor. Bağlantıyı silmeden önce bu kullanımları taşıyın veya kaldırın. Geçmiş çalıştırma kayıtları silinmez.” Sayılar temsili olup gerçek sunucu sonucundan alınır. Büyük bağımlılık listesi türlere göre gruplandırılır, ilk kayıtlar ve toplam sayılar gösterilir; `Tüm kullanımları aç` ile detay sayfasına gidilir.

Bağımlılık yoksa onay etiketi **Bağlantıyı sil**, vazgeç etiketi **Bağlantıyı koru** olur. Genel “Evet / Hayır” kullanılmaz. Eşzamanlı değişiklikte beklenen sürüm uyuşmazlığı açıklanır, kayıt yeniden okunur; otomatik tekrar silme yapılmaz. Güncel silme API çağrısı zaten beklenen version taşımaktadır; bu korunur. [K05]

### 10.12 Ölçek ve gereken UI projeksiyonları

Katalog için bağlantı başına en yeni tüm sürümleri istemek yerine **tek özet yanıtı veya sınırlı toplu projeksiyon** gerekir. Gerekli alanlar: görünen kimlik, etkin revizyon özeti, son test, runtime yeteneği, şema sayıları, izin verilen eylemler ve sonraki sayfa bilgisi. Bu bir backend yeniden tasarım tarifi değil; doğru ve hızlı UI için veri ihtiyacıdır.

Ana liste, fiziksel şema tablosu, mantıksal şemalar ve modeller birbirinin hatasından bağımsız yüklenir. Arama 300 ms gecikmeyle gönderilir; filtre ve sayfa URL’ye yazılır. Tek satır testi o satırı meşgul eder. Yüksek adetlerde bütün bağlantılara otomatik test başlatılmaz; “toplu test” bu sürümün kapsamı dışıdır.

## 11. Modeller Çalışma Alanı

### 11.1 Amaç ve yerleşim

**KARAR:** Modeller, Geliştirme ağacının son kökünden açılan bağımsız metadata çalışma alanıdır. Bağlantılar ekranının “Catalog” sekmesi olmayacaktır. Ana amacı kaynak sistemin tablo/görünüm/kolon bilgisini incelemek ve veri akışında güvenilir biçimde kullanmaktır; veritabanı yöneticisinin bütün DDL işlerini yapmak değildir.

Ana sayfa model tablosu kullanır: **Model adı, mantıksal şema, nesne sayısı, son metadata güncellemesi, doğrulama/uyum durumu**. Şu an API’de bulunmayan son güncelleme veya kapsam bilgisi sahte değerlerle doldurulmaz; projeksiyon eksiği olarak ele alınır. Modelin mantıksal şemaya bağlı olması mevcut tipte doğrulanmıştır. [K09]

Model detayı solda alt model/veri nesnesi ağacı, sağda seçili nesnenin metadata tablosu ve alt açıklama alanıdır. Başlıkta **Model → Mantıksal şema → İncelenen ortam → Çözümlenen bağlantı/fiziksel şema** özeti görülür. Veri nesnesi adından fiziksel ortam tahmin edilmez.

### 11.2 Keşif ve içe alma akışı

Birincil işlem **Metadata içe al** olacaktır. İşlem sırası: mantıksal şema/model bağlamını al → keşif ortamını seç → çözümlenen fiziksel hedefi göster → tablo/görünüm adına göre kapsam daralt → sonuçları listele → seçili nesnelerin metadata’sını içe al. Canlı satır verisi bu işleme dahil değildir.

Keşif düğmesi bağlantı detayında yardımcı bir bağlantı olarak bulunabilir; kullanıcıyı doğru model/keşif yüzeyine taşır. Aynı keşif formu iki yerde kopyalanmaz. JNDI veya test-only bağlantı keşif destekliyorsa bu kullanılabilirlik ayrıca gösterilir; metadata okunabilmesi veri akışının çalışacağı anlamına gelmez. [K09]

| Durum | Ekran davranışı |
|---|---|
| Henüz model yok | “Kaynak metadata’sını düzenlemek için bir model oluşturun.” + `Model oluştur`. |
| Mantıksal şema eşleştirilmemiş | Seçili ortam ve eksik eşleştirme açık; ilgili eşleştirmeye giden eylem. |
| Keşif yetkisi/bağlantısı yok | Hata veya capability gerekçesi; boş tablo sanılmaz. |
| Kısmi keşif / limit | Taranan kapsam ve limit yazılır; bütün veritabanı taranmış gibi toplam verilmez. |
| Metadata değişmiş | Eklenen/değişen/kaldırılan kolon karşılaştırması; güncelleme öncesi etkilenebilecek akışlar. |
| Veri nesnesi kaldırılmış | Kullanım bağımlılıkları korunur; mapping referansı sessizce başka tabloya geçmez. |

### 11.3 Seçili veri nesnesi

Kolon tablosu **Ad, veri tipi, uzunluk/precision/scale, nullable, anahtar/rol bilgisi** gösterir; yalnız metadata’nın gerçekten sağladığı alanlar doldurulur. `Veri akışında kullan` eylemi yeni akış veya mevcut akışın kaynak/hedef seçimine yönlendirir. Kullanıcıdan tablo adını yeniden yazması istenmez.

Canlı **Veri önizleme** bu sürümde varsayılan bir düğme olmayacaktır. Metadata preview ile gerçek veri okuması farklı yetki, maliyet ve gizlilik gerektirir. Mevcut kaynak ön kontrolü de bütün tablolar için genel preview değildir. Bu nedenle preview gerekiyorsa ayrı, sınırlandırılmış ve yetkili bir sözleşmeyle etkinleştirilir. [K21]

## 12. Mapping Editörü

### 12.1 Nihai çalışma yüzeyi

**KARAR:** Kullanıcıya görünen nesne türü **Veri akışı** olacaktır. Editörün merkezinde **veri setleri/ilişkileri ve kolon eşleştirme tablosu** bulunacaktır. Paket editörünün kontrol akışı diyagramı birebir buraya kopyalanmayacaktır.

Üstte nesne adı, klasör yolu, taslak/sürüm ve kaydedilmemiş değişiklik bilgisi bulunur. Başlık eylemleri **Kaydet** birincil; **Doğrula** ve uygun olduğunda **Çalıştır** ikincil; `…` içinde sürümler, dışa aktarma ve gelişmiş salt okunur tanım bulunur. Alt sekmeler **Design / Tasarım**, **Versions / Sürümler**, **Run history / Çalıştırma geçmişi** olur. Mantıksal/fiziksel çözümleme tasarım içindeki bağlam alanından erişilir; kullanıcı teknik binding UUID’si düzenlemez.

Tasarım yüzeyi üç bölümden oluşur: sol **Veri nesneleri** seçicisi; üst merkez **Kaynaklar → Dönüşüm/ilişki → Hedefler** ilişkisi; alt merkez **Kolon eşleştirmeleri**. İlk sürümde ilişki alanı yalnız desteklenen veri seti ilişkilerini gösterir. Backend’in temsil etmediği join/aggregate/lookup işlemleri renkli düğüm olarak çizilip çalışırmış gibi pazarlanmaz.

### 12.2 Kaynak/hedef ekleme ve metadata

`Kaynak ekle` ve `Hedef ekle` etiketli düğmeleri görünürdür. Sürükle-bırak aynı işlemin hızlandırıcısıdır. Ekleme seçicisi Model → Veri nesnesi yolu ve arama sunar; yalnız UUID listesi değildir. Nesne eklendikten sonra kısa kartta **takma ad, model, mantıksal şema ve veri nesnesi** görünür.

İnsan okuyabilir takma ad değiştirilebilir; referans kimliği sabittir. Veri seti silinmeden önce etkilenen kolon eşleştirmeleri ve ifadeler sayılır. Kullanıcı **veri setini ve bağlı eşleştirmeleri kaldır** işlemini onaylar; tek bir geri alma ile tamamı geri gelir. Serbest input’ta ID değiştirmek bütün referansları geçersiz hale getirmemelidir. Güncel grid’deki ID odaklı düzen bu nedenle değiştirilir. [K13]

### 12.3 Kolon eşleştirme tablosu

| Kolon | Düzenleme standardı |
|---|---|
| Hedef alan | Veri seti + kolon; hedef sırasına göre; gerekli alan işareti. |
| Kaynak / ifade | Metadata’dan kaynak kolonu seçimi veya `İfade oluştur` eylemi. |
| Kaynak veri tipi | Salt okunur; metadata bilinmiyorsa `Bilinmiyor`. |
| Hedef veri tipi | Salt okunur; uyumsuzluk metin ve ikonla. |
| Dönüşüm / doğrulama | Desteklenen dönüşümün okunabilir özeti; hata varsa satır içi bağlantı. |
| İşlemler | Eşleştirmeyi temizle, ifadeyi düzenle; yalnız hover’a bağlı olmayan menü. |

Tablonun üstünde **Eşleşmeyen alanlar**, **Hatalı alanlar** hızlı filtreleri ve kolon araması bulunur. Otomatik eşleştirme ad/tip önerisidir; sonuç özeti gösterilmeden var olan kullanıcı ifadelerini üzerine yazmaz. Hedef kolona birden fazla belirsiz eşleştirme yapılamaz. `Enter` ile aşağı satıra ilerleme gibi mevcut grid verimlilik yaklaşımı korunur ve bütün klavye sözleşmesi test edilir. [K13]

### 12.4 JSON yerine ifade düzenleyicisi

Güncel `MappingGrid` ifade hücresinde JSON gösterip `JSON.parse` çalıştırmaktadır. Bu davranış normal kullanım yüzeyinden kaldırılacaktır. [K13]

**Kesin uygulama yönü:** Mevcut ifade AST’si arka planda korunur. Üstüne **desteklenen işleç/fonksiyon kataloğu + kolon seçici + argüman alanları + okunabilir ifade özeti** eklenir. İlk sürümde backend’in kabul ettiği düğümlere karşılık gelen yapılandırılmış builder kullanılır. Desteklenmeyen serbest SQL metnini “expression” alanına alıp aynı AST’ye çevrilebiliyormuş gibi yapmak yoktur.

İfade editorü seçili satırın altında genişleyen içerik panelidir; büyük sağ drawer değildir. Panelde **Uygula** ve **Vazgeç** bulunur. Eksik argümanlar, tip uyuşmazlığı ve geçersiz referanslar alan düzeyinde gösterilir. Parse veya dönüşüm başarısızsa eski çalışan ifade korunur; kullanıcının yazdığı metin kaybolmaz. Ham tanım yalnız `… → Gelişmiş → Tanımı görüntüle` altında salt okunurdur.

### 12.5 Doğrulama ve fiziksel bağlam

Doğrulama iki ayrı sonuç üretir: **Tasarım doğrulaması** ve **Seçili ortamda çalıştırılabilirlik**. İlkinde eksik hedef, geçersiz ifade, silinmiş kolon, yinelenen hedef gibi yerel/metadata kuralları; ikincisinde mantıksal şema eşlemesi, sabit revizyon ve runtime yeteneği kontrol edilir.

`Çözümleme ortamı: Test` alanının yanında örneğin **BILLING_SOURCE → Oracle test / BILLING_OWNER / r4** görünür. Başka ortama bakmak taslağın SQL’ini, mantıksal kimliğini veya yayımlanmış fiziksel manifestini kendiliğinden değiştirmez. Tasarım düzeyi değişiklik ile çalışma ortamı incelemesi birbirinden ayrılır.

## 13. Prosedür Editörü

### 13.1 Sınırsız adım hedefinin doğru yorumu

**KARAR:** Ürün arayüzünde keyfî bir adım sayısı sınırı olmayacaktır. Adımlar sanallaştırılmış gezinme listesi, arama ve seçili adım editorüyle yönetilecektir. “Sınırsız”, bütün adımların DOM’a aynı anda basılması veya bugünkü yürütme profilinin sınırlarının kaldırıldığı anlamına gelmez.

Güncel editor `limits` üzerinden runtime profili bilgisi almaktadır; varsayılan `maxTasks` değeri 1000’dir. Bu sayı kullanıcının genel prosedür kavramına gömülmeyecek, **seçili çalıştırma profilinin sınırı** olarak gösterilecektir. Kaydetme sözleşmesinde ayrıca sert bir sınır varsa sistem bunu açıkça raporlar; UI kaydı başarılıymış gibi göstermeyecektir. Yüksek adetli authoring için gerekli kalıcı tanım sözleşmesi ayrı teslim kapısıdır. [K11]

### 13.2 Yerleşim ve adım gezinimi

Sol adım paneli 280 px varsayılan, 220–360 px ayarlanabilir; içerikte seçili adım ayrıntısı bulunur. Üstte `Adım ekle`, arama ve toplam adım bilgisi vardır. Her satır sıra, ad, işlem türü, kaynak/hedef rolü ve hata işareti gösterir. Çoklu satır formu liste içinde açılmaz.

Ada tıklamak adımı seçer. Sıralama sürükleme ile veya **Yukarı taşı / Aşağı taşı** menüleriyle yapılır. Kaynak rowset ve tüketicisi bir ilişki grubu olarak gösterilir; grubun taşınması ilişkiyi bozmaz. Bağımlılığı geçersiz yapacak hareket kullanıcıya nedenini söyler ve engellenir. Otomatik normalizasyonla ilişkiyi sessizce silmek yoktur. [K11]

Adım **Çoğalt**, **Devre dışı bırak**, **Sil** eylemleri vardır. Çoğaltma yeni sabit kimlik üretir, adı “Kopya” ekiyle önerir; diğer adımları eski kimliğe bağlamayı değiştirmez. Devre dışı bırakma backend semantiği desteklenmeden yalnız gri satır olarak uygulanmaz; destek yoksa açıklamalı kapalı eylemdir. Silme, rowset/parametre bağımlılıklarını gösterir ve geri alınabilir authoring işlemi olur.

### 13.3 Adım tipleri ve SQL

| Kullanıcıya görünen işlem | Ana form | Çalıştırma güvenliği |
|---|---|---|
| Truncate | Hedef veri nesnesi ve açık hedef özeti; üretilmiş komut incelemesi | Yıkıcı işlem etiketi; çalıştırma ön kontrolünde nesne/ortam onayı. |
| Select | Kaynak bağlamı ve kaynak SQL alanı; çıktı sözleşmesi | Satır/byte sınırları ve ilgili profil açık. |
| Insert | Hedef bağlamı; hedef SQL veya desteklenen parametre/kolon eşlemesi | Kaynak çıktısına bağımlılık görünür. |
| Update / Merge | Hedef SQL; etki/risk doğrulaması | Genel SQL desteği gerçek capability ile etkin. |
| PL/SQL | Çok satırlı kod editorü; değişken/parametre referansları | “PL/SQL yazılabiliyor” runtime izni anlamına gelmez. |
| Stored procedure | Şema/prosedür seçimi ve desteklenen parametre alanları; gerekirse çağrı özeti | Yetki, imza ve destek kontrolü. |
| İstatistik toplama | Hedef nesne ve izinli seçenekler | Keyfî komut yerine desteklenen işleme özgü form. |
| Serbest SQL | SQL editorü, rol ve açık bağlam | Parser/policy/capability izin vermiyorsa çalıştırma engellenir. |

Bunlar **nihai ürünün authoring kapsamıdır**. Güncel mimari belge Oracle V1 için dar bir SELECT/INSERT/TRUNCATE/izinli istatistik alt kümesi tarif etmektedir. Geniş SQL tipi seçilmesi halinde kullanıcı “Tanımlanabilir; bu runtime profilinde çalıştırılamaz” ayrımını görür. Destek genişletilmeden normal çalıştırma düğmesi açılmaz. [K20]

SQL alanı düz küçük textarea yerine satır numarası, SQL renklendirme, arama, girinti, okunabilir hata konumu ve tam ekran odak modu olan ortak bir editor olacaktır. **SQL editor katmanı için CodeMirror 6 ve SQL dil paketi seçilmiştir**; mevcut React yapısına ortak adapter ile bağlanacak ve lazy-load edilecektir. SQL paketinin resmi kaynak belgesi schema tabanlı tamamlama ve PL/SQL dialect tanımını göstermektedir. Repository’de kurulu olduğu veya bu projede test edildiği varsayılmamıştır. Resmi GitHub deposu yeni barındırma adresine taşınmış ve arşivlenmiştir; uygulama grubunda güncel dağıtım kaynağı, sürüm kilidi ve bağımlılık güvenliği doğrulanacaktır. Bu bakım kapısı farklı bir UI seçeneği değil, seçilen bağımlılığın güvenli teslim koşuludur. [D28] Kullanıcının SQL girintisi otomatik yeniden formatlanmayacaktır.

### 13.4 Her adımın bağlantı ve şema bağlamı

Seçili adımın SQL üstünde tek bir **Çalışma bağlamı** alanı olacaktır:

```text
Rol: Hedef                 Çözümleme ortamı: Test
Mantıksal şema: BILLING_TARGET
Çözümlenen bağlantı: Oracle test  >  Fiziksel şema: BILLING_STG  >  Revizyon: r4
Durum: Bu ortam için eşleştirme doğrulandı          [Eşleştirmeyi aç]
```

Kullanıcı normalde mantıksal şemayı seçer; bağlantı, fiziksel şema ve revizyon bu seçim ile ortamdan çözümlenir. Üç alan birbirinden bağımsız serbest dropdown olarak verilmez; bu tutarsız kombinasyon üretir. Eşleştirme yoksa açıklama ve düzeltmeye giden bağlantı gösterilir. Yetkisi olmayan kullanıcı eşleştirmeyi okuyabilir ama değiştiremez.

Kaynak ve hedef içeren adımlarda iki açık başlık kullanılır: **Kaynak SQL** ve **Hedef SQL**. Mevcut rowset yaklaşımı iki ayrı bağlı adım gerektiriyorsa UI bunları ilişki grubu olarak sunar; fiziksel veri taşımanın tek SQL ifadesinde yapıldığını ima etmez. [K20]

### 13.5 Gelişmiş ayarlar ve risk

Adımın hata davranışı, desteklenen transaction/parametre seçenekleri gelişmiş bölümde yer alır. **Bağlantı zaman aşımı alanları adım formunda tekrar bulunmaz.** Etkin politika salt okunur `Bağlantıdan devralınan ayarlar` altında açıklanabilir. Aynı timeout’un hem bağlantıda hem adımda çelişen kaynakları olmaz. [K20]

TRUNCATE ve diğer yıkıcı eylemler, editorün tamamını kırmızıya boyamaz. Adım listesinde küçük uyarı ikonu ve **Yıkıcı işlem** etiketi bulunur. Run başlatma ön kontrolü seçili üretim ortamını, fiziksel hedefi ve etkilenecek adımları topluca gösterir. Her tuşa basışta onay dialog’u çıkarılmaz; doğru yerde bilinçli onay alınır.

### 13.6 Kaynak ön kontrolü

Mevcut `procedure-preflights` işlemi yalnız belirli yayımlanmış Oracle prosedürlerinin kaynak okuma yarısını doğrular; hedef oturumu açmaz ve satır değerlerini geri döndürmez. Bu işlem, **Kaynak okumasını doğrula** adıyla, kapsam açıklaması ve yalnız uygun profile sahip sürümde gösterilecektir. “Tüm prosedürü simüle et” olarak yeniden etiketlenmeyecektir. [K21]

## 14. Paket/Workflow Editörü

### 14.1 Mevcut liste için karar ve ODI’den alınan düzen

**Mevcut basit paket adım listesi ana editor olarak kaldırılacaktır.** Adım listesi erişilebilir yardımcı görünüm olarak yaşayabilir; asıl çalışma yüzeyi diyagram olacaktır. Çünkü adımların adı ve türü, bir paketin başarı/hata/koşul geçişlerini anlatmaya yetmez. [K12]

ODI dokümanı palet, diyagram, toolbar, özellik alanı ve Overview/Diagram ayrımını tarif eder. AKIŞ bu çalışma ayrımını alacaktır; eski masaüstü görünümünü ve ikon yoğunluğunu almayacaktır. Buradaki yerleşim, ODI ekran görüntüsünün görsel ölçümüne değil resmi metinsel açıklamaya dayanır. [D01]

### 14.2 Kesin çalışma masası

| Bölge | Boyut / yer | İçerik ve davranış |
|---|---|---|
| Nesne başlığı | İçerik üstü, ortak editor başlığı | Paket adı, klasör, taslak/sürüm, kaydedilmemiş değişiklik. |
| Üst eylemler | Başlığın sağında | **Kaydet** birincil; **Doğrula**, **Çalıştır** ikincil; üç nokta gelişmiş eylemler. |
| Sekmeler | Başlığın altında | **Diagram / Diyagram** varsayılan; **Overview / Genel bilgiler**; sürümler ortak nesne kabuğunda. |
| Sol palet | 240 px; 200–320 px ayarlanabilir | Nesneler ve kontrol adımları; arama; görünür tıklayarak ekleme. |
| Diyagram | Kalan alan; kendi pan/zoom yüzeyi | İsimli düğümler, portlar, metinli geçişler, başlangıç işareti. |
| Alt özellik paneli | Başlangıç 280 px; 160–440 px ayarlanabilir | Seçili adım/bağlantı özellikleri; hiç seçim yoksa kısa yardım ve doğrulama özeti. |
| Diyagram araç satırı | Canvas üstünde, nesne eylemlerinden ayrı | Geri al/ileri al, otomatik hizala, zoom, sığdır, palet/özellikler aç-kapat. |

Sayfa ilk açıldığında üç farklı sol ağaç görünmez. Geniş ekranda proje ağacı açık kalabilir; 1280–1439 px aralığında editor odak moduyla uygulama sidebar’ı daralır, paket paleti korunur. Kullanıcı ana projeye dönüş kontrolünü kaybetmez.

### 14.3 Palet ve nesne ekleme

Palet iki gruptur: **Proje nesneleri** ve **Kontrol adımları**. Proje nesneleri içinde Veri akışı, Prosedür, Paket ve uygun Senaryo referansları aranır. Kontrol adımları içinde desteklenen Değişken işlemi/Koşul görünür. Gerçek modelin kaydedemediği bir düğüm türü etkin bir araç olarak gösterilmez.

Sürüklenen nesne diyagrama bırakıldığında yeni adım oluşur; referans nesnenin kendisi çoğaltılmaz. Aynı işlem palet öğesi seçilip **Diyagrama ekle** ile yapılır. Çift tıklama da tek adım ekler; farklı event’lerin iki kayıt yaratmaması test edilir. Klavye ile ekleme seçili düğümün yakınına veya görünür alanın merkezine yerleştirir.

İlk adım eklendiğinde başlangıç olarak işaretlenir. Sonradan başka bir düğüm **Başlangıç adımı yap** eylemiyle tek başlangıç olur. Başlangıç hem küçük işaret hem `Başlangıç` metniyle görünür; yalnız renk veya küçük üçgen değildir.

### 14.4 Geçişlerin semantiği

| Kaynak adım türü | Çıkışlar | Görsel ve metinsel anlam |
|---|---|---|
| Normal çalıştırılabilir adım | **Başarılı**, **Hatalı** | Başarılı çizgi düz; hata çizgisi kesik ve hata ikonu. Her ikisinde metin etiketi. |
| Boolean koşul/değişken değerlendirmesi | **Doğru**, **Yanlış**, gerektiğinde **Değerlendirme hatası** | Doğru ve yanlış normal iş dallarıdır; yanlış kırmızı hata hattı değildir. |
| Son adım | Çıkış olmayabilir | Terminal durum doğrulama kurallarıyla açıklanır. |

**False başarısızlık değildir.** Koşulun false olması beklenen iş akışıdır; değerlendirme sırasında SQL/ifade hatası oluşması ayrı bir durumdur. AKIŞ’ta Doğru ve Yanlış yolları metin/port adı ve çizgi biçimiyle ayrılır; başarı rengine bakarak anlam çıkarılması istenmez.

Bu sürümün kontrol akışı **tek yürütme kolu, açık koşullu dallanma ve döngüsüz graf** olarak tasarlanacaktır. Paralel fork/join, bekleyen bütün kollara bariyer ve kontrolsüz döngü yoktur. Bir adımın birden fazla giriş alması paralel AND-join değil, alternatif yollardan erişim anlamına gelir. Aynı çıkış portundan birden fazla hedef bağlantı kurulmaz. Paralellik gerekiyorsa ayrı Çalıştırma planı kavramı ve gerçek runtime sözleşmesiyle ele alınır; çizgide saklı semantik yaratılmaz.

### 14.5 Bağlantı kurma ve özellikler

Fareyle port sürüklemek bir yöntemdir. Eşdeğer erişilebilir yöntem: düğüm seç → **Geçiş ekle** → çıkış türünü seç → hedef adımı seç → onayla. Mevcut bağlantı seçildiğinde alt panel kaynak, çıkış koşulu ve hedefi gösterir. Hedef değiştirilebilir; ilişki türü yalnız kaynak türünün izin verdiği seçeneklerdendir.

Alt panel adım seçiliyken **Genel**, **Bağlı nesne**, **Geçişler** bölümlerini gösterir. İş nesnesinin SQL’i paket içine ikinci kez kopyalanmaz. **Bağlı nesneyi aç** hem panelde etiketli link hem bağlam menüsünde bulunur. Referans sürüm stratejisi açık gösterilir; “en yeni” ifadesi yayımlanmış bir senaryonun pinlenmiş sürümünü belirsizleştirmez.

### 14.6 Çoğaltma, silme ve düzenleme güvenliği

Çoğaltma yeni sabit adım kimliği üretir; bağlı nesne referansı ve kullanıcı ayarları kopyalanır, **gelen/giden bağlantılar otomatik kopyalanmaz**. Böylece görünmeyen çift çalışma yolu oluşmaz. Ad değişince kimlik ve ilk adım referansı değişmez.

Silme öncesi “Bu adım ve 3 geçiş kaldırılacak” gibi etki özeti verilir. Geçişler düğümle birlikte kaldırılır; eski komşular arasında otomatik köprü kurulmaz. Tek geri alma adımı düğümü, ayarlarını, geçişlerini ve başlangıç durumunu birlikte geri getirir. Başlangıç silindiyse yeni başlangıç seçilmeden paket çalıştırılabilir doğrulaması geçmez.

Yalnız editör içi silme ile gerçek bağlı prosedürü silme aynı menü etiketi değildir. **Paketten adımı kaldır**, referans prosedürü repository’den silmez.

### 14.7 Toolbar, zoom ve kaydetme

Zoom aralığı %25–%200; `Diyagrama sığdır` bütün paketi, `Seçime odaklan` seçili adımı gösterir. Fare tekerleği tarayıcı sayfasını beklenmedik biçimde ele geçirmeyecek şekilde canvas odaklı çalışır; `+ / −` düğmeleri her zaman erişilebilir olur.

**Otomatik hizala** açık kullanıcı eylemidir, geri alınabilir ve sadece yerleşimi değiştirir. Dosya açılırken veya bir isim değişirken mevcut düzen kendiliğinden yeniden çizilmez. Kaydedilmiş düğüm koordinatları semantik yürütme sırasıyla karıştırılmaz. Geçişlerin anlamını veya senaryo planını sadece layout değişti diye değiştiren model kullanılmaz; mevcut hash sözleşmesi layout ayrımını desteklemiyorsa bu bir ön koşuldur.

Kaydet, sunucu onayından sonra “Kaydedildi” gösterir. İstek sürerken editöre yeni değişiklik gelirse bu değişiklik temiz sayılmaz. Başlıkta **Kaydedilmemiş değişiklikler** metni ve nokta birlikte bulunur. Çakışmada sessiz son-yazan-kazan yoktur; karşılaştırma/yeniden yükleme yolu verilir.

### 14.8 Overview / Diagram ayrımı

**Genel bilgiler**: ad, kod, açıklama, klasör, değişken/parametre özeti, referans nesneler ve kullanım. **Diyagram**: çalışma mantığı ve adım özellikleri. Overview ikinci bir KPI dashboard’u veya JSON sayfası değildir. Diyagramdan Overview’a geçmek state’i sıfırlamaz; aynı taslağın iki görünümüdür.

### 14.9 Çalıştırma ve simülasyonun kesin sözleşmesi

| Eylem | Anlamı | Etkinleştirme koşulu |
|---|---|---|
| Doğrula | Başlangıç, referans, port, erişilebilirlik, döngü ve desteklenen türleri kontrol et | Yerel doğrulama çalışır; sunucu doğrulaması ayrı sonucu gösterir. |
| Simüle et | Veritabanı oturumu açmadan ve veri yazmadan; örnek değişkenlerle yol/plan önizlemesi | Bu garantiyi veren ayrı desteklenmiş simülasyon sözleşmesi. Kaynak sorgusunu sessizce çalıştırmaz. |
| Çalıştır | Seçilen kayıtlı/çalıştırılabilir sürüm ve açık ortam için gerçek run | Capability, izin, doğrulama ve onaylar uygunsa. |

Mevcut paket listesi, tam bir graph editor veya güvenli simülasyon motoru kanıtı değildir. Simülasyon sözleşmesi yoksa buton açıklamalı kapalıdır: **“Bu sürümde paket simülasyonu desteklenmiyor.”** Aynı yerde `Tasarımı doğrula` etkin olabilir; bu bir simülasyon sonucu diye etiketlenmez. Kaynak ön kontrolü de bu eksiği otomatik karşılamaz. [K12] [K21]

**Paket doğrulama kuralları:** tam bir başlangıç; kayıp nesne referansı olmaması; kimlik tekilliği; geçiş portu uygunluğu; kendine bağlantı/döngü olmaması; gerekli koşul dallarının tanımlanması; erişilemeyen adımın hata/uyarı politikasına göre işaretlenmesi; iç içe paketlerde dolaylı kendini çağırma olmaması. Eksik taslak kaydedilebilir ancak çalıştırılabilir sürüm yapılamaz; kalıcı tanım sözleşmesi buna izin vermiyorsa uygulama öncesi açıkça hizalanır.

### 14.10 React uygulama yönü

**Grafik etkileşim katmanı için React Flow seçilecektir.** İş nesnesinin tipi, sürümü ve geçiş anlamları ayrı adapter’da kalır; React Flow düğüm verisi doğrudan backend domain modeli haline gelmez. Kütüphanenin odak/klavye ve yerelleştirilebilir ARIA olanaklarından yararlanılır, fakat bunlar uygulamanın otomatik WCAG uyumluluğu sayılmaz. [D26]

Mevcut paket listesi graf modeline **salt okunur veya kayıpsız migrasyonla** açılmalıdır. Kaynak listede bulunmayan başarı/hata geçişleri varsayımla üretilip iş anlamı yaratılmaz. Eski `firstStepId` ve referanslar kontrol edilir; belirsiz kayıt “otomatik düzeltildi” diye sessizce dönüştürülmez. Yeni modelin kaydet–yeniden aç testleri geçmeden eski paket editorü kaldırılmaz.

## 15. Operasyon ve Çalıştırma Geçmişi

### 15.1 Ana ekran: nesne değil çalıştırma odaklı

**KARAR:** Operasyonlar girişinde çalıştırma tablosu olacaktır. Aynı nesnenin bütün geçmişi, nesne filtreli görünüm veya nesne detayındaki geçmiş sekmesinden açılır. Mevcut “önce nesneyi aç, sonra run’ı bul” düzeni ana yüzeyden kaldırılır. [K14]

Üstte kısa amaç: “Çalıştırmaları izleyin, hataları inceleyin ve izin verilen müdahaleleri yönetin.” Sağda **Çalıştırma başlat** birincil, **Yenile / Canlı** ikincil. `Aktif`, `Başarısız` sayaçları küçük filtre bağlantılarıdır; sayfa çok sayıda büyük metrik kartıyla doldurulmaz.

| Görünüm | Kesin varsayılan | Tarih davranışı |
|---|---|---|
| Recent runs / Son çalıştırmalar | Son 24 saatte oluşturulan çalışmalar; en yeni üstte | Açık `Son 24 saat` filtresi. |
| Active runs / Aktif çalıştırmalar | Terminal olmayan bütün çalışmalar | Tarih sınırı uygulanmaz; “Tüm aktif çalıştırmalar” yazılır. Eski bir takılı run gizlenmez. |
| Failed runs / Başarısız çalıştırmalar | Son 24 saatin başarısız çalışmaları | Aralık genişletilebilir; tüm zamanlar sanılmaz. |
| Run history / Çalıştırma geçmişi | Son 7 gün | Hazır aralıklar ve özel başlangıç/bitiş; geniş sorguda sunucu sayfalaması. |

Aktif görünümde **Sonucu belirsiz** kayıt terminal/aktif semantiğine göre ayrıca filtrelenebilir; “başarısız” kabul edilip otomatik tekrar başlatılmaz. Durum sınıflaması sunucu enum/projection sözleşmesine dayanır.

### 15.2 Tablo ve filtreler

Tablo kolonları: **Durum, nesne adı/türü, ortam, başlangıç, süre, satır/byte özeti, başlatan/tetikleyici, deneme numarası, işlemler**. Run kimliği normalde kısa ikincil tanımlayıcıdır; UUID teknik ayrıntıda kalır. Bilinmeyen satır sayısı `0` değil `—` gösterilir. Satır sayıları farklı adımlarda tekrar sayılıyorsa “toplam veri” diye körlemesine toplanmaz; metrik kapsamı belirtilir.

Filtreler: nesne araması, durum çoklu seçimi, ortam, nesne türü ve tarih aralığı. İkincil filtreler başlatan ve tetikleyici türüdür. Kapsam özetinde aktif filtre chip’leri ve `Filtreleri temizle` bulunur. Hazır tarih/durum seçimi otomatik sorgular; özel tarih aralığı iki değer geçerliyken **Uygula** ile tek sorgu oluşturur. Formun her karakterinde sunucu taraması yapılmaz.

Tarih/saat kullanıcının seçtiği saat diliminde gösterilir; aralıkta saat dilimi açıkça yazılır. Türkiye kullanıcısı için `Europe/Istanbul` tercih edilebilir; bütün kurulumlara zorunlu sabit UTC+3 gömülmez. API’ye gönderilen aralık ve gösterilen yerel saat arasında dönüşüm test edilir. Bitiş sınırının dahil/haricî anlamı tek sözleşmede sabittir; UI’nın son saniyeyi tahmin etmesi gerekmez.

### 15.3 Canlı yenileme

Görünür ve aktif çalışma içeren sayfada **10 saniyelik kontrollü yenileme** ürün varsayılanıdır. Kullanıcı durdurabilir; son başarılı yenileme zamanı görünür. Sekme arka plana gidince sorgu durur; tekrar görünür olduğunda yenilenir. Hata halinde son bilinen kayıtlar korunur ve “Veriler güncel olmayabilir” bilgisi çıkar.

Canlı yenileme seçili satırı, scroll konumunu veya açık log bölümünü sıfırlamaz. Kullanıcı geçmiş sayfasında eski bir sayfayı incelerken yeni satırlar onu üst sayfaya atmaz; `Yeni çalışmalar var` bağlantısı gösterilir. Bu AKIŞ için seçilen davranıştır; rakiplerde aynı otomatik yenilemenin bulunduğu iddiası değildir.

### 15.4 Çalıştırma detayı

Run detayı büyük sağ drawer değil ayrı sayfadır. Üstte **nesne adı + run/deneme + ortam + durum + süre** özeti; altında sol **yürütme ağacı**, sağ **seçili adım bilgileri ve loglar** bulunur. Geçmişe dön bağlantısı mevcut filtreyi ve sayfayı korur.

```text
Daily billing — Run #248 / Attempt 1
├── Prepare staging [Procedure]
│   ├── Truncate target
│   └── Collect statistics
├── Customer load [Data flow]
│   ├── Read source
│   └── Write target
└── Final checks [Package]
    └── Check totals [Procedure]
        └── Compare counts
```

Bu ağaç UI’nın olay JSON’undan veya adların benzerliğinden türetilmez. Sunucudan sabit kimlik, parent ID, tür ve sıralama içeren **typed yürütme projeksiyonu** gerekir. Güncel `RunDetailPage` düz adım listesi render etmektedir; gerçek iç içe hiyerarşi bu kaynakta doğrulanmamıştır. [K15]

Başarısız run açıldığında ilk başarısız adımın ebeveynleri açılır ve o adım seçilir. Çalışan run’da kullanıcı henüz seçim yapmadıysa aktif adım önerilir; kullanıcı seçim yaptıktan sonra otomatik seçim onun incelemesini bozmaz. Ağacın araması ad/kod/hata koduna göre filtreler; “yalnız sorunlu adımlar” seçeneği bağlam için gerekli ebeveynleri korur.

### 15.5 Seçili adım detayı ve loglar

| Alan | Kesin sunum |
|---|---|
| Kimlik | Adım adı, nesne türü, sıra ve parent yolu; UUID kapalı teknik alan. |
| Durum | Çevrilmiş metin + ikon + küçük badge; durum geçiş zamanı. |
| Çalışma bağlamı | Bu run’da gerçekten pinlenmiş ortam, bağlantı takma adı, fiziksel şema ve revizyon. Güncel tasarım eşlemesiyle değiştirilmez. |
| Süre | Başlama/bitiş, toplam süre; devam ediyorsa geçen süre. |
| Veri miktarı | Okunan/yazılan satır ve byte, hangi faza ait oldukları; yoksa `—`. |
| Hata | Hata kodu, güvenli hata mesajı, adım ve zaman; destek için kopyalama. |
| Günlükler | Varsayılan seçili adım ve seçili deneme; seviye, arama ve zaman filtreleri. |
| Teknik ayrıntı | İzinli kimlikler, plan/komut hash’i, ilgili güvenli teknik alanlar. |

Sağ panel sekmeleri **Summary / Özet**, **Logs / Loglar**, **Events / Olaylar** olacaktır. Başarısız adım açılışında hata özeti Özet’in tepesinde görünür. Olaylar zaman sıralı bir listedir; ağaç rolü kullanılmaz. JSON payload yalnız yetkili teknik ayrıntıda kalır. Mevcut redaction yaklaşımı korunup düz hata mesajı ve kopyalama yoluna da uygulanır. [K15]

Log akışında `Sona git` ve `Canlı takip` açık eylemlerdir. Kullanıcı yukarı kaydırmışken zorla alta atılmaz. Uzun loglar cursor/segment ile yüklenir; browser’a bütün geçmiş dökülmez. Worker başlamadan hata olmuşsa “Bu adım başlamadığı için adım logu yok; çalışma isteği olaylarını inceleyin” gibi açıklama gösterilir. Bu ayrımın önemi Prefect log kapsamı dokümanında da görülmektedir. [D19]

### 15.6 Yeniden çalıştırma, devam ve iptal

| Eylem | Kesin anlam | Onay içeriği |
|---|---|---|
| Yeniden çalıştır | Aynı pinlenmiş çalıştırılabilir sürüm/ortam ile yeni deneme veya yeni run; sunucu semantiği açık | Yeniden yazılabilecek adımlar, hedef, veri tekrarı riski, sürüm. |
| Başarısız adımdan devam | Sunucunun güvenli checkpoint/resume sözleşmesiyle kaldığı yerden devam | Devam edilecek adım, önceki tamamlanan işlerin durumu, yeniden yürütülecek bölüm. |
| Yeni sürümle çalıştır | Ayrı eylem; eski run’ın aynı denemesi değildir | Yeni sürüm ve yeni hedef eşleştirmeleri. |
| İptal iste | Çalışan iş için iptal talebi | “İptal istendi” ile “iptal edildi” ayrı; anında rollback garantisi verilmez. |

**Başarısız olmak, güvenle tekrar çalıştırılabilir olmak değildir.** Bu eylemler yalnız sunucunun `allowedActions` ve yetenek bilgisiyle etkinleşir. Mevcut ekranda cancel desteklenirken retry/resume desteği sınırlı/kapalı ifade edilmektedir. UI başarısız run görünce kendi başına “devam” düğmesi üretmeyecektir. [K15]

Buton kapalıysa gerekçe görünürdür: “Bu çalışma için güvenli devam noktası bulunmuyor.”, “Sonuç belirsiz; hedefteki işlem doğrulanmadan tekrar çalıştırılamaz.” veya “Yetkiniz yok.” Birinci ve ikinci durumları genel “işlem başarısız” mesajına indirgemez.

### 15.7 Operasyon rolü için sade görünüm

Operasyon rolünde varsayılan yüzey **Aktif/Başarısız/Geçmiş**, okunabilir nesne adları, ortam ve güvenli müdahale eylemleridir. Kod editorü, teknik eşleştirme düzenleme, connection credential alanları ve raw JSON ilk görünümde bulunmaz. Gerektiğinde geliştiriciye gönderilebilecek güvenli destek özeti üretir; SQL içindeki hassas sabitler ve credential bilgileri özete dahil edilmez.

Operatörün görmediği bir yetkinin var olduğu varsayılmaz. Menünün rol bazında sadeleşmesi server-side authorization’ın yerine geçmez. Veri yenileme ve hatadan geri dönme iş akışları geliştiricideki kadar eksiksiz kalır.

## 16. Proje Seçimi ve Genel Bakış

### 16.1 Proje kapısı

| Durum | Kesin davranış |
|---|---|
| Erişilebilir proje yok | Seçim kapısı; yetki varsa `Proje oluştur`, ikincil `İçe aktar`; yoksa erişim talebi açıklaması. |
| Tek erişilebilir proje | Otomatik seçilir ve proje girişine gidilir; mevcut davranış korunur. [K17] |
| İlk giriş, birden çok proje | Proje seçimi zorunlu; rastgele ilk proje açılmaz. |
| Sonraki giriş, kayıtlı erişilebilir proje | Son açıkça seçilmiş proje açılabilir; üst bardan değiştirilebilir. |
| Son proje silinmiş/yetki kaybedilmiş | Eski veriler temizlenir; seçim kapısı ve açıklama. |
| Geçerli deep link | Proje yetkisi doğrulanır ve istenen nesne açılır; gereksiz seçim ekranı araya girmez. |

Son proje tercihi kullanıcı ve hesap/tenant kapsamına bağlı saklanır. Farklı kullanıcının tarayıcı oturumunda eski seçim nedeniyle yanlış bağlam yüklenmez. Proje seçici ad ve kodla arama, klavye gezinimi ve son kullanılanları gösterir; projeye ait hassas iş verisi seçim kapısında sergilenmez.

### 16.2 Karşılama ekranı

Genel bakış mevcut sadeliğini korur: proje adı, kodu, kısa açıklama ve role uygun **Geliştirmeye git** veya **Operasyonlara git** eylemi. İkinci düzeyde diğer çalışma alanlarına metin bağlantıları olabilir. Büyük KPI kartları, canlı grafikler, son çalışmalar tablosu, JSON dışa aktar düğmesi ve sürekli tehlike banner’ı eklenmez.

Proje gerçekten erişilemez veya kritik durumda ise yerel hata/uyarı gösterilir; “sade” olması hatayı gizlemek anlamına gelmez. Normal boş karşılama ile proje yüklenememesi aynı görünüm değildir. [K18]

## 17. Ortak Sayfa Şablonu

### 17.1 İlk 3–5 saniyede yön bulma

OSB Dashboard’un gerçek HTML/CSS/JavaScript dosyası erişilebilir olmadığından, onun bunu nasıl başardığına dair kaynak bulgusu üretilemedi. Aşağıdaki düzen **AKIŞ için seçilen tasarım ve doğrulama modeli**dir; OSB’de görülmüş gibi sunulmaz.

| Kullanıcı sorusu | Ekrandaki kesin cevap | Örnek |
|---|---|---|
| Hangi sistem? | Sürekli AKIŞ markası | `AKIŞ` |
| Hangi proje? | Üst bar proje adı | `Finance DWH ▾` |
| Hangi nesne/fonksiyon? | Breadcrumb ve H1 | `Bağlantılar / Oracle billing` |
| Bu ekran ne yapıyor? | Başlık altında tek cümlelik amaç veya nesne açıklaması | “Fiziksel şemaları ve bağlantı revizyonlarını yönetin.” |
| Birincil işlem? | Başlık sağında tek vurgulu eylem | `Yeni bağlantı`, `Kaydet` veya `Çalıştırma başlat` |
| Sonraki adım? | Duruma bağlı kısa yönlendirme | “Bağlantı hazır. İlk fiziksel şemayı tanımlayın.” |
| Tehlikeli işlem? | Açık risk etiketi ve ayrı danger eylem | `Üretim`, `Yıkıcı işlem`, `Bağlantıyı sil` |

Bütün nesne isimleri breadcrumb içine doldurulmaz. Dört düzeyi aşan yollarda ortadaki klasörler kısaltılır, tam yol erişilebilir menü/tooltip ile açılır. Proje ve son nesne her zaman görünür kalır. Başlık yalnız `Details` veya `Edit` gibi bağlamsız kelime olmaz.

### 17.2 Üç ortak sayfa tipi

**Liste sayfası:** breadcrumb → H1/amaç/eylem → filtreler → kapsam özeti → tek ana tablo → sayfalama. **Detay sayfası:** breadcrumb → kimlik/durum/eylem → kısa özet → URL’li alt sayfalar → seçili içerik. **Editor sayfası:** breadcrumb → nesne/sürüm/dirty/eylem → editor toolbar → göreve özgü çalışma yüzeyi → doğrulama/özellik alanı.

Her sayfayı aynı kart dizisine çevirmek yoktur. Ortaklık; başlık, bağlam, eylem hiyerarşisi, form/tablolar ve durum davranışıdır. İç düzen görevin ihtiyacına göre değişir: prosedür sıralı adım, mapping kolon ilişkisi, paket kontrol akışı.

### 17.3 Eylem önceliği sözleşmesi

| Durum | Birincil eylem | İkincil eylemler |
|---|---|---|
| Boş katalog | İlk kaydı oluştur | İçe aktar veya yardım, gerekiyorsa. |
| Dolu katalog | Yeni kayıt | Yenile, filtreler. |
| Düzenlenen taslak | Kaydet | Doğrula, uygun sürümü çalıştır, vazgeç/geri al. |
| Bağlantı detayı | Bağlantıyı test et | Şemaları aç, revizyon/kullanım, menü. |
| Aktif run detayı | İzin verilen müdahaleye göre; zorunlu dolu buton yok | Yenile/canlı takip; iptal danger ikincil eylem. |
| Başarısız run detayı | Güvenli devam destekleniyorsa devam; değilse inceleme | Yeni deneme, destek özeti, teknik ayrıntı. |

Her sayfada mutlaka renkli buton bulunması gerekmez. Salt okunur bir ekranın olmayan işlemi uydurulmaz. “Birincil” kavramı iş önceliğidir; tehlikeli işlemi sırf boşluk dolsun diye birincil yapmak değildir.

## 18. Tasarım Sistemi

### 18.1 Genel ölçüler ve grid

| Token / öğe | Kesin karar |
|---|---|
| Üst bar | 56 px; dar dokunmatik görünümde de en az 56 px. |
| Sol navigasyon | Geniş 280 px; dar 64 px; kullanıcı tarafından aç/kapa. |
| Sayfa yatay boşluğu | ≥1280 px: 24 px; 768–1279 px: 20 px; <768 px: 16 px. |
| Liste/detay içeriği | Kullanılabilir genişliği kullanır; okunabilir özet/metin blokları en fazla 1200 px; tablolar gerekirse daha geniş. |
| Editor | Sabit 1200 px max-width yok; canvas ve grid kullanılabilir alanı doldurur. |
| Genel form | 960 px dış kap; uzun metin/tek sütun alanları çoğunlukla 720 px. |
| Grid | 12 kolon mantıksal yerleşim; 24 px gutter. Formlarda varsayılan 2 eşit kolon, dar görünümde 1. |
| Boşluk ölçeği | 4, 8, 12, 16, 24, 32, 48 px; rastgele 13/19/27 px boşluklar yok. |
| Radius | Alan/buton 8 px; kart/panel 12 px; modal 16 px; badge tam yuvarlak. |
| Panel ayrımı | 1 px border; gerekirse `0 1px 2px rgba(15,23,42,.05)` kadar hafif gölge. |
| Z-order | Normal 0; sabit header 20; dropdown 40; modal backdrop 80/modal 90; toast 100. |

### 18.2 Tipografi

Font ailesi mevcut sistem fontu yaklaşımıyla **Aptos → Segoe UI → system-ui → sans-serif** olacaktır. Font dosyası yüklemeden kurumsal Windows/Mac ortamına uyum korunur. SQL/teknik değerlerde sistem monospace kullanılır; bütün ekran monospace yapılmaz. Mevcut Aptos/Segoe UI temeli kaynakta vardır. [K04]

| Rol | Boyut / satır yüksekliği / ağırlık |
|---|---|
| Sayfa başlığı H1 | 24 / 32 px / 600 |
| Bölüm başlığı H2 | 18 / 26 px / 600 |
| Panel başlığı H3 | 16 / 24 px / 600 |
| Gövde ve tablo | 14 / 20 px / 400 |
| Alan etiketi | 13 / 20 px / 500 |
| Yardımcı bilgi / badge | 12 / 18 px / 400–600 |
| SQL editor | 14 / 22 px / 400, kullanıcının büyütebilmesi gerekir |
| Büyük özel giriş başlığı | En fazla 28 / 36 px; normal çalışma sayfalarında kullanılmaz |

Anlamlı tablo verisi ve navigasyon metni 12 px altına düşmeyecektir. Küçük eyebrow, büyük tracking ve sürekli uppercase; mevcut ağacın yoğun teknik etiketlerinde azaltılacaktır. Satır sayıları ve sürelerde tabular numerals kullanılacaktır.

### 18.3 Renk rolleri

**KARAR:** Nötr/slate temel + sınırlı teal etkileşim rengi. Oracle veya başka sağlayıcının rengi tüm kartı boyamaz; yalnız küçük ikon/monogram alanı ve metinle birlikte kullanılır. Yoğun mavi, yaygın amber, gradient ve glow yoktur.

| Rol | Light | Dark | Kullanım |
|---|---|---|---|
| Sayfa zemini | `#F8FAFC` | `#0B1220` | Büyük arka plan. |
| Ana yüzey | `#FFFFFF` | `#111827` | Panel, tablo, form. |
| Yükseltilmiş yüzey | `#F1F5F9` | `#1E293B` | Toolbar, hover, ikincil alan. |
| Ana metin | `#0F172A` | `#F8FAFC` | Başlık/veri. |
| İkincil metin | `#475569` | `#CBD5E1` | Açıklama. |
| Yardımcı metin | `#64748B` | `#94A3B8` | Tarih, bağlam; küçük metin kontrastı ayrıca test edilir. |
| Ayırıcı border | `#E2E8F0` | `#334155` | Dekoratif panel/tablo ayrımı. |
| Gerekli kontrol sınırı | `#64748B` | `#64748B` | Alan sınırı başka işaretle anlaşılmıyorsa kontrastlı çizgi. |
| Birincil işlem | `#0F766E` | `#5EEAD4` | Light’ta beyaz, dark’ta `#0F172A` yazı. |
| Birincil hover | `#115E59` | `#99F6E4` | Renk değişir; buton zıplamaz. |
| Seçili yüzey | `#F0FDFA` | `#134E4A` | Seçim küçük alanlarda; ayrıca kenar/metin. |
| Focus ring | `#0F766E` | `#5EEAD4` | 2 px dış çizgi + 2 px boşluk; glow değil. |

### 18.4 Durum renkleri

| Anlam | Light metin / açık zemin | Dark metin / zemin | Ek ayırt edici |
|---|---|---|---|
| Başarılı | `#166534` / `#F0FDF4` | `#86EFAC` / `#052E16` | Check + `Başarılı`. |
| Devam ediyor | `#0F766E` / `#F0FDFA` | `#5EEAD4` / `#134E4A` | Küçük hareket/durum ikonu + metin. |
| Uyarı / bekleyen onay | `#92400E` / `#FFFBEB` | `#FCD34D` / `#451A03` | Uyarı/clock + gerekçe. |
| Başarısız / tehlikeli | `#B91C1C` / `#FEF2F2` | `#FCA5A5` / `#450A0A` | Hata/stop ikonu + metin. |
| Pasif / iptal / atlandı | `#475569` / `#F1F5F9` | `#CBD5E1` / `#1E293B` | Her durum kendi adı ve ikonu; tek gri “bitti” değil. |
| Bilinmiyor | Nötr | Nötr | Soru işareti + `Bilinmiyor`; başarı rengi yok. |

Bunlar önerilen token değerleridir. Son UI’da kontrast; gerçek foreground/background, opacity, hover/disabled ve seçili durum kombinasyonlarında ölçülür. Bu tablo tek başına WCAG uygunluk sertifikası değildir. [D24]

### 18.5 Kart ve tablo standardı

Kart yalnız bir nesnenin özeti veya farklı görev blokları için kullanılır. Kart içinde aynı bilgileri tekrar eden metrik kutuları yoktur. Kart başlığı 16 px, içerik padding 16–24 px; kartlar arası 16 px. Durum rengi bütün yüzeyi boyamaz.

Tablo header 40 px, satır 44 px varsayılan; yoğun veri modunda 36 px satır açık kullanıcı tercihiyle seçilebilir. Dokunmatik hedef 44 px kalır. Header sabitlenebilir, border hafif olur. Zebra zorunlu değildir; hover ve seçili satır farklıdır. Ad/metin sola, sayı sağa hizalanır. Satırın tamamını tek dev button yapmak yerine nesne adı gerçek link, satır seçimi ayrı davranıştır.

Tablo araçları: arama/filtre, sonuç kapsamı, gerekirse kolon görünürlüğü ve sayfalama. CSV/JSON export araç çubuğunun devamlı birincil eylemi olmaz; yetkili ikincil menüde kalır. Boş tablo 10 boş satır ve `0` değerlerle taklit edilmez.

### 18.6 Modal ve form standardı

| Yüzey | Genişlik / davranış |
|---|---|
| Basit onay | 480 px; açık nesne adı; tehlikeli etki varsa ilk odak güvenli seçenek. |
| Küçük nesne formu | 640 px; mantıksal/fiziksel şema, klasör, değişkenin kısa ayarları. |
| Bağımlılık/etki onayı | 720 px; sayfalı/gruplu bağımlılık listesi. |
| Karşılaştırma dialog’u | En fazla 960 px; tam sayfaya açma eylemi; uzun editor burada yaşamaz. |
| Uzun bağlantı formu | Modal değil ayrı sayfa. |
| Paket/prosedür/mapping | Ayrı tam çalışma alanı; sağ drawer yok. |

Alan yüksekliği 40 px, dokunmatikte 44 px; label üstte, yardım ve hata altta. Placeholder label yerine geçmez. Gereklilik metinle/işaretle açıklanır. Koşullu alanlar hem görünüm hem payload’dan kaldırılır. Hatalı submit’te özet ilk hataya link verir ve odak ilk geçersiz alana taşınır; girilen veri korunur.

Modal başlığı ve eylem satırı sabit, gövde kaydırılabilir; sayfa arkasındaki içerik etkileşimsizdir. Escape önce açılmış dropdown’u kapatır, sonra dialog’u; kaydedilmemiş veri varsa koruma kuralı işler. Kapatınca odak açan elemana döner. Mevcut ortak core dialog’un geliştirilmesi tercih edilir; GPU’nun geçmiş dialog kodu birebir taşınmaz. [K16]

### 18.7 İkonlar, butonlar ve metin standardı

İkon ailesi mevcut **Lucide** kullanımıyla birleştirilecektir. Normal ikon 18 px, tablo/yardımcı 16 px, önemli boş durum en fazla 32 px; stroke tutarlı. Sağlayıcı logoları yalnız tanıma amacı taşır. İkon tek başına anlamın tamamını üstlenmez.

Birincil buton dolu teal; ikincil border/nötr; üçüncül metin/quiet; tehlikeli buton gerçek tehlikeli onayda kırmızı. Silme gibi bir eylem menüde kırmızı metin + ikonla ayrılır. Bir formdaki Test et ikincil, Kaydet birincildir. Disable’ın nedeni yakında gösterilir; her disabled buton `cursor:wait` olmaz. Loading yalnız devam eden istekte spinner + eylem metni kullanır.

Her iki dilde **sentence case / cümle düzeni** seçilmiştir: `New connection` / `Yeni bağlantı`, `Run history` / `Çalıştırma geçmişi`. `Yeni Bağlantı Oluştur` gibi her kelimenin baş harfini büyüten ayrı Türkçe stil kullanılmaz. SQL, JDBC, JNDI, SID, PL/SQL, JSON ve marka/adlar korunur. CSS `text-transform` ile Türkçe metinler dönüştürülmez; doğru metin sözlükte tutulur.

### 18.8 Hover, focus, selected, motion ve tema

Hover bir önizleme işaretidir, kayıtlı seçim değildir. Selected durum metin ağırlığı + küçük kenar/işaret + hafif zeminle gösterilir. Focus ring ayrı kalır; mouse ile seçilen satırda keyboard focus kaybolmaz. Renk transition en fazla 120–160 ms; butonların yukarı zıplaması ve ekran açılışındaki dekoratif hareketler kaldırılır.

Yeni kullanıcı **light** tema ile başlar. Kaydedilmiş light/dark/system tercihi korunur; system tercihi işletim sistemi değişimini izler. Dark tema, light tokenlarını opacity ile karartmak değildir; yukarıdaki ayrı tokenlar kullanılır. İlk render’da yanlış temanın kısa süre parlaması önlenir. `prefers-reduced-motion` spinner dışındaki gereksiz hareketleri kapatır; yükleme bilgisi metinle de taşınır.

## 19. İngilizce/Türkçe Terminoloji Tablosu

Backend enumları ve teknik kimlikler değiştirilmez; aşağıdaki terimler görünür metin sözleşmesidir. Kullanıcının verdiği nesne adları çevrilmez. Durum satırlarındaki `SUCCEEDED`, `FAILED`, `QUEUED` gibi İngilizce tanımlayıcılar kavramsal karşılıklardır; mevcut API’nin bütün kanonik enum adlarının aynen bu şekilde olduğu iddia edilmez. Uygulama, sunucunun gerçek durum kodlarını bu görünür etiketlere açık bir eşleştirme tablosuyla çevirir; yeni enum icat etmez.

| Kavram / teknik karşılık | İngilizce UI | Türkçe UI | Karar / açıklama |
|---|---|---|---|
| PROJECT | Project | Proje | Üst bağlam. |
| Workspace: development | Development | Geliştirme | Nesne tasarımı alanı. |
| Workspace: operations | Operations | Operasyonlar | Çalıştırma izleme/müdahale alanı. |
| Genel koleksiyon | Flow / Flows | Akış / Akışlar | Mapping ile paket için ortak üst aile, bağımsız yeni type değil. |
| MAPPING | Data flow | Veri akışı | Ana nesne adı. |
| Mapping teknik kavramı | Mapping | Eşleştirme (mapping) | Yardım/teknik açıklamada; nesne listesinde ikinci ad değil. |
| Kolon ilişkisi | Column mapping | Kolon eşleştirmesi | Schema binding ile karıştırılmaz. |
| PROCEDURE | Procedure | Prosedür | SQL/işlem adımları. |
| PACKAGE | Package | Paket | Kontrol akışı nesnesi. |
| Workflow genel kavramı | Workflow | İş akışı | Paket açıklamasında kullanılır; ikinci paralel nesne türü yaratılmaz. |
| VARIABLE | Variable | Değişken | Ortak bileşen. |
| SEQUENCE | Sequence generator | Sıra üreteci | “Sequence” tek başına bırakılmaz. |
| USER_FUNCTION | User function | Kullanıcı fonksiyonu | Ortak bileşen. |
| KNOWLEDGE_MODULE | Execution module | Yürütme modülü | Teknik backend türü korunur; kullanıcı terimi sabit. |
| MODEL | Model | Model | Metadata organizasyonu. |
| Submodel | Submodel | Alt model | Model altındaki grup. |
| Data object | Data object | Veri nesnesi | Tablo/görünüm gibi metadata nesnesi. |
| CONNECTION | Connection | Bağlantı | Menüde Connections / Bağlantılar. |
| Provider | Provider | Sağlayıcı | Oracle vb.; yalnız ikon değil metin. |
| PHYSICAL_SCHEMA | Physical schema | Fiziksel şema | Bağlantı altındaki fiziksel referans. |
| LOGICAL_SCHEMA | Logical schema | Mantıksal şema | Proje düzeyinde soyut referans. |
| SCHEMA_BINDING | Schema binding | Şema eşleştirmesi | Mantıksal + ortam → fiziksel + revizyon. |
| ENVIRONMENT | Environment | Ortam | UI’da Context kullanılmaz. |
| Eski/ODI terimi | Context | Bağlam (ODI context) | Yalnız karşılaştırma veya teknik yardım. |
| RUN isim | Run | Çalıştırma | Tek yürütme kaydı. |
| RUN fiil | Run | Çalıştır | Buton. |
| Run history | Run history | Çalıştırma geçmişi | Nesne veya proje kapsamında. |
| Attempt | Attempt | Deneme | Run/yeniden yürütme sözleşmesiyle uyumlu. |
| SCENARIO | Scenario | Senaryo | Derlenmiş/pinlenmiş yürütme temsili; operasyon kaydı değildir. |
| LOAD_PLAN | Execution plan | Çalıştırma planı | Birden fazla yürütmeyi düzenleyen ayrı nesne. |
| Draft | Draft | Taslak | Henüz yayımlanmış çalışma kaydı değil. |
| Definition version | Version | Sürüm | Nesnenin kayıtlı tasarım sürümü. |
| Connection version | Revision | Revizyon | Bağlantının değişmez ayar sürümü; `r3`. |
| Publication kullanıcı görünümü | Runnable version | Çalıştırılabilir sürüm | Backend yayın kaydı korunur. |
| Make runnable | Make runnable | Çalıştırılabilir sürüm hazırla | Otomatik çalıştırma değildir. |
| Overview: project | Overview | Genel bakış | Yalnız proje giriş sayfası. |
| Overview: object | Overview | Genel bilgiler | Nesne detayının metadata sekmesi. |
| Diagram | Diagram | Diyagram | Paket çalışma görünümü. |
| Properties | Properties | Özellikler | Seçili adım/bağlantı. |
| SOURCE / TARGET | Source / Target | Kaynak / Hedef | Enum ham gösterilmez. |
| READ_ONLY | Read only | Salt okunur | Risk/politika açıklaması. |
| STOP / CONTINUE | Stop on error / Continue on error | Hatada dur / Hatada devam et | Koşullu iş dalıyla karıştırılmaz. |
| TRUE / FALSE | True / False | Doğru / Yanlış | False = hata değildir. |
| SUCCEEDED | Succeeded | Başarılı | İkon + metin. |
| FAILED | Failed | Başarısız | Hata kodu/mesajı ayrı. |
| RUNNING | Running | Çalışıyor | Yürütme gerçekten başlamış. |
| QUEUED | Queued | Sırada | Running diye gösterilmez. |
| CANCEL_REQUESTED | Cancellation requested | İptal istendi | Henüz sonuçlanmadı. |
| CANCELLED | Cancelled | İptal edildi | Terminal durum. |
| SKIPPED | Skipped | Atlandı | Başarısız değil. |
| UNKNOWN | Unknown | Bilinmiyor | Belirsizliği gizlemez. |
| Export | Export | Dışa aktar | İkincil menü. |
| Import | Import | İçe aktar | Uygun bağlamda. |
| Project import | Import project | Proje içe aktar | Proje seçimi/menüsü; workflow paketiyle karıştırılmaz. |
| Bundle dosya biçimi | Project bundle | Proje aktarım paketi | Yalnız dosya biçimi/yardım metninde. |
| Re-run | Run again | Yeniden çalıştır | Yeni deneme; yan etki açıklamasıyla. |
| Resume | Resume from failed step | Başarısız adımdan devam et | Güvenli checkpoint gerekir. |
| Test connection | Test connection | Bağlantıyı test et | Revizyon/test kapsamı açık. |
| View raw definition | View raw definition | Ham tanımı görüntüle | Gelişmiş, salt okunur. |

Çoğul, sayı, tarih ve cümleler parça birleştirilerek çevrilmez. Örneğin `1 physical schema` / `4 physical schemas` ile `1 fiziksel şema` / `4 fiziksel şema` tam cümle kurallarıyla üretilir. TR/EN aramalarında locale-aware case handling kullanılır; teknik kod doğrulaması bundan ayrı tutulur.

## 20. Ekran Bazlı Wireframe’ler

**Gösterim anahtarı:** `[T]` sayfa başlığı; `[B]` bağlam/breadcrumb; `[P]` birincil işlem; `[I]` ikincil işlemler; `[F]` filtre/arama; `[A]` ana içerik; `[D]` seçili detay; `[X]` tehlikeli işlem/uyarı. Bütün adlar, zamanlar ve sayılar temsili örnektir; canlı sistemden alınmış ölçüm değildir. Wireframe’ler yerleşim ve davranış sözleşmesidir, bitmiş görsel tasarım veya ekran görüntüsü değildir.

### 20.1 Ana uygulama kabuğu

```text
+--------------------------------------------------------------------------------------+
| AKIŞ        [B] Finance DWH v   [Proje girişine git]             Türkçe | Tema | Profil |
+------------------------+-------------------------------------------------------------+
| Geliştirme  [seçili]    | [B] Finance DWH / Billing / Prosedürler                     |
| Operasyonlar           | [T] Prepare staging       Prosedür · Taslak · Değişiklik var |
| Bağlantılar            |                                         [P] Kaydet          |
|------------------------| [I] Doğrula   Çalıştır   ...                                 |
| [F] Nesne ara          |-------------------------------------------------------------|
| Finance DWH      Yeni v| [A] Göreve özgü çalışma alanı                               |
| v Akışlar              |                                                             |
|   v Billing            | Liste / mapping / prosedür / paket                           |
|     Prosedürler        |                                                             |
| > Ortak bileşenler     |-------------------------------------------------------------|
| > Modeller             | [D] Seçili nesne veya doğrulama bilgisi                     |
|                        | [X] Silme yalnız nesne menüsünde; üretim riski bağlamda      |
+------------------------+-------------------------------------------------------------+
```

Üç çalışma alanı hiçbir genişlikte tamamen kaybolmaz. Seçili workspace ile seçili nesne farklı görsel durumlar taşır. Breadcrumb sayfa değişiminde H1 ile birlikte güncellenir; eski projenin içeriği yeni proje başlığı altında kalmaz.

### 20.2 Proje seçimi

```text
+--------------------------------------------------------------------------------------+
| AKIŞ                                                            Dil | Tema | Profil |
+--------------------------------------------------------------------------------------+
| [B] Çalışma alanına giriş                                                            |
| [T] Bir proje seçin                                                                 |
|     Çalışmak istediğiniz projeyi açın.                                              |
|                                                                                     |
| [F] Proje adı veya kodu ara __________________________________                       |
|                                                                                     |
| [A] ( ) Finance DWH          FINANCE_DWH           Son kullanılan                    |
|     ( ) Billing integration BILLING              Yetkili proje                      |
|     ( ) Data quality        DATA_QUALITY                                             |
|                                                                                     |
| [D] Seçili proje: Finance DWH · Finans veri entegrasyonu                              |
|                                            [P] Projeyi aç                            |
| [I] Proje oluştur (yetki varsa)     ... > İçe aktar                                  |
| [X] Bu görünümde silme veya çalıştırma işlemi yoktur.                                 |
+--------------------------------------------------------------------------------------+
```

Tek projede bu seçim görünümü bekletilmez. Çoklu projede arama ve seçim klavyeyle yapılır. Proje yüklenemediğinde eski liste yerine hata + yeniden deneme gösterilir.

### 20.3 Bağlantılar ana ekranı

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Bağlantılar                                                       |
| [T] Bağlantılar                                             [P] Yeni bağlantı       |
|     Veri kaynaklarını, şemaları ve bağlantı revizyonlarını yönetin.                  |
| [I] Yenile                                                    ...                   |
|--------------------------------------------------------------------------------------|
| Katalog [seçili] | Mantıksal şemalar | Ortamlar                                      |
| [F] Ara...  Sağlayıcı v  Test durumu v  Kullanıldığı ortam v  Filtreleri temizle      |
|--------------------------------------------------------------------------------------|
| [A] Ad           Sağlayıcı   Uç nokta         Revizyon  Şemalar      Test    İşlem   |
| Oracle billing   Oracle      db... / BILLING  r3 Etkin  4 fiz/3 man  Başarılı Test ...|
| Oracle stage     Oracle      db... / STAGE    r2 Etkin  2 fiz/1 man  Yok      Test ...|
|                                                                                     |
| 1–50 / 180 bağlantı                                Sayfa boyutu 50 v   < 1 2 3 >     |
|--------------------------------------------------------------------------------------|
| [D] Ada basmak ayrı bağlantı detayını açar; katalogda büyük edit formu yoktur.        |
| [X] ... > Bağlantıyı sil > bağımlılık kontrolü ve açık onay                           |
+--------------------------------------------------------------------------------------+
```

Kullanıcı tablo görünümünü kart görünümüne değiştirmek zorunda bırakılmaz. Aynı kolonlar çok sayıda kayıtta aynı karşılaştırma eksenini korur. Test butonu yalnız kendi satırını meşgul eder.

### 20.4 Bağlantı oluşturma

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Bağlantılar / Yeni                                                 |
| [T] Yeni bağlantı                                                                   |
|     Bağlantı bilgilerini girin, test edin ve kaydedin.                                |
|--------------------------------------------------------------------------------------|
| [A] Kimlik                                                                          |
|     Sağlayıcı [Oracle v]           Ad [Oracle billing________________]               |
|     Kod [ORACLE_BILLING____]        Açıklama [________________________]               |
|                                                                                     |
|     Bağlantı yöntemi (o) JDBC  ( ) JNDI                                              |
|     Host [db.example.internal___]  Port [1521]                                      |
|     Tanımlayıcı [Service name v]   Değer [BILLING__________________]                  |
|     Kullanıcı [AKIS_READER_____]    Parola [.........................] [Göster]       |
|     > Gelişmiş bağlantı ayarları                                                    |
|--------------------------------------------------------------------------------------|
| [D] Test sonucu: Henüz test edilmedi. Test edilen bilgiler burada gösterilecek.       |
| [F] Bu formda katalog filtresi yok; sağlayıcı alanı form seçicisidir.                 |
| [X] Test gerçek bağlantı kurar; form veri aktarımı veya şema oluşturma yapmaz.        |
|--------------------------------------------------------------------------------------|
| [I] Vazgeç                 [I] Bağlantıyı test et           [P] Bağlantıyı kaydet     |
|                                 Kaydetmeden önce bu bağlantıyı test edin.             |
+--------------------------------------------------------------------------------------+
```

JNDI seçiminde host/kullanıcı/parola yerine JNDI adı gösterilir. Başarılı testten sonra endpoint değişirse sonuç geçersiz olur; açıklama değişirse olmaz. Kaydet sunucu onayı gelmeden başarılı sayılmaz.

### 20.5 Bağlantı detayı

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Bağlantılar / Oracle billing                                      |
| [T] Oracle billing                           [P] Bağlantıyı test et    [I] ...       |
|     Oracle · ORACLE_BILLING                                                          |
|--------------------------------------------------------------------------------------|
| [D] Etkin revizyon  r3                Kullanım        Çalıştırılabilir               |
|     Uç nokta        db... / BILLING   Kullanıcı       AKIS_READER (yetkiye göre)     |
|     Son test        Başarılı · tarih Şemalar         4 fiziksel · 3 mantıksal        |
|--------------------------------------------------------------------------------------|
| Genel bilgiler | Fiziksel şemalar [seçili] | Revizyonlar | Kullanım                  |
| [F] Şema adı ara...                           [I] Fiziksel şema ekle                 |
| [A] Şema          Fiziksel referans       Mantıksal kullanım         İşlem            |
|     Billing       BILLING_OWNER          2 ortam                  Aç ...            |
|     Staging       BILLING_STG            1 ortam                  Aç ...            |
|--------------------------------------------------------------------------------------|
| [X] ... > Bilgileri düzenle / Yeni revizyon oluştur / --- / Bağlantıyı sil            |
+--------------------------------------------------------------------------------------+
```

Etkin revizyon olmayan bağlantıda kart açıkça bunu söyler; en yeni taslak etkinmiş gibi gösterilmez. Şema ekleme bu alt sayfanın yerel eylemidir; ana başlığın Test işlemiyle aynı vurguya sahip değildir.

### 20.6 Fiziksel şema tanımı

```text
+------------------------------ Modal: 640 px ------------------------------------------+
| [B] Oracle billing / Fiziksel şemalar                                                 |
| [T] Fiziksel şema ekle                                                         Kapat |
|     Var olan veritabanı şemasını AKIŞ'a tanıtın.                                     |
|--------------------------------------------------------------------------------------|
| [D] Bağlantı: Oracle billing · Oracle · r3 (bağlam, salt okunur)                     |
| [A] Görünen ad [Billing staging________________________________]                    |
|     Fiziksel şema / owner [BILLING_STG_________________________]                    |
|     Kod [BILLING_STG______________]                                                  |
|     Bu işlem veritabanında yeni kullanıcı veya şema oluşturmaz.                     |
|                                                                                     |
| [F] Yalnız keşfedilmiş şema seçicisinde arama; katalog filtresi yok.                  |
| [X] Oluşturma formunda silme yok. Var olan şemayı kaldırma ayrı etki kontrolüdür.     |
|--------------------------------------------------------------------------------------|
| [I] Vazgeç                                                 [P] Fiziksel şemayı ekle |
+--------------------------------------------------------------------------------------+
```

Fiziksel şema adı ile görünen ad ayrıdır. Keşif sonucu yetki nedeniyle boşsa manuel alanın desteklenip desteklenmediği açıkça belirtilir; görünmeyen schema varmış gibi doğrulanmaz.

### 20.7 Mantıksal şema ve eşleştirme

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Bağlantılar / Mantıksal şemalar / BILLING_SOURCE                   |
| [T] BILLING_SOURCE                                    [I] Bilgileri düzenle  ...     |
|     Faturalama kaynak verisi                                                         |
| [F] Ortam ara...                                                                    |
|--------------------------------------------------------------------------------------|
| [A] Ortam       Bağlantı          Fiziksel şema      Revizyon    Durum        İşlem   |
|     Development Oracle dev       BILLING_OWNER      r2          Hazır        Düzenle|
|     Test        Oracle test      BILLING_OWNER      r4          Hazır        Düzenle|
|     Production  —                —                  —           Eksik       Eşleştir|
|--------------------------------------------------------------------------------------|
| [D] Production eşleştirmesi — içerik içi panel                                       |
|     Bağlantı [Oracle production v]  Fiziksel şema [BILLING_OWNER v]                  |
|     Revizyon [r3 · test edildi · çalıştırılabilir v]                                  |
|     Sonuç: BILLING_SOURCE > Production > Oracle production / BILLING_OWNER / r3      |
| [X] Üretim eşleştirmesi. Eski yayımlanmış çalışmaların hedefi değişmez.               |
|                     [I] Vazgeç                            [P] Eşleştirmeyi kaydet  |
+--------------------------------------------------------------------------------------+
```

Bir satırda seçim yapıldığında diğer ortamlar değişmez. Revizyon alanı gereksiz UUID değil açık sürüm bağlamıdır; kullanıcıya revizyonun etkin/uygun durumu ve etkisi gösterilir.

### 20.8 Proje nesne ağacı ve klasör içeriği

```text
+-----------------------------+--------------------------------------------------------+
| [B] Finance DWH             | [B] Finance DWH / Geliştirme / Billing                 |
| [F] Nesne ara...    [P] Yeni| [T] Billing                                             |
|-----------------------------| [I] Yenile   ... > Klasör işlemleri                     |
| [A] v Akışlar               |--------------------------------------------------------|
|       v Billing [seçili]    | [A] Tür            Nesne adı              Son değişim  |
|         > Veri akışları     |     Veri akışı      Customer load          tarih       |
|         > Prosedürler       |     Prosedür        Prepare staging        tarih       |
|         > Paketler          |     Paket           Daily billing          tarih       |
|         > Çalıştırma planları|                                                        |
|     > Ortak bileşenler      |                                                        |
|       Değişkenler           |--------------------------------------------------------|
|       Sıra üreteçleri       | [D] Seçili nesnenin kısa açıklaması ve açma bağlantısı  |
|       Kullanıcı fonksiyonları                                                       |
|       Yürütme modülleri     | [X] Silme/taşıma etkisi menüden açılan onayda;           |
|     > Modeller              |     sağ tık dışında aynı ... menüsü vardır.             |
+-----------------------------+--------------------------------------------------------+
```

Ağaç ile ana klasör tablosu aynı veri kaynağını kullanır. İkinci bir bağımsız proje gezgini state’i tutulmaz. Sürükle-bırak taşımanın eşdeğeri `Taşı` menüsüdür.

### 20.9 Mapping / veri akışı editörü

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Billing / Veri akışları / Customer load                            |
| [T] Customer load · Taslak · Değişiklik var                  [P] Kaydet               |
| [I] Doğrula   Çalıştır   ...         Çözümleme ortamı: Test v                         |
+---------------------+----------------------------------------------------------------+
| Veri nesneleri      | [A] Kaynaklar             Desteklenen ilişki          Hedefler |
| [F] Tablo/kolon ara |     BILLING.CUSTOMER  ------------------------>  STG.CUSTOMER   |
| Model v             |     [Kaynak ekle]                               [Hedef ekle]   |
| > Billing model    |----------------------------------------------------------------|
|   CUSTOMERS         | [F] Kolon ara...   Eşleşmeyenler   Hatalılar                    |
|   ACCOUNTS          | Hedef kolon     Kaynak / ifade      Tip       Doğrulama        |
|                     | CUSTOMER_ID     SRC.CUSTOMER_ID     NUMBER    Uygun            |
|                     | FULL_NAME       İfade oluştur      VARCHAR   Eksik            |
|                     |----------------------------------------------------------------|
|                     | [D] Seçili ifade: Fonksiyon v | Kolonlar | Argümanlar           |
|                     |     Okunabilir ifade özeti             Uygula / Vazgeç         |
|                     | [X] Veri setini kaldırmak bağlı eşleştirmeleri etkiler.         |
+---------------------+----------------------------------------------------------------+
```

İfade bölümünde ham JSON yoktur. Metadata ve runtime uyumluluğu bilinmiyorsa “Uygun” gösterilmez. İlişki çizgileri paket başarı/hata geçişleri anlamına gelmez.

### 20.10 Prosedür editörü

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Billing / Prosedürler / Prepare staging                            |
| [T] Prepare staging · Prosedür · Değişiklik var             [P] Kaydet                |
| [I] Doğrula   Kaynak okumasını doğrula*   Çalıştır   ...                             |
+-------------------------+------------------------------------------------------------+
| Adımlar      Adım ekle v| [D] Adım 2 — Read customers                               |
| [F] Adım ara...         |     İşlem: Select       Rol: Kaynak                       |
|-------------------------|     Ortam: Test v                                         |
| [A] 1 Truncate staging  |     Mantıksal şema: BILLING_SOURCE                         |
|       Yıkıcı işlem      |     Oracle test / BILLING_OWNER / r4  [Eşleştirmeyi aç]     |
|     2 Read customers <- |------------------------------------------------------------|
|       Kaynak            | [A] Kaynak SQL                                             |
|     3 Insert customers  |  1  SELECT CUSTOMER_ID, CUSTOMER_NAME                       |
|       Hedef             |  2    FROM BILLING_OWNER.CUSTOMERS                          |
|     4 Collect statistics|                                                            |
|                         | Kaynak çıktısı -> Adım 3                                   |
|                         | > Gelişmiş adım ayarları                                    |
|-------------------------|------------------------------------------------------------|
| [I] Çoğalt / Taşı / ... | [X] Üretimde yıkıcı adım için çalıştırma onayı gerekir.    |
+-------------------------+------------------------------------------------------------+
* Yalnız gerçek kaynak ön kontrolü capability'si bulunan kayıtlı sürümde.
```

Adımların çoğu kapalı bir form yığını değildir; yalnız seçili adımın editorü yüklenir. Bağlantı timeout alanı burada tekrar düzenlenmez. SQL biçimi temsildir; kullanıcının mevcut kod stili korunacaktır.

### 20.11 Paket editörü

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Billing / Paketler / Daily billing                                |
| [T] Daily billing · Paket · Kaydedilmemiş değişiklikler       [P] Kaydet              |
| [I] Doğrula   Simüle et*   Çalıştır   ...                                           |
| Diyagram [seçili] | Genel bilgiler                                                  |
+--------------------+-----------------------------------------------------------------+
| Adım paleti        | [I] Geri al  İleri al | Hizala | - 100% + | Sığdır | Panel       |
| [F] Nesne ara...   |-----------------------------------------------------------------|
| Proje nesneleri    | [A] [Başlangıç]                                                 |
|  Prosedür          |     [Prepare staging] --Başarılı--> [Row count > 0 ?]           |
|  Veri akışı        |             | Hatalı                  | Doğru     | Yanlış       |
|  Paket             |             v                        v           v              |
| Kontrol adımları   |        [Notify error]          [Load customers] [Finish empty]   |
|  Koşul             |                                                                 |
|  Değişken          |                                                                 |
| [Diyagrama ekle]   |                                                                 |
+--------------------+-----------------------------------------------------------------+
| [D] Seçili adım: Row count > 0                                                      |
|     Genel | Bağlı nesne | Geçişler                                                   |
|     Doğru -> Load customers   Yanlış -> Finish empty   Hata -> Notify error          |
| [X] Paketten kaldır: yalnız adımı ve ilişkilerini kaldırır, asıl nesneyi silmez.      |
+--------------------------------------------------------------------------------------+
* Simülasyon sözleşmesi yoksa açıklamalı kapalı; doğrulama ile aynı işlem değildir.
```

Bu örnekte false bir başarı dalı veya hata dalı olarak renkle kodlanmaz; etiketi okunur. Başlangıç silme, adım çoğaltma ve otomatik hizalama ayrı geri alınabilir komutlardır.

### 20.12 Operasyon ana ekranı

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Operasyonlar                                                      |
| [T] Çalıştırmalar                                  [P] Çalıştırma başlat             |
|     Çalışmaları izleyin ve sorunlu adımları inceleyin.                               |
| [I] Yenile  Canlı: Açık/Pasif  Son güncelleme: saat                                  |
|--------------------------------------------------------------------------------------|
| Son çalıştırmalar | Aktif | Başarısız [seçili] | Çalıştırma geçmişi                  |
| [F] Nesne ara...  Durum v  Ortam v  Tür v  Son 24 saat v  Saat dilimi: Europe/Istanbul|
|     Aktif filtreler: Başarısız · Test · Son 24 saat               Temizle            |
|--------------------------------------------------------------------------------------|
| [A] Durum       Nesne           Ortam  Başlangıç  Süre   Veri miktarı  Başlatan      |
|     Başarısız   Daily billing   Test   saat       02:14  —            Kullanıcı     |
|     Başarısız   Customer load   Test   saat       00:42  1200 satır   Zamanlama     |
|                                                                                     |
| [I] Sayfa boyutu 50 v                                            < 1 2 3 >          |
| [D] Run adı ayrı detay sayfasını açar; filtre/scroll dönüşte korunur.                 |
| [X] Toplu sil/yeniden çalıştır yok; müdahale run bağlamı ve yetkiyle yapılır.          |
+--------------------------------------------------------------------------------------+
```

Aktif görünüm seçilirse tarih kısıtı kalkar ve bu açıkça yazılır. “Son 24 saatte aktif” yüzünden günlerdir takılı bir çalışma gizlenmez.

### 20.13 Çalıştırma detay ekranı

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Operasyonlar / Daily billing / Run #248                            |
| [T] Daily billing · Deneme 1 · Başarısız · Test                                      |
|     Başlangıç: tarih/saat  Süre: 02:14  Sürüm: v7                                    |
| [P] Başarısız adımdan devam et*  [I] Yeniden çalıştır*  Yenile  Destek özeti  ...     |
+--------------------------+-----------------------------------------------------------+
| [F] Adım / hata ara...   | [D] Insert customers — Başarısız                           |
| v Daily billing          |     Oracle test / BILLING_STG / r4 (bu run'ın hedefi)     |
|   v Prepare staging      |     Hata kodu: ORA-...   Hata mesajı: güvenli açıklama     |
|     Başarılı Truncate    |     Okunan: 1200  Yazılan: —  Byte: —                    |
|   v Load customers       |-----------------------------------------------------------|
|     Başarılı Read        | [A] Özet | Loglar [seçili] | Olaylar                       |
|     Hatalı Insert <-     | [F] Seviye v  Logda ara...  Seçili adım / Deneme 1         |
|   > Final checks         | saat  ERROR  Güvenli hata mesajı                           |
| [A] Gerçek parent ağacı | saat  INFO   Çalıştırma adımı sonlandı                     |
|                          | [I] Canlı takip   Sona git   Güvenli metni kopyala         |
|                          | [X] Devam tekrar yazabilir; kapsam ve hedef onayda görünür|
+--------------------------+-----------------------------------------------------------+
* Yalnız server allowedActions + checkpoint/idempotency sözleşmesi uygunsa.
```

Desteklenmeyen devam eylemi görünür gerekçeyle kapalıdır. Run bitmişse log “canlı” göstergesi kendiliğinden açık kalmaz. Hata kodu yanında hata mesajı olmadan kullanıcı teşhise zorlanmaz.

### 20.14 Modeller çalışma alanı

```text
+--------------------------------------------------------------------------------------+
| [B] Finance DWH / Geliştirme / Modeller / Billing model                             |
| [T] Billing model                                    [P] Metadata içe al            |
| [I] Metadata'yı yenile   Kullanımlar   ...                                          |
+-------------------------+------------------------------------------------------------+
| [F] Veri nesnesi ara...  | [D] CUSTOMERS · BILLING_SOURCE · İnceleme ortamı: Test     |
| v Billing model         |     Oracle test / BILLING_OWNER / r4                       |
|   > Alt modeller        |------------------------------------------------------------|
|   v Tablolar            | [A] Kolon             Veri tipi      Nullable   Anahtar    |
|     CUSTOMERS <-        |     CUSTOMER_ID       NUMBER         Hayır      PK          |
|     ACCOUNTS            |     CUSTOMER_NAME     VARCHAR2       Evet       —           |
|   > Görünümler          |                                                            |
|                         | [I] Veri akışında kullan                                   |
|                         | [X] Metadata kaldırma fiziksel tabloyu silmez.             |
+-------------------------+------------------------------------------------------------+
```

Metadata sürümü/keşif zamanı gerçekten sağlanıyorsa başlıkta gösterilir. Örnek PK/nullability değerleri yalnız kaynağın sağladığı ölçüde doldurulur.

### 20.15 Üretim çalıştırma ön kontrolü

```text
+------------------------------ Onay: 720 px -------------------------------------------+
| [B] Daily billing / Çalıştırılabilir sürüm v7                                        |
| [T] Üretim çalıştırmasını onaylayın                                                  |
|--------------------------------------------------------------------------------------|
| [D] Ortam: Production      Hedef: Oracle production / BILLING_OWNER / r3             |
| [A] Ön kontrol                                                                       |
|     Yetki: Uygun      Sürüm: Pinlenmiş      Eşleştirme: Doğrulandı                   |
|     Yıkıcı adımlar: 1 · Truncate staging                                             |
|     Tekrar çalıştırma/yan etki bilgisi: sunucudan doğrulanan kapsam                  |
| [F] Bu onayda filtre yok; hedef/sürüm burada sessizce değiştirilemez.                 |
| [X] Veriler değiştirilecektir. Tamamlanan işlemlerin otomatik geri alınacağı          |
|     garanti edilmez.                                                                |
|     [ ] Gösterilen ortamı ve etkilenecek adımları kontrol ettim.                     |
|--------------------------------------------------------------------------------------|
| [I] Vazgeç                                               [P][X] Üretimde çalıştır   |
+--------------------------------------------------------------------------------------+
```

Bu onay kullanıcıyı sorumluluk metniyle baş başa bırakmaz; gerçek hedef, sürüm ve tehlikeli adımları gösterir. Sunucu bilgisi eksikse işlem engellenir; yalnız checkbox işaretlemek eksik güvenlik verisini telafi etmez.

## 21. Responsive Davranış

### 21.1 Ekran aralıkları

| Görünüm | Uygulama kabuğu | Editor ve tablolar |
|---|---|---|
| ≥1440 px | 280 px sidebar; 56 px topbar | Proje ağacı + palet + canvas birlikte kullanılabilir; alt panel açık. |
| 1280–1439 px | Editor açıldığında odak modu önerilir; 64 px sidebar | Paket paleti korunur; metadata detayları kapatılabilir. Liste ekranında sidebar geniş kalabilir. |
| 1024–1279 px | Varsayılan dar sidebar, açık yeniden genişletme kontrolü | Panel aç/kapa düğmeleri görünür; iki büyük yan panel aynı anda zorlanmaz. |
| 768–1023 px | Navigasyon açılır gezinme paneli; topbar proje adı görünür | Prosedürde adım listesi ve editor ardışık seçilir; run detayı ağaç/ayrıntı sekmeleriyle. |
| 320–767 px | Tek sütun; proje adı ve aktif workspace metinle korunur | Formlar tek sütun; operasyon için öncelikli kolonlar/kısa kayıt görünümü; grafik için erişilebilir adım/geçiş listesi. |

Genişlik daraldı diye kullanıcıya “yalnız masaüstünde kullanın” duvarı konmaz. Büyük grafiğin tamamını aynı anda göstermek mümkün değildir; ancak **adım seçme, özellik düzenleme, geçiş ekleme ve doğrulama** liste biçiminde yapılabilir. Bu görünüm ayrı iş modeli değil aynı grafın erişilebilir projeksiyonudur.

### 21.2 Dar ekran ilkeleri

Form sırası DOM sırasıyla aynı kalır; host/port gibi ilişkili alanlar gerektiğinde alt alta gelir. Alt eylem satırı kritik içeriği örtmez. Modal ekran kenarlarında en az 12–16 px boşluk bırakır; uzun form zaten ayrı sayfadır. Topbar’da dil/tema profil menüsüne taşınabilir, fakat proje kimliği kaybolmaz.

Tabloda bütün kolonları 8 px yazıyla sıkıştırmak yoktur. Önce ad, durum, ortam ve eylem korunur; ikincil metrikler satır detayında açılır. Gerçek iki boyutlu karşılaştırmanın gerekli olduğu mapping grid ve graf kendi içinde yatay kayabilir; **sayfanın tamamı** beklenmedik biçimde sağa taşmaz. Klavye odağı kaydırma alanını terk edebilir.

%200 yakınlaştırmada ana işlemler üst üste binmeyecek; %400/reflow kontrolünde genel kabuk ve formlar tek sütuna geçecektir. İki boyutlu diyagram istisnası, erişilebilir metin/listenin yokluğunu meşrulaştırmaz. Bu davranışlar yalnız CSS breakpoint’ine bakılarak tamamlanmış sayılmaz.

## 22. Erişilebilirlik Gereksinimleri

### 22.1 Standart ve ürün hedefi

Hedef **WCAG 2.2 AA** olacaktır. Normal metin kontrastı en az 4.5:1, büyük metin 3:1; gerekli non-text kontrol/odak ayrımları uygun 3:1 ölçütüyle kontrol edilir. WCAG’nin 24×24 CSS px hedef boyutu ölçütü ve istisnaları ile AKIŞ’ın daha yüksek **40 px normal / 44 px dokunmatik** hedefi karıştırılmaz. Sürükleme için alternatif işlem ve görünür/örtülmeyen odak zorunlu kabul edilir. [D24]

### 22.2 AKIŞ’a özgü davranış sözleşmeleri

| Alan | Uygulanacak davranış |
|---|---|
| Uygulama | Sayfa başında `Ana içeriğe geç`; anlamlı `main`, `nav`, başlık düzeni; route değişiminde odak H1/ana içerik başlangıcına. |
| Ağaç | Yukarı/aşağı görünür düğümler; sağ aç/çocuğa git; sol kapat/ebeveyne git; Home/End; Enter aç; yazıyla arama. Seçim ve odak ayrı tutulur. [D25] |
| Sanal ağaç | Dinamik satırlarda seviye/konum/toplam bilgisi, odaktaki düğümün DOM’dan beklenmedik çıkarılmaması. |
| Menüler | Üç nokta ve Shift+F10 eşdeğer; Escape kapatır ve odağı geri verir; yeni menü açılması altında sayfayı kaydırmaz. |
| Paket diyagramı | Her düğüm/edge erişilebilir isim; metinli çıkışlar; `Geçiş ekle` formu ve adım/geçiş listesi alternatifleri. |
| Prosedür sıralama | DnD yanında Yukarı/Aşağı taşı; değişiklik sonrasında yeni sıra duyurulur. |
| Form | Label–input ilişkisi; yardım/hata için `aria-describedby`; hatalı alan `aria-invalid`; required yalnız yıldızla açıklanmaz. |
| Dialog | Odak içeride kalır; arka plan etkileşimsiz; kapanışta odağı açana geri ver; dirty state korunur. |
| Tablolar | Gerçek table/header semantiği; sıralanan kolonda `aria-sort`; satır eylemlerinde nesne adı dahil erişilebilir label. |
| Canlı sonuç | Küçük değişiklikler `polite` duyurulur; her 10 saniyede bütün tablo okunmaz. Kritik hata `alert`, sürekli spam yok. |
| Renk | Her durum metin ve ikonla; paket true/false hata rengine bağımlı değil. |
| SQL editor | Klavye tuzağı yok; editörden çıkma yolu açıklı; ekran okuyucu metin alanı modu; satır/hata mesajı ilişkilendirilir. |
| Oturum süresi | Yetki/oturum sona erdiğinde bağlam ve kaydedilmemiş çalışma durumu açık; gizli bilgi koruması sürer. |

React Flow’un sağladığı erişilebilirlik seçenekleri etkinleştirilecek ve Türkçeleştirilecektir; kendi düğüm renderer’ları bu özellikleri kaldırmayacaktır. Kütüphanenin varsayılanları üzerine uygulama düzeyi keyboard, focus, hit area ve ekran okuyucu testleri yapılır. [D26]

### 22.3 Kabul için manuel kontrol

En az klavye-only, Windows ekran okuyucu ve macOS ekran okuyucu senaryoları; ayrıca yüksek kontrast/forced-colors, azaltılmış hareket, %200 yakınlaştırma ve dar reflow çalıştırılır. Kullanılan tarayıcı/işletim sistemi/yardımcı teknoloji sürümleri test raporuna yazılır. Otomatik a11y taraması yararlıdır fakat tek başına kabul değildir. Mevcut kodda bütün bunların geçtiği iddiası yoktur.

## 23. Boş/Yükleniyor/Hata/Başarı Durumları

### 23.1 Ortak durum modeli

Her veri alanı en az **ilk yükleme, boş veri, filtre sonucu boş, başarılı veri, kısmi hata, tam hata, güncelliğini yitirmiş veri, yetkisiz erişim** durumlarını ayıracaktır. Tek bir `loading/error/data` bayrağıyla bütün workspace’i kapatmak yerine işlev alanı bazında durum yönetilir.

| Durum | Kesin ekran dili | Korunacak davranış |
|---|---|---|
| İlk yükleme | Yerleşimi koruyan skeleton + “Bağlantılar yükleniyor” gibi gerçek nesne adı | Rastgele sayı/örnek satır gösterme; filtre eylemleri gerekli ölçüde kapalı. |
| İlk kullanım / hiç kayıt yok | “Henüz bağlantı yok.” + `Yeni bağlantı` | Sorunun kullanıcıda olduğu ima edilmez. |
| Filtre boş | “Bu filtrelerle eşleşen çalıştırma bulunamadı.” + `Filtreleri temizle` | Filtreler görünür kalır. |
| Kısmi hata | “Şema kullanımları yüklenemedi.” + o alanı yeniden dene | Başarılı bağlantı özeti kaybolmaz. |
| Tam hata | Kullanıcıya anlaşılır açıklama + `Yeniden dene` + izinli destek kodu | Ham stack/credential mesajı yok. |
| Eski veri | Son başarılı zaman + “Veriler güncel olmayabilir” | Eski veri güncelmiş gibi yeşil işaretlenmez. |
| Yetkisiz | “Bu proje/işlem için erişiminiz yok.” | Eski projenin verisi içerikte kalmaz; alternatif güvenli yol. |
| Kayıt başarı | Kısa toast + başlıkta kaydedilen durum | Kayıt sonrası yeri ve seçimi koru; her seferinde ana sayfaya atma. |
| Test başarı | Inline sonuç; revizyon/kapsam/tarih | Toast tek kanıt olmaz; “etkin” ile karıştırılmaz. |
| Eşzamanlı değişiklik | “Bu kayıt siz düzenlerken değişti.” | Sessiz üzerine yazma yok; değişiklikler korunarak karşılaştırma/yeniden yükleme. |

### 23.2 Ekranlara özgü mikro metinler

| Ekran | İngilizce | Türkçe | İşlem |
|---|---|---|---|
| Bağlantı testi | Connection tested successfully. This does not activate the revision. | Bağlantı testi başarılı. Bu işlem revizyonu etkinleştirmez. | Revizyonu incele. |
| Eksik binding | No schema binding exists for Test. | Test ortamı için şema eşleştirmesi yok. | Eşleştirmeyi aç/oluştur. |
| Metadata | No objects matched the discovery scope. | Keşif kapsamıyla eşleşen nesne bulunamadı. | Kapsamı değiştir. |
| Paket başlangıcı | Choose a start step before making this package runnable. | Paketi çalıştırılabilir yapmadan önce başlangıç adımı seçin. | Başlangıcı seç. |
| Desteklenmeyen SQL | This operation is not supported by the selected runtime profile. | Bu işlem seçili runtime profilinde desteklenmiyor. | Desteklenen işlemleri görüntüle. |
| Run logu yok | This step did not start. Review run events. | Bu adım başlamadı. Çalıştırma olaylarını inceleyin. | Olayları aç. |
| Devam yok | No safe resume point is available for this run. | Bu çalışma için güvenli devam noktası bulunmuyor. | Hata/etkiyi incele. |
| Kayıt hatası | Your changes are still here. Try saving again. | Değişiklikleriniz korunuyor. Kaydetmeyi yeniden deneyin. | Yeniden kaydet. |

### 23.3 Uzun süren işlemler

Keşif, test ve çalıştırma başlatma ayrı meşgul durumları taşır. Süresi bilinmeyen işte sahte yüzde ilerleme yoktur. Uzun istek iptal edilebiliyorsa `İptal et` gerçek server/istek semantiğine bağlanır; yalnız dialog’u kapatmak işlemi iptal etmiş sayılmaz. Test sonrasında başarı mesajı yeni bir düzenleme ile geçersizleştiğinde eski yeşil sonuç görünür kalmaz.

## 24. Uygulama Yol Haritası

### 24.1 Sıra ve teslim ilkesi

Aşağıdaki gruplar **gelecekte geliştiricinin uygulayacağı iş planıdır**; bu araştırma sırasında yapılmış kod değişiklikleri değildir. Karmaşıklık, insan-gün tahmini değil göreli değerlendirmedir. Her grup ilgili kabul testlerini geçmeden bir sonraki grupta “hazır temel” sayılmaz.

**Sıra:** G1 → G2 → G3 → G4 → G5 → G6 → G7 → G8 → G9 → G10. Ortak veri/erişim sözleşmesi önceden hazırsa bazı ekranlar paralel geliştirilebilir; yayımlama kapıları bu sıraya göre korunur. G8’in graf veya G9’un güvenli devam sözleşmesi eksikse tasarım gösterimi ile gerçek işlem ayrı teslim edilir; eksik işlev için sahte başarı verilmez.

### G1 — Ortak tasarım sistemi, dil ve uygulama kabuğu

**Amaç:** Bütün sonraki ekranların aynı başlık, eylem, form, durum ve gezinme sözleşmesini kullanmasını sağlamak; yeni ekran başına yeni UI ailesi yaratmamak.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Proje seçimi, proje girişi, uygulama kabuğu; bütün ekranların ortak header/controls yüzeyi. |
| Mevcut bileşenler | `app/App.tsx`, `app/AppShell.tsx`, `styles.css`, `features/projects/ProjectsPage.tsx`, `ProjectOverviewPage.tsx`, `features/operations/OperationsUi.tsx`, `core/i18n/index.ts`. |
| Yeni bileşenler | `core/ui/Button.tsx`, `Field.tsx`, `PageHeader.tsx`, `StatusBadge.tsx`, `AsyncState.tsx`, `FilterBar.tsx`; `app/WorkspaceNavigation.tsx`, `app/ProjectSwitcher.tsx`. |
| Birleşecek/kaldırılacak | `OperationsUi` içindeki generic header/field/status katmanı ortak UI’ya taşınır; feature wrapper’ları geçici adapter olur. Mevcut `core/ui/Dialog.tsx` geliştirilir, başka dialog sistemi eklenmez. |
| Kabul kriterleri | AC01–AC10 ve AC52–AC55. Üç workspace her modda erişilir; light/dark ve EN/TR tutarlıdır. |
| Test senaryoları | Tek/çok/sıfır proje; son projeye erişim kaybı; dar sidebar; dirty state ile proje değişimi/çıkış; iki dilde uzun etiketler. |
| Bağımlılıklar | Mevcut proje ve yetki cevapları; yeni backend mimarisi yok. |
| Karmaşıklık | Orta–yüksek; yatay etkisi nedeniyle dikkatli migrasyon. |
| Uygulama sırası | **1.** Ortak bileşenler önce iki temsilî sayfada doğrulanır, sonra feature wrapper’ları değiştirilir. |

### G2 — Bağlantı kataloğu, detay ve Oracle formu

**Amaç:** Kart duvarını karşılaştırılabilir katalog tablosuna, uzun formu ayrı sayfaya çevirmek; bağlantı revizyon/test/yetenek ayrımını görünür yapmak.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Bağlantı listesi, oluşturma, detay, revizyon düzenleme ve test sonucu. |
| Mevcut bileşenler | `features/topology/TopologyPage.tsx`, `OracleConnectionCreateForm.tsx`, `OracleConnectionEndpointEditForm.tsx`, `topology.css`, `api.ts`; `app/App.tsx`. |
| Yeni bileşenler | `features/connections/ConnectionsPage.tsx`, `ConnectionsTable.tsx`, `ConnectionCreatePage.tsx`, `ConnectionDetailPage.tsx`, `ConnectionRevisionPage.tsx`, `ConnectionTestResult.tsx`. |
| Birleşecek/kaldırılacak | TopologyPage’in bağlantı kart dalı kaldırılır. Oracle alan mantığı tek provider form adapter’ında birleştirilir; domain API tipleri korunur. |
| Kabul kriterleri | AC11–AC18, AC48–AC50. Etkin revizyon doğru; test success = active değildir; sırlar geri doldurulmaz. |
| Test senaryoları | r3 etkin/r4 taslak; test sürerken alan değişimi; yalnız açıklama değişimi; JNDI test-only; başarısız test; tekrar parola/secret koruma sözleşmesi; 500 bağlantı. |
| Bağımlılıklar | G1; katalog özet/sayfalama projeksiyonu; güvenli secret reuse gereksinimi; mevcut concurrency. |
| Karmaşıklık | Yüksek; form state’i ve revizyon güvenliği birlikte değişir. |
| Uygulama sırası | **2.** Önce salt okunur liste/detay, ardından create/edit/test geçişleri. |

### G3 — Fiziksel şema, mantıksal şema ve ortam eşleştirmeleri

**Amaç:** Sahiplik ve ilişkiyi ayırmak; tek bir kanonik binding editörü sağlamak.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Bağlantı fiziksel şemaları, mantıksal şema listesi/detayı, ortam listesi/detayı, kullanım. |
| Mevcut bileşenler | `TopologyPage.tsx` şema/binding/ortam dalları, `topology/api.ts`, `topology/copy.ts` metin sözlüğü. |
| Yeni bileşenler | `features/connections/PhysicalSchemasPage.tsx`; `features/schemas/LogicalSchemasPage.tsx`, `LogicalSchemaDetailPage.tsx`, `EnvironmentBindingsMatrix.tsx`, `SchemaBindingEditor.tsx`; `features/environments/EnvironmentsPage.tsx`, `EnvironmentDetailPage.tsx`. |
| Birleşecek/kaldırılacak | Aynı sayfadaki ayrı şema/ortam/binding modal dalları yeni sahiplerine taşınır; birden fazla binding formu birleştirilir. |
| Kabul kriterleri | AC19–AC23. Ortam proje düzeyinde; sürüm pin’i korunur; bağımlı silme engellenir. |
| Test senaryoları | Aynı logical schema için Test/Prod farklı hedef; yinelenen etkin binding; yanlış bağlantıya ait fiziksel şema; yeni revizyonun eski run’a etkisizliği; bağımlılık sorgusu hatası. |
| Bağımlılıklar | G2; izinli revizyon, usage/dependency ve ortam risk verisi. |
| Karmaşıklık | Yüksek; doğru hiyerarşi kadar yanlış hedefi önleme önemlidir. |
| Uygulama sırası | **3.** Fiziksel şema → mantıksal şema → ortam matrisi → kullanım/silme. |

### G4 — Bağımsız modeller ve metadata akışı

**Amaç:** Modelleri bağlantı yönetiminden çıkarıp Geliştirme altında, akış editorünün kullanacağı metadata kaynağı yapmak.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Modeller listesi, model/veri nesnesi detayı, metadata içe alma. |
| Mevcut bileşenler | `TopologyPage.tsx` catalog/discovery dalları; `ProjectSidebarTree.tsx`; mevcut model API tipleri. |
| Yeni bileşenler | `features/models/ModelsPage.tsx`, `ModelDetailPage.tsx`, `MetadataImportPage.tsx`, `DataObjectTable.tsx`, `MetadataDiff.tsx`. |
| Birleşecek/kaldırılacak | TopologyPage catalog görünümü kaldırılır; `/models` bu bileşene yönelmez. Aynı keşif formu birden çok yerde tutulmaz. |
| Kabul kriterleri | AC24–AC26. Metadata işi fiziksel tablo yaratma/silme değildir; bilinmeyen alanlar uydurulmaz. |
| Test senaryoları | Eksik ortam binding’i; test-only discovery capability; dar kapsam; metadata kolon değişimi ve akış kullanım etkisi; boş/yarım keşif. |
| Bağımlılıklar | G3; mevcut discovery verisi, gerekiyorsa snapshot/usage projeksiyonu. |
| Karmaşıklık | Orta–yüksek. |
| Uygulama sırası | **4.** Salt okunur metadata → kontrollü keşif/içe alma → fark gösterimi. |

### G5 — Proje ağacı, nesne kabuğu ve çalıştırılabilir sürüm yüzeyi

**Amaç:** Bütün authoring nesnelerini tek gezinme ve sürüm dili altında toplamak; ham JSON’a düşen kullanıcı akışını kaldırmak.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Geliştirme kökü, klasör içeriği, nesne editör başlığı, sürümler, yayın/çalıştırılabilir sürüm geçişi. |
| Mevcut bileşenler | `ProjectSidebarTree.tsx`, `DefinitionsWorkspace.tsx`, `ProjectExplorer.tsx`, `JsonDraftEditor.tsx`; mevcut publication ekranlarına girişler. |
| Yeni bileşenler | `features/definitions/DefinitionEditorShell.tsx`, `DefinitionHeader.tsx`, `DefinitionActionMenu.tsx`, `RunnableVersionsPanel.tsx`; `app/ProjectObjectTreeAdapter.ts`. |
| Birleşecek/kaldırılacak | Aynı projeyi ikinci kez gösteren explorer kaldırılır; gerekirse tek tree adapter’ının klasör görünümü olur. JSON toggle ve kullanıcıya açık `schemaVersion` kaldırılır. `JsonDraftEditor` normal authoring’den çıkarılır. |
| Kabul kriterleri | AC04–AC10, AC27–AC28. Ağaç sırası, tür isimleri, keyboard ve dirty koruması. |
| Test senaryoları | İki klasörde aynı ad; TR `İ/ı`; boş gruplar; 10.000 nesne fixture’ı; arama temizleme; eski deep link; sürüm hazırlamak = çalıştırmak değil. |
| Bağımlılıklar | G1, G4; mevcut tanım/sürüm ve publication sözleşmeleri. |
| Karmaşıklık | Yüksek; büyük workspace bileşeni kademeli ayrılır. |
| Uygulama sırası | **5.** Ortak kabuk ve ağaç tamamlandıktan sonra tür editorleri sırayla taşınır. |

### G6 — Prosedür adımı ve SQL çalışma alanı

**Amaç:** Mevcut iyi master–detail temelini kaybetmeden uzun prosedür, açık hedef bağlamı ve güvenli adım ilişkisi sağlamak.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Prosedür editorü ve uygun sürümlerde kaynak ön kontrolü sonucu. |
| Mevcut bileşenler | `ProcedureEditor.tsx`, `DefinitionsWorkspace.tsx`; mevcut limits/capability entegrasyonu. |
| Yeni bileşenler | `ProcedureStepList.tsx`, `ProcedureStepEditor.tsx`, `ResolvedContextSummary.tsx`, `ProcedureDependencyDialog.tsx`; `core/ui/SqlEditor.tsx` (CodeMirror adapter). |
| Birleşecek/kaldırılacak | Düz SQL textarea ortak editor ile; görünür ham task ID/enum alanları insan okuyabilir sözlükle değiştirilir. Bağı silen sessiz normalize davranışı kaldırılır. |
| Kabul kriterleri | AC29–AC33; yüksek adette çalışma, güvenli taşı/sil/çoğalt, bağlam, runtime ayrımı. |
| Test senaryoları | 10.000 adımlı görüntüleme fixture’ı; kaynak/tüketici taşıma; referanslı adım silme ve geri alma; PL/SQL biçimi; desteklenmeyen işlem; kaynak ön kontrolünde hedefe erişilmemesi sözleşmesi. |
| Bağımlılıklar | G3–G5; metadata çözümleme; editor bağımlılığı güvenlik/sürüm kilidi; authoring ve runtime limit ayrımı. |
| Karmaşıklık | Yüksek. |
| Uygulama sırası | **6.** Bağlam + SQL → adım komutları → yüksek adet ve profil kapıları. |

### G7 — Veri akışı ve ifade editorü

**Amaç:** MappingGrid’in tablo verimliliğini koruyup JSON ve kimlik odaklı veri girişini kaldırmak.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Veri akışı tasarımı, veri seti seçimi, kolon eşleştirme ve ifade paneli. |
| Mevcut bileşenler | `MappingGrid.tsx`, `DefinitionsWorkspace.tsx`, `mappingUtils.ts`, mevcut mapping tipleri. |
| Yeni bileşenler | `DataFlowEditor.tsx`, `DataObjectPicker.tsx`, `ExpressionEditor.tsx`, `MappingValidationPanel.tsx`, `MappingReferenceAdapter.ts`. |
| Birleşecek/kaldırılacak | JSON.parse/stringify ifade hücresi, ID tabanlı çıplak seçiciler ve sessiz bağlı eşleştirme kayıpları kaldırılır; grid bileşeni korunarak yenilenir. |
| Kabul kriterleri | AC34–AC37. AST round-trip, metadata referansı, kayıpsız ifade düzenleme, silme etkisi. |
| Test senaryoları | Null/sabit/kolon/fonksiyon ifadeleri; desteklenmeyen AST; metadata değişimi; aynı isimli kolonlar; tip uyumsuzluğu; otomatik eşleştirmede var olan ifadeyi koruma. |
| Bağımlılıklar | G4–G6; gerçek desteklenen AST düğüm kataloğu ve metadata provenance. |
| Karmaşıklık | Yüksek; okunabilirlik için veri anlamı kaybedilemez. |
| Uygulama sırası | **7.** Seçiciler → grid → ifade adapter/builder → doğrulama. |

### G8 — Paket diyagramı ve kontrol akışı

**Amaç:** Basit paket listesini gerçek görsel ve erişilebilir kontrol akışı editorüne çevirmek.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Paket Diagram/Overview, adım/edge özellikleri, kontrol akışı doğrulaması. |
| Mevcut bileşenler | `StructuredDraftEditor.tsx` içindeki PACKAGE dalı; `DefinitionsWorkspace.tsx`. |
| Yeni bileşenler | `features/definitions/packages/PackageEditor.tsx`, `PackagePalette.tsx`, `PackageCanvas.tsx`, `PackagePropertiesPanel.tsx`, `PackageGraphAdapter.ts`, `PackageValidation.ts`, `PackageStepList.tsx`. |
| Birleşecek/kaldırılacak | PACKAGE liste dalı yalnız kayıpsız geçiş tamamlandıktan sonra kaldırılır; Variable/Sequence dalları silinmez. |
| Kabul kriterleri | AC38–AC43. Tek başlangıç, kimlik bütünlüğü, edge semantiği, undo, erişilebilir eşdeğer ve capability kapıları. |
| Test senaryoları | Sürükleme/tıklama/klavye aynı adımı bir kez ekler; clone kimliği; start silme; false vs error; döngü/referans doğrulama; 500 düğüm fixture; kaydet–aç; eski paket migrasyonu. |
| Bağımlılıklar | G5–G7; kalıcı graph/edge modeli ve çalıştırma semantiği; React Flow sürüm kilidi; simülasyon için ayrı güvenli sözleşme. |
| Karmaşıklık | Çok yüksek; yalnız grafik kütüphanesi ekleme işi değildir. |
| Uygulama sırası | **8.** Model/adapter → salt okunur graph → authoring komutları → doğrulama → yalnız desteklenen run/simülasyon. |

### G9 — Operasyon listesi, yürütme ağacı ve güvenli müdahale

**Amaç:** Başarısız çalışmayı hızla bulmak, gerçek hedef ve adım bağlamında teşhis etmek, yalnız izinli müdahaleyi yapmak.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Son/aktif/başarısız/geçmiş run listesi; run detayı; başlat/iptal/yeniden dene/devam onayı. |
| Mevcut bileşenler | `RunsPage.tsx`, `RunDetailPage.tsx`, `OperationsUi.tsx`, mevcut StartRunDialog/allowedActions kullanımı. |
| Yeni bileşenler | `RunsTable.tsx`, `RunFilters.tsx`, `RunStepTree.tsx`, `RunStepDetails.tsx`, `RunLogViewer.tsx`, `RunActionDialog.tsx`, `RunRefreshController.ts`. |
| Birleşecek/kaldırılacak | Ana ekrandaki kapalı nesne grupları; düz step tree taklidi; olaylara verilmiş ağaç rolleri kaldırılır. Redaction ve izin kontrolleri korunur. |
| Kabul kriterleri | AC44–AC51. Aktif görünüm tarih dışı; first failure seçimi; pinned hedef; güvenli retry/resume; kısmi hata. |
| Test senaryoları | Günlerdir aktif run; yeni satır geldiğinde scroll; 10.000 run fixture; parent’lı paket/prosedür ağacı; hatalı adımdan devam destekli/desteksiz; sonuç belirsiz; log yüklenememesi. |
| Bağımlılıklar | G1, G3, G5; typed run/step projeksiyonu, sayfalama, güvenli hata mesajı ve action eligibility. |
| Karmaşıklık | Çok yüksek; frontend tek başına güvenli resume üretemez. |
| Uygulama sırası | **9.** Liste/filtre → salt okunur detay/log → canlı yenileme → server izinli müdahaleler. |

### G10 — Erişilebilirlik, responsive, rol ve geçiş temizliği

**Amaç:** Yeni tasarımın yalnız örnek ekranda değil bütün hedef kullanım senaryolarında çalışmasını doğrulamak; eski paralel yüzeyleri güvenle kapatmak.

| İş planı alanı | İçerik |
|---|---|
| Değişecek ekranlar | Bütün yeni çalışma alanları ve legacy route yönlendirmeleri. |
| Mevcut bileşenler | App/router, ortak Dialog/tema/i18n, feature CSS dosyaları; taşıma sonrası eski wrapper’lar. |
| Yeni bileşenler | Ürün düzeyi fixture/test yardımcıları, ortak rol görünüm adapter’ı, erişilebilir diagram/list switch. |
| Birleşecek/kaldırılacak | Kullanımı kalmayan feature button/field/header kuralları; tüm tüketicileri taşınmış TopologyPage; duplicate explorer. Drawer genel bileşeni yalnız kullanım analizi sonrası kaldırılabilir, otomatik silinmez. |
| Kabul kriterleri | AC52–AC60; önceki grupların tamamı regresyondan geçer. |
| Test senaryoları | EN/TR, light/dark/system, 320–1920 px ve zoom, keyboard/ekran okuyucu, rol farkları, ağ gecikmesi/hatası, eski bookmark, üretim güvenliği. |
| Bağımlılıklar | G1–G9; bütün erişilebilirlik ve performans fixture’ları. |
| Karmaşıklık | Yüksek; kapanış kontrolü, kozmetik son rötuş değil. |
| Uygulama sırası | **10.** Kullanım testi → düzeltmeler → eski yüzeylerin kontrollü kapanışı → kabul kaydı. |

### 24.2 UI için gerekli sözleşme boşlukları ve kapılar

Bunlar yeni backend mimarisi önerisi değil; tasarımın doğru veriyle çalışması için açıklık gerektiren arayüz ihtiyaçlarıdır.

| Boşluk | Mevcut kanıt | Hedef veri/işlem | Eksikken ekran davranışı | Sahip grup |
|---|---|---|---|---|
| Katalog özet/sayfalama | Bağlantı başına sürüm çağrısı. [K05] | Etkin revizyon + test + sayılar + cursor/page | Küçük veriyle doğru görünüm mümkündür; ölçek kabulü geçmez. | G2 |
| Secret koruma | Editör yeniden parola istiyor. [K08] | Değeri geri vermeden güvenli mevcut secret referansı | Yeniden giriş gerekçesi; sahte “korundu” yok. | G2 |
| Dependency/usage | Mevcut UI’da bütün etkilerin projeksiyonu doğrulanmadı | Yetki kapsamlı isim/tür/sayı ve engel nedenleri | Silme engelli; sorgu hatası “bağımlılık yok” değil. | G3 |
| Ortam risk sınıfı | `risk` opsiyonel. [K09] | Güvenilir risk/üretim sınıflandırması | Risk bilinmiyor; tehlikeli işlem güvenli varsayılmaz. | G3 |
| Metadata provenance/fark | Model tipleri var; tam fark yüzeyi yok. [K09] | Kaynak snapshot/zaman/kapsam ve etkilenen referanslar | Bütün metadata güncelmiş gibi etiketlenmez. | G4 |
| Büyük taslak / runtime sınırı | Editor maxTasks kullanıyor. [K11] | Authoring kapasitesi ile yürütme kapasitesi ayrımı | Kaydetme/çalıştırma ayrı hata ve açık limit. | G6 |
| İfade AST kataloğu | JSON alanı mevcut. [K13] | Desteklenen node/operator ve tip kuralları | Desteklenmeyen ifade kayıpsız salt okunur; bozarak kaydetme yok. | G7 |
| Paket graph | Mevcut UI liste. [K12] | Sabit step/edge kimliği ve açık transition semantiği | Graph gösteriminde uydurma başarı/hata bağlantısı yok. | G8 |
| Simülasyon | Genel paket simülasyonu doğrulanmadı | Sıfır hedef yazma/DB oturumu garantili plan/yol önizlemesi | Açıklamalı kapalı. | G8 |
| Yürütme ağacı | Mevcut detay düz liste. [K15] | parent ID, tür, sıralama, attempt ve pinlenmiş bağlam | Düz liste dürüstçe gösterilir; ağaç varmış gibi türetilmez. | G9 |
| Retry/resume | Cancel/allowedActions temeli var. [K15] | Checkpoint, idempotency, side-effect özeti, izinli aksiyon | Müdahale kapalı; teşhis açık. | G9 |

## 25. Kabul Kriterleri

### 25.1 Fonksiyonel ve güvenlik kabul matrisi

Bu tablo **test planıdır**. Araştırma kapsamında bu testlerin uygulama üzerinde çalıştırıldığı veya geçtiği iddia edilmemektedir.

| ID | Senaryo | Geçme koşulu |
|---|---|---|
| AC01 | İlk açılış, tercih yok | İngilizce metinler ve light tema; `html.lang=en`. |
| AC02 | Bir erişilebilir proje | Kullanıcıdan seçim istemeden o projenin girişine gider. |
| AC03 | Çoklu proje, ilk giriş | Seçim ekranı gösterilir; rastgele ilk projeye geçilmez. |
| AC04 | Daraltılmış sol menü | Geliştirme, Operasyonlar ve Bağlantılar erişilir; proje kimliği görünür. |
| AC05 | Proje değişimi | Eski proje içeriği/yetkisi/async cevabı yeni başlık altında görünmez. |
| AC06 | Kaydedilmemiş nesneden ayrılma | Kaydet/devam, bırak ve kal yolları çalışır; kayıt hatasında yerinde kalır. |
| AC07 | Çıkış ve browser geri | Aynı dirty koruması geçerli; sırlar kalıcı tarayıcı depolamasına gitmez. |
| AC08 | Proje ağacı | Akışlar → Ortak bileşenler → Modeller; reusable mapping görünmez; ikinci Projeler menüsü yok. |
| AC09 | Genel bakış | Yalnız proje karşılama ve sonraki adım; operasyon dashboard’u ve JSON/export barı yok. |
| AC10 | EN/TR uzun ve özel harfli adlar | Başlık/menü taşmaz; `İ/ı` arama davranışı doğru; teknik kod kuralı ad alanına bulaşmaz. |
| AC11 | Bağlantı listesi | Katalog tablodur; arama/filtre/sıralama/sayfa durumu URL ve dönüşte korunur. |
| AC12 | Etkin r3, daha yeni taslak r4 | Ana kart/liste etkin r3’ü gösterir; r4 farklı durumla görünür. |
| AC13 | Taslak testi sonrası ad/açıklama değişimi | Başarılı test sırf bu yüzden geçersiz olmaz. |
| AC14 | Test sonrası host/secret/policy değişimi | Önceki sonuç geçersiz; yeniden test gerekir; eski async sonuç yeni formu doğrulamaz. |
| AC15 | JDBC ↔ JNDI | Gizlenen alan payload’da gönderilmez; JNDI test-only ise çalıştırılabilir gösterilmez. |
| AC16 | Kaydedilmiş parola editörü | Gerçek parola gelmez; literal maske/boş değer secret yerine gönderilmez. |
| AC17 | Yeni revizyon ve etkinleştirme | Test başarı = etkinleştirme değil; eski binding/run pin’leri değişmez. |
| AC18 | Bağlantı testi başarısız/uzun | Alan bilgisi korunur; güvenli hata; yalnız ilgili işlem meşgul; sahte başarı yok. |
| AC19 | Fiziksel şema oluştur | Seçili bağlantı sabit; kayıt metadata tanımıdır, fiziksel DB şeması yaratma değildir. |
| AC20 | Logical+environment binding | Yalnız uygun bağlantının fiziksel şeması/revizyonu seçilir; aynı bağlamda belirsiz çift etkin eşleme yok. |
| AC21 | Ortam kullanımı | Ortam proje kapsamındadır; adından üretim/güvenlik türetilmez. |
| AC22 | Bağımlı bağlantı sil | İsim/tür/sayı gösterilir; bağımlılıklar varken silme engellenir; cascade yok. |
| AC23 | Dependency sorgusu hatası / version conflict | “Bağımlılık yok” veya otomatik sil retry davranışı olmaz. |
| AC24 | Model ana yüzeyi | `/models` bağımsız model sayfasıdır; bağlantılar catalog sekmesi değildir. |
| AC25 | Metadata keşfi | Kapsam/ortam/hedef açık; kısmi keşif tam tarama gibi gösterilmez; satır verisi otomatik okunmaz. |
| AC26 | Metadata değişimi | Kolon farkı ve kullanım etkisi görünür; silinen referans başka kolona sessizce bağlanmaz. |
| AC27 | Normal nesne authoring | Kullanıcı JSON veya `schemaVersion` düzenlemek zorunda kalmaz. |
| AC28 | Çalıştırılabilir sürüm hazırlama | Taslağı kaydet, sürüm hazırla ve çalıştır ayrı onaylı adımlardır; otomatik üretim run’ı yok. |
| AC29 | 10.000 adımlı UI fixture | Adım arama/seçme/gezinti uygulanabilir; bütün formlar aynı anda render edilmez; keyfî UI kesmesi yok. |
| AC30 | Adım taşı/çoğalt/sil | Sabit kimlikler ve source/consumer bağı korunur; etki açık; undo bütün işlemi geri getirir. |
| AC31 | SQL adım bağlamı | Mantıksal şema, ortam, çözümlenen bağlantı/fiziksel şema/revizyon aynı anda anlaşılır. |
| AC32 | SQL editorü | Girinti/metin kaybolmaz; klavye çıkışı, bul/değiştir ve hata konumu çalışır; timeout tekrar alanı yok. |
| AC33 | Runtime desteklenmeyen işlem / preflight | Desteklenmeyen çalıştırma engelli; kaynak ön kontrolü tam simülasyon olarak etiketlenmez. |
| AC34 | Mapping kaynak/hedef seçimi | Metadata adları ve tipleriyle seçim; yalnız ID listesi veya serbest kolon yazımı değil. |
| AC35 | İfade round-trip | Desteklenen AST aç–düzenle–kaydet–aç sonucunda semantik korunur; görsel alanda JSON yok. |
| AC36 | Hatalı/desteklenmeyen ifade | Hata ilgili satırda; önceki geçerli ifade veya kullanıcı girdisi sessizce silinmez. |
| AC37 | Veri seti sil / otomatik eşleştirme | Bağlı eşleştirmeler açık etkilenir; kullanıcı ifadesinin üzerine onaysız yazılmaz. |
| AC38 | Pakete adım ekle | DnD, tıklama ve klavye tek kayıt üretir; yeni kimlik tekildir. |
| AC39 | Başlangıç adımı | Tam bir başlangıç; silmede açıklık; yeniden adlandırmada referans bozulmaz. |
| AC40 | Başarı/hata/true/false | Çıkış türleri metinle ayrılır; false hata rengi/semantiği değildir; cycle/yanlış port engellenir. |
| AC41 | Paket clone/delete/layout/undo | Clone edge’leri istemeden kopyalamaz; silme otomatik rewire yapmaz; layout iş anlamını değiştirmez. |
| AC42 | Paket kaydet–aç ve legacy geçiş | Kimlik, referans, başlangıç ve semantik kayıpsız; eski listede olmayan edge anlamı uydurulmaz. |
| AC43 | Simüle/çalıştır | Gerçek capability yokken etkin değil; doğrulama simülasyon diye gösterilmez. |
| AC44 | Son/aktif/başarısız/geçmiş | Varsayılan tarih/durumlar doğru; günlerdir aktif run aktif görünümden kaybolmaz. |
| AC45 | Run tablosu büyük veri / yenileme | Gerçek sayfalama; filtre, seçim ve scroll korunur; arka planda gereksiz polling yok. |
| AC46 | İç içe yürütme ağacı | Sunucu parent/type/sıra verisiyle oluşur; ilk başarısız yol açılır; adlardan parent tahmini yok. |
| AC47 | Run adımı ve log | Bu run’ın pinlenmiş hedefi, hata kodu+mesajı ve metrik kapsamı görünür; bilinmeyen 0 değildir. |
| AC48 | Hassas veri | UI, URL, toast, log, kopyalama ve hata mesajında parola/token/secret içeriği sızmaz. |
| AC49 | Retry/resume/cancel | Sunucu allowedActions’a bağlı; belirsiz sonuçta güvenli varsayım yok; iptal isteği tamamlandı sanılmaz. |
| AC50 | Üretim işlemi | Gerçek ortam/hedef/sürüm/yıkıcı adımlar onayda görünür; eksik risk verisiyle başlatılmaz. |
| AC51 | Kısmi run/log hatası | Sağlam bölümler kalır; yanlış “log yok” veya sahte başarı yerine ilgili hata ve retry. |
| AC52 | Keyboard / odak | Ana işlerin tamamı faresiz yapılır; menu/tree/dialog odak döngüsü ve dönüşü doğru. |
| AC53 | Kontrast / renk / hit area | Light/dark, hover/focus/selected kombinasyonları ölçülür; durum renk tek başına anlam taşımaz. |
| AC54 | Responsive / zoom | 320–1920 px ve %200/%400 testleri; ana kontrol kaybolmaz, sayfa genel taşması yok; grafik alternatif listesi çalışır. |
| AC55 | Çeviri kapsamı | Eksik anahtar ve beklenmedik ham enum yok; sayı/tarih/çoğul iki dilde; backend kodları değişmez. |
| AC56 | Eski URL’ler | `/topology`, `/runs`, eski definition query linkleri doğru kanonik ekrana/uyumlu görünümüne gider. |
| AC57 | Rol görünümü | Operatör sade yüzey görür; gizlenen eylem doğrudan API isteğinde de yetki kontrolüne tabidir. |
| AC58 | Algısal yön bulma testi | Bölüm 25.2’deki bağlam/eylem test hedefleri sağlanır; sonuç kayıt altına alınır. |
| AC59 | Performans fixture’ları | Bölüm 25.3 bütçeleri belirlenmiş test makinesi/ağ koşullarında sağlanır; ortalama yerine p95 raporlanır. |
| AC60 | Kapsam ve regresyon | Eski immutable kayıtlar/hashes/pinler/roller bozulmaz; kullanılmayan UI yalnız tüketici analizi sonrası kaldırılır. |

### 25.2 3–5 saniye hedefinin ölçülmesi

**Önerilen kabul deneyi:** Ürünü geliştirenler dışında 12 katılımcı; 6 veri geliştirme, 6 operasyon deneyimine sahip kişi. Katılımcıya ilgili ekran 5 saniye gösterilir, sonra kapatılır. “Hangi proje?”, “Hangi nesne/iş?”, “Birincil işlem?”, “Üretim veya yıkıcı işlem var mı?” soruları sorulur. Daha sonra gerçek görev akışı izlenir.

Kabul hedefi: en az 10/12 kişi proje ve birincil eylemi doğru söyler; kritik üretim ekranını test ortamı sanan katılımcı varsa tasarım düzeltilir ve yeniden test edilir. Bu küçük örneklem istatistiksel nüfus kanıtı değil, ürün kabul kapısıdır. “Yeni tasarım %X daha iyi” gibi karşılaştırma iddiası için ayrıca mevcut sürümle aynı görev koşullarında ölçüm gerekir.

Görevler: yeni Oracle bağlantısı oluşturup test etme; Test ortamına mantıksal şema eşleme; 50 adımlı prosedürde hatalı SQL adımını bulma; pakette false dalını ekleme; başarısız run’ın gerçek fiziksel hedefini bulup destek özetini alma. Görev boyunca sağ tık keşfetmeye mecbur kalınmamalıdır.

### 25.3 Performans ve büyük veri doğrulama hedefleri

| Fixture / koşul | Ürün hedefi | Ölçüm yöntemi |
|---|---|---|
| 500 bağlantı; sayfa 50 satır | İlk kullanılabilir liste, test ağı dahil p95 ≤2 saniye | Önceden belirlenmiş referans makine/ağ; sunucu+UI süreleri ayrı. |
| 10.000 proje nesnesi | Ağaçta arama/seçim sonrası ana UI thread uzun süre bloke olmaz; görünür liste güncellemesi p95 ≤300 ms | Arama cevabı hazırken render/interaction ölçümü. |
| 10.000 prosedür adımı UI fixture’ı | Seçili adım açılması p95 ≤200 ms; bütün editorler mount edilmez | Depolama/runtime sınırından ayrı authoring performans testi. |
| 1.000 kolon eşleştirmesi | Scroll ve hücre düzenleme kabul edilebilir; tek hücre yazımı bütün editor state’ini ağır render’a sokmaz | Profiler; yazım tepkisi p95 ≤100 ms hedefi. |
| 500 düğümlü paket fixture’ı | Açma/sığdırma p95 ≤2 saniye; 2.000 edge senaryosu ayrı stres testi | Graph adapter ve renderer maliyetleri ayrı ölçülür. |
| 10.000 run; sayfa 50 | İstemciye bütün geçmiş indirilmez; sayfa ve filtre geçişi p95 ≤2 saniye | Network payload/istek sayısı ve render ölçümü. |
| Uzun log | Segment/cursor yükleme; kullanıcı konumu korunur | 100.000 satırlık sentetik log, gerçek sır içermeyen fixture. |

Bu sayılar **önerilen kabul bütçeleridir; AKIŞ’ta ölçülmüş sonuç değildir**. Referans cihaz/ağ koşulu test başlamadan kaydedilir. Bir performans hedefini tutturmak için kayıt saklamak, hata mesajını kaldırmak veya güvenlik kontrolünü atlamak kabul edilmez.

## 26. Mevcut React Bileşenleriyle Değişiklik Eşleştirmesi

### 26.1 Mevcut → hedef haritası

Aşağıdaki **mevcut yollar repository’de doğrulanmış dosyalardır**. “Yeni” ile işaretlenen hedefler önerilen uygulama yollarıdır; bunların repository’de şimdiden var olduğu iddia edilmez. Bir dosyanın envanterde görünmesi, bütün içeriğinin denetlendiği anlamına gelmez; ayrıntılı kaynak kapsamı bölüm 2’dedir.

| Mevcut dosya / bileşen | İşlem | Hedef ve somut değişiklik | Grup |
|---|---|---|---|
| `frontend/src/app/App.tsx` | Düzenle | Yeni çalışma alanı route’ları ve eski URL yönlendirmeleri; Models ayrı lazy page. | G1–G4/G10 |
| `frontend/src/app/AppShell.tsx` | Böl ve koru | Proje yükleme/dirty koruması kalır; **yeni** `WorkspaceNavigation` ve `ProjectSwitcher`; üç workspace dar modda da görünür. | G1 |
| `frontend/src/app/ProjectSidebarTree.tsx` | Yenile | Kök sırası korunur; sanal tür grupları, locale-aware arama, keyboard ve kalıcı genişletme. | G5 |
| `frontend/src/styles.css` | Tokenlaştır | Ölçü/renk/typography/button kuralları tek sözleşmeye; dark destek korunur; özel küçük fontlar azaltılır. | G1/G10 |
| `frontend/src/core/ui/Dialog.tsx` | Ortaklaştır/geliştir | Tek modal davranışı, focus/return/inert/dirty; tüm formlar bunun üzerinden. | G1/G10 |
| `frontend/src/core/ui/Drawer.tsx` | Tüketici bazında azalt | Büyük form drawer kullanımını kaldır; dosyayı yalnız gerçek tüketici kalmadığında sil. | G10 |
| `frontend/src/core/navigation/PendingChangesContext.tsx` | Genişlet | Nesne/proje/çıkış/sekme geziniminde ortak pending sözleşmesi; mevcut dosya envanterde doğrulandı. | G1/G5 |
| `frontend/src/core/i18n/index.ts` | Koru/güçlendir | İngilizce default, saklanan tercih ve html.lang; feature anahtarlarıyla tek kullanım sözleşmesi. | G1 |
| `frontend/src/core/i18n/locales/en.json` | Düzenle | Sentence case, kullanıcı odaklı hata, tek terim sözlüğü; eski kullanılmayan anahtarlar kullanım taramasından sonra. | G1/G10 |
| `frontend/src/core/i18n/locales/tr.json` | Düzenle | Sequence/Mapping/Publish terim tutarlılığı; `İptal` ile `Vazgeç` bağlama göre ayrılır. | G1/G10 |
| `frontend/src/features/projects/ProjectsPage.tsx` | Tamamla | Tek proje oto seçim korunur; çoklu proje araması, son seçim ve yetki kaybı durumları. | G1 |
| `frontend/src/features/projects/ProjectOverviewPage.tsx` | Küçük düzenleme | Mevcut sadelik korunur; role uygun tek sonraki adım. | G1 |
| `frontend/src/features/topology/TopologyPage.tsx` | Sorumluluklara ayır | **Yeni** connections/schemas/environments/models sayfalarına böl; tüm tüketiciler taşınınca monolit kaldırılır. | G2–G4 |
| `frontend/src/features/topology/topology.css` | Birleştir/azalt | Global token ve ortak UI kullan; yalnız çalışma alanına özgü layout kalır. | G2/G10 |
| `frontend/src/features/topology/OracleConnectionCreateForm.tsx` | Refactor | Provider form mantığı korunur; sayfa yüzeyi, alan hataları, test fingerprint ayrımı. | G2 |
| `frontend/src/features/topology/OracleConnectionEndpointEditForm.tsx` | Refactor | Yeni revizyon kimliği, güvenli secret koruma/yenileme, yaşam döngüsü açıklaması. | G2 |
| `frontend/src/features/topology/api.ts` | Adapter ile kullan | Veri modeli korunur; ekran özetleri ve etki/sayfalama boşlukları sözleşmeyle eklenir; UI için tüm backend yeniden adlandırılmaz. | G2–G4 |
| `frontend/src/features/topology/copy.ts` | Birleştir | Connection/schema/env terimleri merkezi namespace’e; görünmeyen eski label’lar kullanım kanıtıyla kaldırılır. | G1–G3 |
| `frontend/src/features/definitions/DefinitionsWorkspace.tsx` | Kademeli parçala | **Yeni** `DefinitionEditorShell` + tür editorleri; visible JSON/schemaVersion kaldır; sürüm/publish davranışı korunur. | G5–G8 |
| Aynı dosyadaki sürüm/eşleştirme bölümleri | Ayır | Bunlar ayrı dosya varmış gibi değil, mevcut workspace içinden **yeni** panellere taşınır. | G5 |
| `frontend/src/features/definitions/ProjectExplorer.tsx` | Birleştir | Bağımsız ikinci ağaç olmayacak; tek tree adapter’ını tüketir veya kullanım kalmazsa kaldırılır. | G5 |
| `frontend/src/features/definitions/DefinitionTypeIcon.tsx` | Koru/birleştir | Lucide ve kanonik tür sözlüğü; renk tür başına yüzeyi boyamaz. | G1/G5 |
| `frontend/src/features/definitions/JsonDraftEditor.tsx` | Normal yoldan çıkar | Salt okunur gelişmiş tanım görüntüleme; sıradan nesne oluşturma bu editorü zorunlu kılmaz. | G5 |
| `frontend/src/features/definitions/StructuredDraftEditor.tsx` | PACKAGE dalını değiştir | Paket, **yeni** `packages/PackageEditor` tarafından açılır; Variable/Sequence formları ortak field standardına taşınır. | G5/G8 |
| `frontend/src/features/definitions/ProcedureEditor.tsx` | Geliştir/böl | **Yeni** `ProcedureStepList`, `ProcedureStepEditor`, `ResolvedContextSummary`; kayıpsız bağımlılık komutları. | G6 |
| `frontend/src/features/definitions/MappingGrid.tsx` | Grid temelini koru | **Yeni** `DataFlowEditor` içinde metadata/ifade adapter’ı; JSON hücresi gider. | G7 |
| `frontend/src/features/definitions/mappingUtils.ts` | Sözleşme testleriyle düzenle | AST/reference round-trip ve silme etkisi; içerik envanteri doğrulandı, tüm fonksiyonlar bu araştırmada satır satır denetlenmedi. | G7 |
| `frontend/src/features/definitions/definitions.css` | Ortak tokenlara taşı | Sadece editor’e özel grid/panel layout kuralları kalır; içerik audit’i kısmi. | G5–G8/G10 |
| `frontend/src/features/definitions/i18n.ts` | Namespace birleştir | TYPE/SOURCE/TARGET/risk etiketleri kullanıcı sözlüğüne; backend enum değişmez. | G5–G8 |
| `frontend/src/features/execution/RunsPage.tsx` | Yeniden düzenle | **Yeni** `RunsTable`, `RunFilters`, `RunRefreshController`; nesne gruplama ana görünümden çıkar. | G9 |
| `frontend/src/features/execution/RunDetailPage.tsx` | Parçala | **Yeni** `RunStepTree`, `RunStepDetails`, `RunLogViewer`, `RunActionDialog`; gerçek run bağlamı. | G9 |
| `frontend/src/features/operations/OperationsUi.tsx` | Generic parçaları ortaklaştır | PageHeader/Field/StatusBadge/AsyncState core’a; feature wrapper migrasyonu; CopyValue redaction/clipboard hata davranışı. | G1/G9 |
| `frontend/src/features/operations/i18n.ts` | Namespace birleştir | Görünür run/status/action terimleri; ham kodlara güvenli bilinmeyen durum fallback’i. | G1/G9 |

### 26.2 Yeni bileşenlerin sahiplik sınırları

Ortak `core/ui` bileşenleri **domain API çağrısı yapmaz**. Bir Button production riskini kendisi tahmin etmez; bir StatusBadge bilinmeyen backend kodunu otomatik başarıya çeviremez. Workspace/domain katmanı, okunabilir ve yetkilendirilmiş props verir.

`ConnectionTestResult` bir test kanıtını gösterir; `ACTIVE` durumunu üretmez. `ResolvedContextSummary` verilen çözümlemeyi anlatır; istemcide üretim hedefi seçmez. `PackageGraphAdapter` görünüm/domain dönüşümünü yapar; olmayan runtime branch semantiğini icat etmez. `RunActionDialog` sunucudan gelen uygunluk/etki verisini gösterir; checkpoint oluşturmaz.

**Bu ayrım, UI araştırmasının backend tasarımına taşmasını önler:** arayüz ihtiyacı tanımlanır; işlem yetkisi ve yürütme doğruluğu ilgili mevcut domain sözleşmesinde kalır.

### 26.3 Eski yollar ve davranışlar için migrasyon

| Mevcut erişim | Hedef davranış |
|---|---|
| `/projects/:p/topology` | `/projects/:p/connections` yönlendirmesi. |
| `/projects/:p/connections` | Yeni katalog sayfası; kalıcı URL korunur. |
| `/projects/:p/models` | Yeni bağımsız ModelsPage; catalog initialTab kullanılmaz. |
| `/projects/:p/definitions` veya `/development?definition=:d` | Kanonik `/projects/:p/development/definitions/:d`; definition yoksa geliştirme kökü. |
| `/projects/:p/runs` | `/projects/:p/operations`; varsa filtreler taşınır. |
| `/projects/:p/runs/:r` | `/projects/:p/operations/runs/:r`. |
| Eski publication URL’si | Nesne/sürüm bağlamı çözümlenirse çalıştırılabilir sürüm detayına; çözümlenemezse yetkili uyumlu eski detay açık tutulur. Veri silinmez. |
| `start=1` içeren eski link | Yalnız çalıştırma hazırlık dialog’unu açar; onaysız run başlatmaz. |

## 27. Riskler ve Kaçınılması Gereken Tasarım Hataları

### 27.1 Ürün ve teknik risk matrisi

| Risk | Neden tehlikeli? | Bağlayıcı önlem |
|---|---|---|
| GPU’yu görsel olarak kopyalamak | Audit kartı ile yüzlerce nesneli ETL editorü aynı yoğunlukta değildir. | Header/kriter/detay ilkesini al; büyük radius/gradient/kart duvarını alma. |
| “Bağlantılar” altına yeniden her şeyi doldurmak | Topoloji etiketi gider, aynı zihinsel karışıklık kalır. | Sahiplik route’larını ve bağımsız modelleri uygula. |
| Son revizyonu etkin sanmak | Kullanıcı yanlış test veya hedef bağlamını okuyabilir. | Etkin/en yeni/pinlenmiş revizyonu ayrı göster. |
| Test başarısını yetki/çalıştırma garantisi saymak | Keşif destekli bağlantı yazma runtime’ını desteklemeyebilir. | Test, yaşam döngüsü ve capability üç ayrı göstergedir. |
| Bağlantı değişimini eski run’a uygulamak | Tarihsel inceleme ve yeniden çalışma hedefi yanlış görünür. | Run’ın pinlenmiş manifest bağlamını kullan. |
| JSON kaldırırken alan kaybetmek | Yeni güzel form eski semantiği silebilir. | Kayıpsız adapter ve round-trip; bilinmeyen alanı bozarak kaydetme yok. |
| Paket grafiğini yalnız çizim sanmak | Görüntüdeki branch’in runtime karşılığı olmayabilir. | Graph sözleşmesi ve validation kapısı; desteklenmeyen run kapalı. |
| False ile error’ı birleştirmek | İş kuralının normal dalı hata gibi yürür/anlaşılır. | Ayrı port, label ve semantik. |
| Adım silerken otomatik rewire | Kullanıcı fark etmeden farklı iş akışı oluşur. | Etki özeti, incident edge silme, undo; otomatik köprü yok. |
| Sınırsız adımı tek DOM listesi yapmak | Büyük prosedürlerde editor kullanılamaz hale gelebilir. | Sanallaştırma, seçili editor, authoring/runtime limit ayrımı. |
| Retry/resume’u sadece status’tan türetmek | Tekrar yazma, mükerrer veri veya belirsiz sonuç büyüyebilir. | Server eligibility, checkpoint, pin ve idempotency kanıtı. |
| Aktif çalışmalara varsayılan dar tarih filtresi | Takılı eski çalışmalar görünmez. | Aktif görünüm tüm terminal olmayanları kapsar. |
| Otomatik refresh ile kullanıcıyı oynatmak | İncelenen kayıt/log kaybolur; yanlış satıra işlem yapılabilir. | Stable ID, scroll/selection koruma, yeni kayıt bildirimi. |
| Renkle bütün anlamı taşımak | Erişilebilirlik ve gri baskıda anlam kaybı. | Metin, ikon, port etiketi, focus işareti. |
| Sadece düğme gizleyerek yetki uygulamak | Doğrudan istekle işlem denenebilir. | UI görünürlüğü + sunucu authorization. |
| Tarih/saat sınırlarını sessiz yorumlamak | Run eksik veya yanlış aralıkta görünür. | Saat dilimi ve aralık sözleşmesi açık. |
| Çeviri anahtarını silince ekranı düzelttiğini sanmak | Anahtar kullanılmıyor olabilir; gerçek renderer değişmemiştir. | Route/component kullanım kanıtı; iki dilde iş akışı testi. |
| Bağımlılık seçimini güncellik kontrolü yapmadan kilitlemek | Kütüphane taşınmış, destek yolu değişmiş olabilir. | Resmi dağıtım/sürüm/güvenlik kontrolü; CodeMirror taşınma notu dikkate alınır. [D28] |

### 27.2 Tasarımla bilinçli olarak eklenmeyenler

Bu sürüm; AI asistanı, otomatik SQL üretimi, sınırsız sağlayıcı pazaryeri, bütün ekranlara dashboard grafikleri, paralel paket fork/join, keyfî loop motoru veya yeni bir veritabanı yönetim konsolu önermemektedir. Bunlar talep edilen UI/UX problemlerini çözmek için gerekli değildir. “Daha kurumsal” olmak adına ana scope genişletilmeyecektir.

Reusable mapping kullanıcı arayüzüne geri getirilmeyecektir. Genel bakış operasyon dashboard’una çevrilmeyecektir. Proje listesi bağımsız sol menüye eklenmeyecektir. Export ve raw JSON sürekli görünür tutulmayacaktır. Büyük sağdan kayan formlar eklenmeyecektir.

### 27.3 Kanıt ve güven düzeyi

**Yüksek güven:** İncelenen commit’teki route/bileşen yapısı, Oracle form davranışı, mevcut mapping JSON alanı, prosedür master–detail, paket listesi, run ekranının render mantığı, ana dil sözlükleri ve kullanıcı gereksinimleri.

**Tasarım kararı, henüz deneysel doğrulama yok:** Tablo/kart dengesi, 280 px navigasyon, çalışma alanı yerleşimi, renkler, 5 saniye yön bulma hedefi ve performans bütçeleri.

**Sınırlı kanıt:** Güncel yerel GPU/OSB ekranlarının piksel düzeni, ODI ekran görüntüsünün gerçek görsel ayrıntısı, bazı rakiplerin bütün alt ürünlerindeki eş ekranları, mevcut AKIŞ’ın gerçek tarayıcı/network performansı ve bütün backend yetenekleri. Bu sınırlar nihai tasarım kararlarını seçenek listesine çevirmemiştir; doğrulanmamış mevcut durum iddiası üretmekten kaçınılmıştır.

## 28. Nihai Sonuç

AKIŞ için seçilen yön **üç çalışma alanı, tek açık proje bağlamı ve göreve özgü editörlerdir**. Bağlantılar kaynak hazırlama işini; Geliştirme nesne tasarımını; Operasyonlar gerçek yürütme ve teşhisi sahiplenir. Bu ayrım teknik modeldeki bağlantı, mantıksal şema, ortam, tanım, sürüm ve run kavramlarını kullanıcıya anlaşılır bir sırayla taşır.

Bağlantılar ekranı yeniden tasarlanmalı; fakat sürümleme, izin ve güvenlik sözleşmeleri korunmalıdır. Mapping’de JSON düzenlemek yerine desteklenen ifadeler tasarlanmalı; prosedürde bütün adımlar tek ekrana açılmak yerine seçili adıma ve gerçek hedefe odaklanılmalı; paket liste değil anlamlı bir kontrol akışı diyagramı olmalıdır. Operasyonda önce run, sonra adım, sonra log gelmelidir.

**Uygulamanın başarı ölçütü yeni kart sayısı değil**, kullanıcının doğru projeyi/nesneyi/ortamı/eylemi okuyabilmesi, hatayı bulabilmesi ve tehlikeli işlemin etkisini görerek hareket edebilmesidir. Bu rapor, o davranışı ekran taslağı, component eşleştirmesi, veri ihtiyacı, yol haritası ve test sözleşmesi düzeyinde tanımlar.

Araştırma sırasında uygulama kodu değiştirilmemiş, commit veya push yapılmamıştır. Tercih edilen Windows konumuna yazılamadığı için teslim dosyası çalışma ortamında üretilmiştir. Güncel yerel GPU ve OSB dosyaları, ODI görüntüsü ve canlı AKIŞ kullanılabilirlik testleri erişim/doğrulama boşluğu olarak kalmaktadır; bunların yapılmış olduğu varsayılmamalıdır.

### 28.1 Kaynak kayıtları ve erişim notları

**Tüm dış kaynakların araştırma erişim tarihi: 12 Eylül 2026.** “Current”, “stable” veya “latest” URL’leri zamanla değişebilir; içerik bu tarihte görülen dokümantasyona göre kullanılmıştır. Eski ODI 12c sayfası özellikle kullanıcının istediği çalışma mantığını incelemek içindir; güncel ürün arayüzü sürümüymüş gibi sunulmamıştır.

Güncellik örnekleri: ADF Visual authoring sayfasında 25 Mart 2026 güncellemesi; Informatica mapping belgesinde Ocak 2026 güncellemesi; Talend referanslarında `8.0-R2026-08` doküman dalı görülmüştür. Bu tarihler ürünlerin bütün sürümlerinin “en son” olduğu iddiası değildir. Kaynaklar, yalnız kullandıkları başlıktaki özelliği doğrulamak için seçilmiştir. [D04] [D08] [D20] [D21]

**Erişim sınırları:** Informatica’nın doğrudan Mapping Designer alt sayfası erişim hatası verdi; bunun yerine erişilen advanced-mode ve field-properties dokümanları kullanıldı. Güncel taskflow monitoring ekranının tamamı incelenmiş sayılmadı. CodeMirror’ın yeni barındırma sayfası açılamadı; resmi GitHub deposunun taşınma notu ve SQL API belgesi görüldü. ODI şekil dosyası açılamadı; resmi yerleşim açıklaması kullanıldı. OSB dosyası bulunamadı. Bu boşluklar başka ürünlerin ekranlarıyla sessizce doldurulmadı.

### 28.2 Tarihsel GPU kaynak kayıtları

Bu kayıtların indirilebilir yeni kopyaları üretilmemiştir; File Library referanslarının yerel dosya olduğu varsayılmaz. Kaynak yolu import/önceki envanterle, snapshot tarihi yükleme kaydıyla ilişkilidir; tarih güncel repository commit’i yerine geçmez.

| Kaynak | Dosya ve snapshot | Bu raporda kullanılan kanıt |
|---|---|---|
| G01 | `AuditTableConfigPage.tsx` — 22–23 Mayıs 2026 yüklemeleri; örnek kimlik `file_00000000dde871f4af2d6bc5c334e984`; `gpu-ui/src/components/audit-management/` bağlamı | Başlık, amaç, kriter, özet/listenin görev ayrımı. |
| G02 | `AuditTableConfigDataTable.tsx` — 22 Mayıs 2026 23:35 yüklemesi; kimlik `file_00000000a17871f4b7ced480f100d49e` | Adı DataTable olsa da kartlı render; başlıkta ekle eylemi, loading/empty/error ayrımı. |
| G03 | `AuditHistoryPage.tsx` — 23 Mayıs 2026 22:58 yüklemesi; kimlik `file_0000000014547246902c1ea327eb0887`; `gpu-ui/src/components/audit-history/` bağlamı | Criteria → Summary → MasterList → DetailDialog düzeni. Eski Drawer sürümüyle karıştırılmadı. |
| G04 | `AuditHistoryDetailDialog.tsx` — 23 Mayıs 2026 22:59; File Library kimliği `file_00000000a720724693e751028ba987f1` | Seçili hareket, değişen kolonlar, eski/yeni değerlerin yatay karşılaştırması, metadata. |
| G05 | `GpuCommon.module.css` — 22 Mayıs token/buton/panel snapshot’ları ve 23 Mayıs 2026 22:59 son ekler; son dosya kimliği `file_00000000b5a47246859fb102b77accbd` | Nötr tokenlar ve spacing; aynı zamanda ağır gradient/radius/shadow/!important örnekleri. Farklı tarihlerin aynı sürüm olduğu ileri sürülmedi. |
| G06 | `GpuDialog.tsx` — 22 Mayıs 2026 yüklemesi; kimlik `file_000000009e5071f4b18dd42afb5b5d6b`; common/gpu bağlamı | Dialog semantiği/escape/body-lock yaklaşımı; tüm focus-trap davranışı doğrulanmadığından birebir transfer edilmedi. |

### 28.3 Commit’e sabitlenmiş AKIŞ kaynakları

| Kod | İncelenen kaynak | Sabit bağlantı |
|---|---|---|
| K00 | İncelenen commit ve sürüm sabitleme | [`commit/5759b69`][K00] |
| K01 | Uygulama route tanımları | [`frontend/src/app/App.tsx`][K01] |
| K02 | Uygulama kabuğu ve proje değiştirici | [`frontend/src/app/AppShell.tsx`][K02] |
| K03 | Sanal proje nesne ağacı | [`frontend/src/app/ProjectSidebarTree.tsx`][K03] |
| K04 | Genel CSS ve tema tokenları | [`frontend/src/styles.css`][K04] |
| K05 | Bağlantı/şema/ortam/model çalışma alanı | [`frontend/src/features/topology/TopologyPage.tsx`][K05] |
| K06 | Topoloji çalışma alanı CSS | [`frontend/src/features/topology/topology.css`][K06] |
| K07 | Oracle bağlantısı oluşturma | [`frontend/src/features/topology/OracleConnectionCreateForm.tsx`][K07] |
| K08 | Oracle bağlantı uç noktası düzenleme | [`frontend/src/features/topology/OracleConnectionEndpointEditForm.tsx`][K08] |
| K09 | Bağlantı, şema, eşleştirme ve model UI sözleşmeleri | [`frontend/src/features/topology/api.ts`][K09] |
| K10 | Nesne ve sürüm çalışma alanı | [`frontend/src/features/definitions/DefinitionsWorkspace.tsx`][K10] |
| K11 | Prosedür adım editörü | [`frontend/src/features/definitions/ProcedureEditor.tsx`][K11] |
| K12 | Yapılandırılmış değişken/sıra/paket editörü | [`frontend/src/features/definitions/StructuredDraftEditor.tsx`][K12] |
| K13 | Mapping veri setleri ve kolon eşleştirme editörü | [`frontend/src/features/definitions/MappingGrid.tsx`][K13] |
| K14 | Çalıştırmalar ana ekranı | [`frontend/src/features/execution/RunsPage.tsx`][K14] |
| K15 | Çalıştırma detayı | [`frontend/src/features/execution/RunDetailPage.tsx`][K15] |
| K16 | Ortak operasyon bileşenleri | [`frontend/src/features/operations/OperationsUi.tsx`][K16] |
| K17 | Proje seçim ekranı | [`frontend/src/features/projects/ProjectsPage.tsx`][K17] |
| K18 | Proje giriş ekranı | [`frontend/src/features/projects/ProjectOverviewPage.tsx`][K18] |
| K19 | Dil başlangıcı ve tercihi | [`frontend/src/core/i18n/index.ts`][K19] |
| K20 | Önceki kabul edilmiş ürün yönü | [`docs/architecture/PRODUCT_EXPERIENCE_V2_DECISIONS.md`][K20] |
| K21 | Kaynak okuma ön kontrolünün gerçek kapsamı | [`docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md`][K21] |
| K22 | Önceki araştırma raporunun repository kopyası | [`docs/research/AKIS_UI_UX_ARASTIRMA_RAPORU.md`][K22] |
| K23 | İngilizce uygulama metinleri | [`frontend/src/core/i18n/locales/en.json`][K23] |
| K24 | Türkçe uygulama metinleri | [`frontend/src/core/i18n/locales/tr.json`][K24] |

### 28.4 Resmi dış kaynaklar

| Kod | Kaynak / incelenen başlık | Bağlantı |
|---|---|---|
| D01 | Oracle ODI 12.1.2 — Working with Packages | [Resmi kaynak][D01] |
| D02 | Oracle ODI — Setting Up the Topology | [Resmi kaynak][D02] |
| D03 | Oracle ODI — Managing Environments | [Resmi kaynak][D03] |
| D04 | Informatica — Mapping configuration in advanced mode | [Resmi kaynak][D04] |
| D05 | Informatica — Connections API; bağlantı türü ve runtime environment | [Resmi kaynak][D05] |
| D06 | Informatica — Explore page; Data Quality içindeki ortak varlık gezgini örneği | [Resmi kaynak][D06] |
| D07 | Informatica — Incoming fields / field rules / properties | [Resmi kaynak][D07] |
| D08 | Azure Data Factory — Visual authoring | [Resmi kaynak][D08] |
| D09 | Azure Data Factory — Visually monitor pipelines | [Resmi kaynak][D09] |
| D10 | Fabric Data Factory — Monitoring hub pipeline runs | [Resmi kaynak][D10] |
| D11 | Fabric Data Factory — Connector overview | [Resmi kaynak][D11] |
| D12 | Fabric — Pipeline debugging açıklaması | [Resmi kaynak][D12] |
| D13 | AWS Glue Studio — Visual job editor features | [Resmi kaynak][D13] |
| D14 | AWS Glue Studio — Viewing job runs | [Resmi kaynak][D14] |
| D15 | Apache Airflow — UI / Webserver | [Resmi kaynak][D15] |
| D16 | Dagster — Using the webserver | [Resmi kaynak][D16] |
| D17 | Prefect — Blocks | [Resmi kaynak][D17] |
| D18 | Prefect — States | [Resmi kaynak][D18] |
| D19 | Prefect Cloud — Troubleshooting; log bağlamı | [Resmi kaynak][D19] |
| D20 | Talend Studio 8.0-R2026-08 — Palette görünümü ve konumu | [Resmi kaynak][D20] |
| D21 | Talend Studio 8.0-R2026-08 — Contexts and variables | [Resmi kaynak][D21] |
| D22 | dbt platform — Studio IDE user interface | [Resmi kaynak][D22] |
| D23 | dbt platform — Environments | [Resmi kaynak][D23] |
| D24 | W3C — Web Content Accessibility Guidelines 2.2 | [Resmi kaynak][D24] |
| D25 | W3C WAI — Tree View Pattern | [Resmi kaynak][D25] |
| D26 | React Flow — Accessibility | [Resmi kaynak][D26] |
| D27 | Talend — Centralizing context variables | [Resmi kaynak][D27] |
| D28 | CodeMirror SQL dil paketi — resmi kaynak API ve taşınma notu | [Resmi kaynak][D28] |

**Kaynak kullanım ilkesi:** Bir ürünün ilgili sayfasında doğrulanan desen, AKIŞ’a otomatik olarak aynı veri modeli veya çalışma yeteneği taşındığı anlamına gelmez. Rakip desenlerinden türetilen AKIŞ kararları raporda ayrıca seçilmiş ve kapsamlandırılmıştır. Doğrulanamayan ekranlar eksiksiz incelenmiş gibi listelenmemiştir.

[D01]: https://docs.oracle.com/middleware/1212/odi/ODIDG/packages.htm
[D02]: https://docs.oracle.com/middleware/1212/odi/ODIDG/setup_topology.htm
[D03]: https://docs.oracle.com/middleware/12212/odi/concepts/managing_environments.htm
[D04]: https://docs.informatica.com/integration-cloud/cloud-data-integration/current-version/mappings/mappings/mappings-in-advanced-mode/mapping-configuration-in-advanced-mode.html
[D05]: https://docs.informatica.com/cloud-common-services/administrator/current-version/rest-api-reference/data-integration-rest-api/connections.html
[D06]: https://docs.informatica.com/data-governance-and-quality-cloud/data-quality/current-version/introduction/data-quality-tools/explore-page.html
[D07]: https://docs.informatica.com/integration-cloud/cloud-data-integration/current-version/transformations/transformations/incoming-fields/field-rules/creating-a-field-rule.html
[D08]: https://learn.microsoft.com/en-us/azure/data-factory/author-visually
[D09]: https://learn.microsoft.com/en-us/azure/data-factory/monitor-visually
[D10]: https://learn.microsoft.com/en-us/fabric/data-factory/monitoring-hub-pipeline-runs
[D11]: https://learn.microsoft.com/en-us/fabric/data-factory/connector-overview
[D12]: https://learn.microsoft.com/hi-in/fabric/data-factory/how-to-debug-pipelines-in-microsoft-fabric
[D13]: https://docs.aws.amazon.com/en_en/glue/latest/dg/job-editor-features.html
[D14]: https://docs.aws.amazon.com/glue/latest/dg/view-job-runs.html
[D15]: https://airflow.apache.org/docs/apache-airflow/stable/ui.html
[D16]: https://docs.dagster.io/guides/operate/webserver
[D17]: https://docs.prefect.io/v3/concepts/blocks
[D18]: https://docs.prefect.io/v3/concepts/states
[D19]: https://docs.prefect.io/v3/how-to-guides/cloud/troubleshoot-cloud
[D20]: https://help.qlik.com/talend/en-US/studio-user-guide/8.0-R2026-08/showing-hiding-palette-and-changing-its-position
[D21]: https://help.qlik.com/talend/en-US/studio-user-guide/8.0-R2026-08/contexts-and-variables
[D22]: https://docs.getdbt.com/docs/platform/studio-ide/ide-user-interface
[D23]: https://docs.getdbt.com/docs/dbt-platform-environments
[D24]: https://www.w3.org/TR/WCAG22/
[D25]: https://www.w3.org/WAI/ARIA/apg/patterns/treeview/
[D26]: https://reactflow.dev/learn/advanced-use/accessibility
[D27]: https://help.qlik.com/talend/en-US/studio-user-guide/8.0-R2026-08/centralizing-context-variables-in-repository
[D28]: https://github.com/codemirror/lang-sql
[K00]: https://github.com/mehmet-karacan/akis/commit/5759b69cb5747793db410209ddcd71244d7975f6
[K01]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/app/App.tsx
[K02]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/app/AppShell.tsx
[K03]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/app/ProjectSidebarTree.tsx
[K04]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/styles.css
[K05]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/topology/TopologyPage.tsx
[K06]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/topology/topology.css
[K07]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/topology/OracleConnectionCreateForm.tsx
[K08]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/topology/OracleConnectionEndpointEditForm.tsx
[K09]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/topology/api.ts
[K10]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/definitions/DefinitionsWorkspace.tsx
[K11]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/definitions/ProcedureEditor.tsx
[K12]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/definitions/StructuredDraftEditor.tsx
[K13]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/definitions/MappingGrid.tsx
[K14]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/execution/RunsPage.tsx
[K15]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/execution/RunDetailPage.tsx
[K16]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/operations/OperationsUi.tsx
[K17]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/projects/ProjectsPage.tsx
[K18]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/features/projects/ProjectOverviewPage.tsx
[K19]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/core/i18n/index.ts
[K20]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/docs/architecture/PRODUCT_EXPERIENCE_V2_DECISIONS.md
[K21]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/docs/architecture/PROCEDURE_SOURCE_PREFLIGHT.md
[K22]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/docs/research/AKIS_UI_UX_ARASTIRMA_RAPORU.md
[K23]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/core/i18n/locales/en.json
[K24]: https://github.com/mehmet-karacan/akis/blob/5759b69cb5747793db410209ddcd71244d7975f6/frontend/src/core/i18n/locales/tr.json

---

## Nihai Ekran Haritası

Bu harita hedef uygulamanın tek bilgi mimarisidir. `:p` proje, `:d` tanım, `:c` bağlantı, `:l` mantıksal şema, `:e` ortam ve `:r` çalıştırma kimliğini temsil eder. Kimlikler route için kullanılabilir; normal ekran başlığına UUID yazılmaz.

```text
AKIŞ
├── Proje seçimi                                     /projects
│   └── Proje içe aktar                              /projects/import
└── Seçili proje                                     /projects/:p
    ├── Genel bakış                                  [Sakin karşılama]
    ├── Geliştirme                                   /development
    │   ├── Akışlar
    │   │   └── Gerçek klasörler                     /development/folders/:f
    │   │       ├── Veri akışları                    [MAPPING]
    │   │       ├── Prosedürler                      [PROCEDURE]
    │   │       ├── Paketler                         [PACKAGE]
    │   │       └── Çalıştırma planları              [LOAD_PLAN]
    │   ├── Ortak bileşenler
    │   │   ├── Değişkenler
    │   │   ├── Sıra üreteçleri
    │   │   ├── Kullanıcı fonksiyonları
    │   │   └── Yürütme modülleri
    │   ├── Seçili nesne                             /development/definitions/:d
    │   │   ├── Türe özgü tasarım / genel bilgiler
    │   │   ├── Sürümler ve çalıştırılabilir sürümler
    │   │   └── Nesnenin çalıştırma geçmişi           [Aynı operasyon görünümü]
    │   └── Modeller                                 /models
    │       └── Seçili model                         /models/:m
    │           ├── Alt modeller
    │           ├── Tablolar ve kolon metadata'sı
    │           ├── Görünümler ve kolon metadata'sı
    │           └── Metadata keşfi/içe alma
    ├── Operasyonlar                                 /operations
    │   ├── Son çalıştırmalar                        ?view=recent
    │   ├── Aktif çalıştırmalar                      ?view=active
    │   ├── Başarısız çalıştırmalar                  ?view=failed
    │   ├── Çalıştırma geçmişi                       ?view=history
    │   └── Çalıştırma detayı                        /operations/runs/:r
    │       ├── Gerçek adım ağacı
    │       ├── Seçili adım: özet / log / hata
    │       └── Yetkili ve desteklenen müdahaleler
    ├── Bağlantılar                                  /connections
    │   ├── Bağlantı kataloğu                         [Tablo]
    │   │   ├── Yeni bağlantı                        /connections/new
    │   │   └── Bağlantı detayı                      /connections/:c
    │   │       ├── Genel bilgiler                   [Özet kart]
    │   │       ├── Fiziksel şemalar                 /physical-schemas
    │   │       ├── Revizyonlar                      /revisions
    │   │       └── Kullanım                         /usage
    │   ├── Mantıksal şemalar                        /logical-schemas
    │   │   └── Mantıksal şema detayı                /logical-schemas/:l
    │   │       └── Ortam → fiziksel şema matrisi    [Eşleştirme düzenleme yeri]
    │   └── Ortamlar                                 /environments
    │       └── Ortam detayı                         /environments/:e
    └── Proje işlemleri                              [Üst bar bağlam menüsü]
        ├── Proje ayarları
        ├── Proje içe/dışa aktarımı
        └── Yetkili yönetim işlemleri
```

Ağaçtaki kısa route’lar `/projects/:p` önekiyle tamamlanır. Bağlantı altındaki `/physical-schemas`, `/revisions`, `/usage` ise `/projects/:p/connections/:c` önekini kullanır. **Mantıksal şema ve ortam proje kapsamındadır; bağlantının sahibi olduğu alt kaynak değildir.** Buna rağmen kullanıcı navigasyonunda Bağlantılar çalışma alanında bulunurlar. Aktif çalışma alanı yalnız URL metninin önekinden tahmin edilmeyecek; route metadata’sıyla belirlenecektir.

| Ekran / görünüm | Birincil işlem | İkincil işlemler / sınır | Kullanım odağı |
|---|---|---|---|
| Proje seçimi | Projeyi aç | Yetkili kullanıcıya oluştur/içe aktar | Bütün roller |
| Genel bakış | Role göre geliştirmeye veya operasyonlara geç | Proje bilgisi; KPI duvarı yok | Yön bulma |
| Geliştirme / klasör | Yeni nesne | Ara, sırala, klasör işlemleri | Geliştirici |
| Nesne tasarımı | Kaydet | Doğrula, sürüm hazırla, uygun olduğunda çalıştır | Geliştirici |
| Nesne sürümleri | Seçili sürümü çalıştırılabilir hale hazırla | Karşılaştır, bağımlılıkları gör | Geliştirici / yetkili onaylayan |
| Modeller | Metadata içe al | İncele, kullanımı gör; veri tablosu oluşturmaz | Geliştirici |
| Bağlantı kataloğu | Yeni bağlantı | Filtre, test, detay, üç nokta | Kaynak yöneticisi / yetkili geliştirici |
| Bağlantı oluşturma | Bağlantıyı kaydet | Test, vazgeç; test kapsamı açık | Kaynak yöneticisi |
| Bağlantı detayı | Bağlantıyı test et | Düzenleme, yeni revizyon, bağımlılık, silme menüsü | Kaynak yöneticisi |
| Fiziksel şemalar | Fiziksel şema ekle | İncele/düzenle/kullanımı gör | Kaynak yöneticisi |
| Mantıksal şema listesi | Mantıksal şema ekle | Ara, detay | Geliştirici / kaynak yöneticisi |
| Ortam eşleştirme matrisi | Değişiklikleri kaydet | Etkiyi incele, değişiklikleri bırak | Yetkili ortam yöneticisi |
| Ortamlar | Ortam ekle | Ortam detayını ve kullanılan şemaları incele | Yetkili ortam yöneticisi |
| Operasyon ana ekranı | Yetkiliyse çalıştırma başlat; salt okunur rolde birincil yazma işlemi yok | Görünümler, filtreler, yenileme | Operasyon |
| Çalıştırma detayı | Duruma ve yetkiye göre tek müdahale; uygun müdahale yoksa salt okunur | Yenile, nesneyi aç, güvenli ayrıntıları kopyala | Operasyon / geliştirici |

Rol isimleri yeni bir yetki modeli kurulması talimatı değildir; ekranın iş odağını açıklar. Mevcut sunucu izinleri korunur. “Yayınlar”, “JSON”, “Topoloji” ve “Projeler” bağımsız ana navigasyon seçenekleri değildir. Kullanıcı yönetimi veya proje aktarımı üç temel çalışma alanının nesne ağacını işgal etmez.

## İlk Uygulanacak 10 İş

Aşağıdaki işler **henüz uygulanmamış, sırayla yürütülecek ilk uygulama dilimidir**. On grup içeren geniş yol haritasının tekrarı değil, ilk teslimlere ayrılmış somut başlangıç listesidir. Paket, mapping ve operasyon işlerinin ayrıntılı devam sırası bölüm 24’te G5–G10 olarak tanımlanmıştır.

| Sıra | Yapılacak iş | Değişiklik sınırı / bileşen | Bitti sayılma koşulu |
|---|---|---|---|
| 1 | Ortak renk, boşluk, tipografi ve yoğunluk tokenlarını kur | `styles.css` ve yeni core/ui token katmanı; ürün dışı genel framework çıkarma yok | Light/dark yüzey, focus, 36/40 px kontrol ve 44 px tablo satırı örnekleri aynı tokenlardan üretilecek; mevcut tercihler korunacak. |
| 2 | Ortak Button, Field, PageHeader, StatusBadge ve AsyncState oluştur; mevcut wrapper’ları bağla | `OperationsUi.tsx`, temel form/header tüketicileri | Aynı eylem dört farklı görsel sınıfla üretilmeyecek; loading/error/empty ayrımı ve alan hata ilişkisi korunacak. |
| 3 | Kabuk ve proje kapısını nihai kurala geçir | `App.tsx`, `AppShell.tsx`, `ProjectsPage.tsx`, `PendingChangesContext.tsx` | Üç çalışma alanı, tek proje otomatik seçim, çoklu ilk seçim, üst bar değiştirme; kaydetme hatasında proje değişmeyecek. |
| 4 | İki dilin temel metinlerini ve sakin genel bakışı tamamla | `en.json`, `tr.json`, `ProjectOverviewPage.tsx` | Sentence case, tek terim sözlüğü, proje aktarımı/paket ayrımı; genel bakışta dashboard veya export birincil düğmesi olmayacak. |
| 5 | Bağlantı katalog tablosunu ve gerekli özet veri adapter’ını çıkar | `TopologyPage.tsx` içinden yeni `ConnectionsPage`, `ConnectionsTable`, `ConnectionFilters` | Ad/sağlayıcı/etkin revizyon/test/şema sayıları okunacak; eksik veri 0 veya etkin diye uydurulmayacak; arama/sayfalama sözleşmesi belirlenecek. |
| 6 | Oracle bağlantı oluşturmayı ayrı sayfaya taşı | `OracleConnectionCreateForm.tsx`, yeni `ConnectionCreatePage` | Oracle → JDBC/JNDI alanları doğru açılacak; test etkileyen ayarlar değişince test kanıtı geçersizleşecek; isim/açıklama değişimi tek başına testi bozmayacak. |
| 7 | Bağlantı detayını etkin/yeni/pinlenmiş revizyon ayrımıyla kur | Yeni `ConnectionDetailPage`, `ConnectionSummary`, `ConnectionRevisionList`; mevcut edit formu | `versions[0]` etkinlik ölçütü olmayacak; kullanılan eski revizyon görülecek; parola koruma/yenileme yalnız gerçek sunucu sözleşmesiyle çalışacak. |
| 8 | Bağlantı silme ve revizyon değiştirme etkisini kullanıcı dilinde göster | Yeni dependency/impact sunumları; mevcut işlem API’si korunur | Bağımlılık yoksa doğrulanmış silme, bağımlılık varsa neden ve kayıt adları; otomatik cascade veya sessiz ortam taşıma yok. |
| 9 | Fiziksel şemaları bağlantı altına; mantıksal şema ve ortamları proje sayfalarına ayır | Yeni `PhysicalSchemaList`, `LogicalSchemasPage`, `EnvironmentsPage` | Modeller bu sayfalara geri taşınmayacak; kullanıcı aynı ilişkiyi birden fazla formda düzenlemeyecek. |
| 10 | Mantıksal şema ortam matrisini ve çözümleme özetini tamamla | Yeni `SchemaBindingMatrix`, `ResolvedContextSummary` | Mantıksal şema + ortam → bağlantı + fiziksel şema + revizyon açık; kayıtsız değişiklik ve üretim etkisi kontrolü; bağlantı revizyonu etkinleşince mevcut pin sessizce değişmeyecek. |

İlk 10 işin çıkışında temel kaynak hazırlama ve doğru bağlamı okuma tamamlanır. Sonraki editörler bu ortak kabuk, alan, bağlam ve güvenlik bileşenleri üzerinde ilerler. Hedef hiçbir aşamada “daha güzel ama daha az doğru” bir ekranı tamamlanmış saymak değildir.

## Mevcut AKIŞ’tan Kesinlikle Kaldırılacak veya Yeniden Tasarlanacak Unsurlar

| Doğrulanan mevcut unsur | Kesin işlem | Korunacak işlev / kanıt |
|---|---|---|
| `TopologyPage.tsx` içinde bağlantı, şema, ortam, keşif ve modelin birlikte yönetilmesi | Sorumluluklara ayrılacak; tüketiciler taşınınca monolit kaldırılacak. | Mevcut kayıtlar, izinler ve API semantiği korunur. [K05] [K09] |
| Bağlantı ana kataloğunun büyük kart ızgarası ve kart içindeki çok sayıda küçük bilgi kutusu | Tablo kataloğu ile değiştirilecek; özet kart yalnız bağlantı detayında kalacak. | Bağlantı adı, sağlayıcı, hedef ve test bilgileri kaybolmaz. [K05] [K06] |
| Kart üzerinde test, düzenleme ve silmenin aynı hızlı işlem alanında sunulması | Test görünür; düzenleme ve silme uygun detay/üç nokta menüsüne ayrılacak. | Silme kaldırılmaz; keşfedilebilir ama yanlış tıklamaya açık olmayan etki onayına taşınır. [K05] |
| Liste başındaki revizyonu varsayılan doğru/etkin revizyon gibi kullanma yaklaşımı | Yaşam döngüsü ve pin kanıtına göre açık seçim yapılacak. | Revizyon geçmişi ve eski çalıştırma bağlamı korunur. [K05] [K09] |
| Oracle düzenlemede parolanın yeniden girilmesini zorunlu kılan mevcut akış | Güvenli koru/değiştir sözleşmesiyle yeniden tasarlanacak; backend desteği gelmeden sahte “korundu” gösterilmeyecek. | Parola veya secret locator geri okunmaz. [K08] |
| Normal nesne toolbar’ındaki JSON editörü geçişi ve teknik şema sürümü girişi | Normal kullanıcı yolundan çıkarılacak. | Gelişmiş salt okunur ham tanım görünümü ve mevcut serileştirme uyumluluğu korunur. [K10] |
| Mapping’in görsel tablosundaki JSON ifade hücresi | Desteklenen AST ile çalışan ifade düzenleyicisine dönüştürülecek. | Desteklenen mevcut ifadeler kayıpsız round-trip yapar; keyfî SQL desteği icat edilmez. [K13] |
| Mapping veri setinin silinmesi/değiştirilmesinde ilişkilerin kullanıcıya açıklanmaması | Referans etki kontrolü, açık hata ve geri alma akışı eklenecek. | Mevcut kolon eşleştirme tablosu ve klavye çalışması korunur. [K13] |
| Paket editörünün ID ve türlerden oluşan basit adım listesi | Paket diyagramı, palet ve alt özellik paneliyle değiştirilecek. | Teknik kimlikler ve geçerli tanımlar korunur; grafiğe taşınamayan içerik sessizce düşürülmez. [K12] |
| Prosedür adımında kullanıcıya ham enum/teknik ID sunulması | Okunabilir işlem adı, tür, risk ve çözümleme bağlamı kullanılacak. | Seçili adım master–detail düzeni korunup güçlendirilir; bu özellik mevcutta yokmuş gibi yeniden yazılmaz. [K11] |
| Prosedür referanslarını `normalizeTasks` ile sessizce temizleme riski | Komut öncesi etki kontrolü; geçersiz taşıma/değişiklik açıkça durdurulacak veya kullanıcıya onaylatılacak. | Kaynak-tüketici ilişkisinin geçerliliği korunur. [K11] |
| Operasyonun girişinde çalıştırmaların kapalı nesne grupları altında gizlenmesi | Ana yüzey zaman/durum odaklı çalıştırma tablosu olacak. | Nesneye göre filtre ve nesne geçmişi erişimi korunur. [K14] |
| Adımların düz listesine `tree` rolü verilip gerçek hiyerarşi beklentisi oluşturulması | Gerçek ebeveyn ilişkili ağaç; veri yoksa dürüstçe düz adım listesi. | Sıralı adım erişimi kaybolmaz; event JSON’dan parent uydurulmaz. [K15] |
| Operasyon detayında teknik başlangıç türü/olay adlarının ham sunumu | Kullanıcı etiketleri ve ikincil teknik ayrıntı ayrımı yapılacak. | Teknik kanıt yetkili destek için erişilebilir kalır. [K15] |
| Feature bazında tekrar eden header, buton, modal ve durum stilleri | Tek core/ui sözleşmesine taşınacak; gerçekten kullanılmayan CSS sonra kaldırılacak. | İş akışı özellikleri veya erişilebilirlik davranışı kaybedilmez. [K04] [K06] [K16] |
| Büyük-küçük harf, `sequence`, aktarım paketi ve iptal/vazgeç metinlerinin tutarsızlığı | Bölüm 19 sözlüğüne göre düzeltilecek; kullanılmayan anahtarlar ayrı kullanım taramasıyla temizlenecek. | Backend kodları ve kullanıcıların nesne adları değiştirilmez. [K23] [K24] |

**Zaten doğru olanlar geriye götürülmeyecek:** Bağımsız Projeler sol menüsünün olmaması, sade genel bakış, tek proje otomatik seçimi, Akışlar → Ortak bileşenler → Modeller sırası, yeniden kullanılabilir mapping’in gizlenmesi, prosedürün seçili adım düzeni, kaydedilmemiş değişiklik koruması, İngilizce varsayılanı ve gerçek runtime yeteneği kontrolleri korunacaktır. Bunlar yeni bir tasarım uğruna silinecek eksikler değildir. [K02] [K03] [K10] [K11] [K14] [K17] [K18] [K19]

**Nihai yön:** AKIŞ’ın kurumsal niteliği, bütün bilgiyi aynı anda göstermesinden değil; doğru bağlamı, doğru ayrıntıyı ve güvenli bir sonraki işlemi tutarlı biçimde göstermesinden doğacaktır.
