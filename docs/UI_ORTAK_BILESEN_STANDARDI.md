# AKIŞ ortak arayüz standardı

## Güncel Font ve Tema Sözleşmesi (Öncelikli)

Bu bölüm aşağıdaki eski ölçü notlarının yerine geçer. Tüm uygulama CSS dosyaları
ortak `--font-caption` (12 px), `--font-body` (13 px), `--font-title` (16 px),
`--font-section` (18 px), `--font-page` (20 px) ölçeğini kullanır. SQL/kod alanlarında
monospace ailesi korunur. Giriş ekranının büyük tanıtım başlığı ayrı bir ölçektir.
Yeni ekranlarda bağımsız font boyutu veya font ailesi tanımlanmaz.

Koyu tema OSB'nin koyu yüzey, mavi vurgu ve anlamsal başarı/uyarı/hata tokenlarını
kullanır. Editörler dahil alt bileşenler kendi koyu renk paletini oluşturmaz.
Özet kartının etiketi sayısızdır: `Toplam Çalıştırma` + değer. `3 sonuç` + `3`
gibi tekrarlar ve kayıt başlığındaki ikinci sayaç kullanılmaz.
Çalıştırma görünümü (Son/Aktif/Başarısız/Geçmiş) filtre içinde seçilir ve Sorgula
ile uygulanır. Temizle varsayılan görünümü ve filtreleri birlikte sıfırlar.

## Referans

2026-09-14: GPU arşivindeki `AuditTableConfigPage`, `AuditHistoryPage`,
`AuditHistoryDetailDialog` ve bu ekranların kullandığı `GpuPanel`, `GpuButton`,
`GpuDialog`, `GpuCommon.module.css` incelendi.
Arşiv: `gpu-fusion-features-5.0.0_SKYRSM-5449_audit-ui-modernization@2a5e3f19881.zip`.
GPU'nun başka ekranları tasarım referansı değildir. OSB HTML de aynı GPU renk
ailesini kullanır. Temel karar Audit ekranlarının ortak bileşen yaklaşımıdır;
Bootstrap/Next.js bağımlılıkları AKIŞ'a taşınmaz.

## Uygulama sözleşmesi

### 14 Eylül: Filtre ve İşlem Davranışları

- Filtre alanları aynı sütun genişliğinde, etiketler üstte ve girdiler 36 px yüksekliğinde hizalanır. Eski yatay etiket kuralları yeni filtrelere taşınmaz.
- Arama metni yazılırken sonuçlar değişmez. `QueryFilter` içindeki Sorgula (Enter dahil) uygular; Temizle hem yazılan hem uygulanmış değeri temizler.
- Ana ekran bölümleri arasında 16 px bulunur. `gap` kullanan kapsayıcı mutlaka flex veya grid olmalıdır.
- Başarı bildirimi `FeedbackToast` ile 5000 ms görünür; üzerine gelindiğinde veya klavye odağı aldığında kapanma durur, kapatma düğmesi bulunur. Hatalar ilgili formda kalıcı ve tekrar denenebilir olmalıdır.
- Ekleme/düzenleme sırasında Kaydet yüklenme durumuna geçer ve çift gönderim engellenir. Başarıda liste yenilenir; hatada girilen değerler korunur.
- Silme onayında kayıt adı ve bağımlılık etkisi gösterilir. API tarafında proje yetkisi ve bağımlılık kontrolü olmayan nesnelere çalışıyormuş gibi silme düğmesi eklenmez.
- Mantıksal şema/ortam için mevcut API yalnız oluşturma ve listeleme sunmaktadır; ad/kod düzenleme ve silme uçları henüz uygulanmamıştır. Eşleme güncelleme ayrı API olarak vardır. Bu eksiklik kapatılmadan tüm CRUD tamamlandı denmemelidir.
- Mantıksal şema proje sayısından bağımsızdır: model/prosedür sabit bir ad kullanır; ortam bu adı bağlantıdaki fiziksel şemaya çözer. Mevcut motor bu çözümlemeyi kullanır. Tek proje için kaldırılması motor ve model sözleşmesi değişikliğidir.

Her katalog/yönetim ekranı değişmez biçimde şu sırayı izler:

1. Ekran başlığı ve kısa açıklama
2. Açılıp kapanabilen filtreler
3. İkonlu özet kartları
4. Kart, liste veya tablo veri alanı

Görsel tokenların kaynağı `C:\Users\mkaracan\Desktop\osb-dashboard\osb-dashboard.html` dosyasındaki OSB Dashboard sistemidir. AKIŞ değişkenleri OSB renk, yüzey, çizgi, radius, gölge, boşluk ve sistem font değerlerine eşlenmiştir. Bilgi mimarisi ve kayıt yoğunluğu için GPU Audit Yönetimi/Audit Geçmişi ekranları kullanılır. Yeni ekranlar tekil CSS değerleri tanımlamak yerine `--surface`, `--line`, `--accent`, `--success`, `--warning`, `--danger`, `--info` ve ortak UI bileşenlerini kullanır.

- Ana font: `-apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif`.
- Kart radius: 14–18 px; alan radius: 8–11 px.
- Kayıt metni: 12–13 px; yardımcı metin: 11–12 px; ekran başlığı: 20 px.
- Normal aksiyon yüksekliği: 34–36 px. Dar ekranda erişilebilir dokunma hedefi 44 px olabilir.
- Özetler `SummaryStrip`; veri alanı `ProgressiveRecords`, `RecordFields` ve `ViewToggle` ile kurulur.
- Modal ve düzenleme ekranları merkezî `Dialog` kullanır; sağ panel/drawer oluşturulmaz.

- `DesignWorkspace`: tanım ve model rotalarında sabit sol gezinti; sağda içerik.
- `PageHeader`: ekran adı, kısa açıklama ve sağda önceliklendirilmiş işlemler.
- `FilterSection`: ikonlu, açılıp kapanabilen filtre bölümü; mobilde kapalı başlangıç.
- `WorkspaceSection`: başlık, işlemler, isteğe bağlı filtre ve içerik yuvaları.
- `Button`: metin/ikon aralığı, devre dışı ve yüklenme durumları ortak.
- `ViewToggle` / `useCollectionView`: Kart/Liste/Tablo seçimi ve güvenli tarayıcı tercihi. Bağlantılarda kart varsayılandır.
- `CollectionCard`: ikon, başlık, alt başlık, içerik ve işlem alanı. Kart/liste aynı veri kümesini kullanır.
- `Dialog`: merkezî yerleşim, odak yönetimi, Escape, arka plan kilidi. Sağdan açılan alternatif yazılmaz.
- `ModelObjectTree`: model alt klasörleri ve tablo/görünüm ikonları.
- `/project/ui-kit`: ortak bileşenlerin inceleme ekranı; örnekler veri yazmaz.

Renkler büyük alanları boyamak yerine işlem/odak/ikon vurgusunda kullanılır.
Birincil mavi, başarı yeşil, uyarı amber, hata/silme kırmızı ailesindedir.
Liste ekranları başlık → filtreler → sonuçlar; editörler sabit ağaç → detay
akışını izler. Mobilde ağaç üstte sınırlı yükseklikle kalır, içerik alta geçer.

Veri nesnesinin mantıksal şeması modelinden gelir. Nesne düzeyinde ikinci bir
bağımsız seçim oluşturulmaz. Çalıştırma ortamı fiziksel şema eşlemesini belirler.
Yeni metadata içe aktarımında alt klasör seçilebilir. Mevcut nesneyi başka alt
klasöre taşıma ve model kökü üstünde bağımsız klasörler bu değişiklikte eklenmedi.

## Takip

### Onaylı Ekran Referansı

Kullanıcının Audit Yönetimi, kart/liste/tablo ve düzenleme diyaloğu görselleri
yerleşim referansıdır. Üst yönetim paneli başlık ve gizlenebilir filtreleri barındırır.
Yeni kayıt ve görünüm araçları sonuç panelinin üstündedir. Filtreler Ara ile uygulanır,
Temizle ile sıfırlanır. Bağlantı testi kayıt üzerinde çalışır; sonuç bağlantı detayına
girmeden gösterilir. Test sonucu canlı erişim garantisi olarak yorumlanmaz.
Ortak yazı ailesi Segoe UI/system-ui; sayfa/diyalog başlığı 24 px, alanlar 14 px,
kayıt değerleri 13 px, yardımcı etiketler 12 px olarak kullanılır.

### Bağlantı Kataloğu — 14 Eylül Düzenlemesi

İkinci görsel kontrolde uzun kartlar ve değişken liste sütunları reddedildi.
Katalog düzeni `catalog-layout.css` ile izole edilir: geniş ekranda iki kart,
kart içinde dört teknik alan sütunu; dar ekranda iki alan sütunu ve tek kart.
Test sonucu ve zamanı teknik alanlardan ayrı, kompakt alt satırda gösterilir.
Liste kimliği 160 px ile sınırlanır; bilgi alanları aynı sütun ızgarasına hizalanır.
Kart yüksekliği masaüstü E2E testinde 400 px altında doğrulanır.

- Audit referansındaki ikon + etiket + değer alanları `RecordFields` üzerinden paylaşılır.
- Yazı ailesi ortak kökten gelir; etiket 12 px, değer 14 px, kart başlığı 16 px, ikon 16 px.
- Sunucu, port, servis/SID, kullanıcı ve şema sayıları birleşik metin olarak gösterilmez.
- Yeşil test simgesi yalnız son başarılı test kaydını anlatır; canlı erişim garantisi değildir.
- Kart, liste ve tablo aynı katalog verisini kullanır; görünüm seçimi küçük ikon grubudur.
- Ekleme ve detay merkezî diyalogda açılır; liste filtresi ve görünümü korunur.
- `ProgressiveRecords` katalog sonuçlarını kaydırdıkça 25'erli gösterir. API şu anda tüm
  kataloğu getirir; bu sunucu taraflı cursor pagination değildir. Klavye için Load More alternatifi vardır.
- Yeni bilgi alanları UI bileşen kataloğunda da gösterilir.

Tüm eski ekranların bu bileşenlere taşınması henüz tamamlanmadı. Yeni ekranlarda
yeni buton/panel/diyalog implementasyonu yerine yukarıdaki bileşenler kullanılmalı.
Yeni varyant gerektiğinde önce UI katalog ekranında ve responsive testlerde doğrulanmalı.
# Çalıştırma İzleme Standardı

Çalıştırma Geçmişi yalnızca izleme ekranıdır; yeni çalıştırma ilgili nesneden başlatılır. Sayfa başlığı Bağlantılar ile aynı ortak PageHeader bileşenini kullanır. Veri bölümü ayrı “Çalıştırma Listesi” başlığı taşır. Görünüm seçimleri filtre alanında seçim şerididir ve Sorgula ile uygulanır. Sabit Yenile düğmesi, 15 saniye başlangıç değerli düzenlenebilir aralık ve Sürekli Yenile anahtarı birlikte gösterilir. Geçersiz aralıkta otomatik yenileme yapılmaz. Deneme sütunu gösterilmez; okunan ve eklenen satırlar ayrı sütunlardır.
# Bağlantı Formu ve Nesne Düzenleyici

Bağlantı Bilgileri doğrudan düzenlenebilir tek formdur; ayrı “Bağlantıyı Düzenle” aşaması yoktur. Salt okunur yetkide alanlar devre dışıdır. Sil, Test ve Kaydet işlemleri formun sağ altındadır. Silme onayı ve bağımlılık engeli korunur. Şifre gösterilmez; yalnız tanım adı/açıklaması değiştiğinde yeniden istenmez. Adres/kimlik değişikliği test gerektirir.

Karttaki doğrulama rozeti son başarılı testin bilgisidir, canlı erişilebilirlik garantisi değildir. Oluşturan API'deki audit kaydından gelir; geçmişte kaydedilmemişse kullanıcı adı uydurulmaz. Nesne düzenleyicilerinin ortak başlık, grid ve düğme stilleri workbench-standard.css dosyasındadır; SQL yazı tipi ve diyagram koordinatları bundan etkilenmez.
