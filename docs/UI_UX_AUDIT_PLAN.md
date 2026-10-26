# UI/UX ekran tarama ve düzenleme planı

Tarih: 2026-10-09
Kapsam: Chrome üzerinde açık olan yerel uygulamanın ana ekranları, alt ekranları, güvenli görüntüleme akışları ve buton/sekme durumları.

## Durum özeti

- Frontend: `http://127.0.0.1:5173/`
- Backend: `http://127.0.0.1:8080/`
- Veritabanı: Docker PostgreSQL sağlıklı.
- Backend Flyway şeması onarıldı; 65 migration güncel ve actuator health `UP`.
- Repo graft grafiği `npx -y @nanonets/graft build` ile yenilendi; ilgili kaynak eşlemesi graf ve doğrudan dosya aralıklarıyla yapıldı.

## Ekran envanteri

İncelenen ana ekranlar:

- Genel Bakış
- Akış Tasarımı: kart/liste görünümleri, filtreler, yeni tanım formu, Veri Akışı tasarım sekmeleri
- Modeller: liste/kart, model oluşturma, model detayı, veri deposu detayı ve kolon kataloğu
- Çalıştırma Geçmişi: filtreler, sayfalama, çalıştırma detayı ve adım/SQL panelleri
- Yayınlar: liste, yayın detayı, onay kararı, prosedür hazırlığı ve fiziksel manifesto
- Zamanlamalar: liste ve uzun zamanlama oluşturma formu
- Bağlantılar: liste, bağlantı detayı, fiziksel şema sekmesi ve ekleme formu
- Mantıksal Şemalar
- Ortamlar
- Şema Metadata: tablo listesi ve kolon/kısıt/indeks/ilişki detayı

## Bulgular

| Öncelik | Alan | Gözlem | Etki |
| --- | --- | --- | --- |
| P0 | Akış Tasarımı / Mantıksal | Diyagram ve alt özellik paneli aynı sabit bölünmüş alana sıkışıyor. Kaynak/hedef kolonları aşağıda kaldığında sayfa kaydırması kullanıcıya doğal bir devamlılık vermiyor; özellik paneli de görünür alanı daraltıyor. | Kaynak eşleşmesi ve hedef kolonlara erişim zorlaşıyor. |
| P0 | Akış Tasarımı / Fiziksel | LKM tarafı geniş boşluklu, IKM tarafı ise yoğun seçenek ve geri alınamaz DDL uyarısıyla dolu. Seçenekler eşit görsel ağırlıkta değil ve alt kontroller ilk bakışta görünmüyor. Ayrıca KM option sözleşmesinde bulunmayan `DROP WORK TABLE`, `TRUNCATE TARGET` gibi davranış alanları fiziksel ekranda tekrar edilmemeli; bu davranışların sahibi KM’lerdir. | Fiziksel ayarların dengesi ve taranabilirliği bozuluyor; aynı davranış iki yerde tanımlanırsa sözleşme ile ekran arasında tutarsızlık oluşabilir. |
| P1 | Kolon Eşleşmeleri | Geniş eşleme tablosu yatay kaydırmaya ihtiyaç duyuyor; sağdaki hedef/aksiyon alanı ilk görünümde ekran dışında kalabiliyor. | Eşleme ve silme aksiyonlarının keşfedilebilirliği düşüyor. |
| P1 | Liste ekranları | Akış, bağlantı ve Şema Metadata tablolarında genişlik nedeniyle sağ kolonlar yatay taşma arkasında kalıyor. | Kullanıcı satır aksiyonlarına ulaşmak için ek kaydırma yapmak zorunda. |
| P1 | Şema Metadata detayları | Kolon detayında sıra başlığı ikonla birlikte `# #` olarak yineleniyor; ana katalog satırlarında tablo ikonu eksik ve `Sequence` başlığı Türkçe ekranda çevrilmemiş. Özet istatistikleri alt sorgular tamamlanmadan kısa süreliğine `0` gösterebiliyor. | Metadata sözlüğünde görsel hiyerarşi ve veri yüklenme durumu gereksiz biçimde belirsizleşiyor. |
| P1 | Model ve detay ekranları | Model detayında sol liste, ana tablo ve sağ reverse-engineering paneli ayrı kaydırma alanlarına sahip. Çalıştırma detayı ve metadata detayında da iç içe kaydırma kullanılıyor. | Hangi panelin kaydığı belirsizleşiyor; klavye ve mouse takibi zorlaşıyor. |
| P1 | Uzun formlar/modallar | Zamanlama oluşturma, bağlantı detayı, veri deposu detayı ve yayın manifestosu uzun içerikleri kendi içinde kaydırıyor. | Alt aksiyonlar ve doğrulama mesajları ekranın altında kalabiliyor. |
| P2 | Tutarlılık | İstatistik kartları, tablolar, modal başlıkları ve sekme rail'leri ekranlar arasında farklı yoğunlukta. | Kullanıcı zihinsel modeli ekranlar arasında yeterince taşınamıyor. |

## Buton ve aksiyon sınırı

Tüm ana menüler, alt sekmeler, kart/liste görünümleri, filtreler, sayfalama, güvenli aç/kapat düğmeleri, detay görüntüleri ve form iptal akışları Chrome üzerinden gezildi. `Çalıştır`, `Sil`, `Duraklat`, `Bağlantıyı Test Et`, metadata yenileme, `Kaydet/Oluştur` ve benzeri kalıcı veya dış sisteme dokunan aksiyonlar bilerek tetiklenmedi; bunların görünürlük ve erişilebilirlikleri kontrol edildi. Böylece veri/işletim durumunu değiştirmeden tam ekran taraması yapıldı.

## Uygulama planı

### P0 — Akış tasarımı

1. Mantıksal diyagramı, özellik paneli açıldığında bile tek bir kontrollü dikey akışta tut; diyagram içi görünürlüğü ve alt panel yüksekliğini viewport'a göre sınırla.
2. Kaynak ve hedef kolonlarının alt tarafta kalmasını önlemek için diyagram araç çubuğunu sabit görünür kıl ve diyagram için `min-height: 0`/`overflow` sınırlarını tek bir owner altında topla.
3. Fiziksel sekmede Kaynak → Staging → Hedef akışını koru; LKM, CKM ve IKM seçeneklerini dengeli kartlar halinde grupla. IKM risk uyarısını seçenek kartının içinde, açıklaması kısa ve belirgin biçimde göster.
4. Fiziksel sekme yalnızca seçili KM sürümünün gerçek option sözleşmesinden üretilen alanları göstermeli. `DROP WORK TABLE`, `TRUNCATE TARGET`, `WRITE MODE`, key kolonları ve benzeri alanlar seçili KM tarafından option olarak sunulmuyorsa bu ekranda statik/tekrar alan olarak eklenmemeli; davranış KM’nin kendi sözleşmesinden okunmalı.
5. Fiziksel ayarların sonundaki kontrollerin ulaşılabilir olduğunu masaüstü ve dar viewport'ta doğrula.

### P1 — Kaydırma ve taşma

1. Her ekran için birincil kaydırma alanını belirle; ikincil kaydırmayı yalnızca tablo, kod/manifesto veya gerçekten bağımsız panel gerektiğinde kullan.
2. Geniş tablolarda yatay kaydırma çubuğunu görünür ve aksiyon kolonunu sabit/erişilebilir hale getir.
3. Modal ve drawer gövdelerinde başlık/aksiyon footer'ını sabitle; yalnızca içerik gövdesini kaydır.

### P2 — Tutarlılık ve doğrulama

1. Sekme, kart, tablo ve boş durumlarının aynı yoğunluk/boşluk ölçeğini kullanmasını sağla.
2. Tüm ana ekranları 1280px, 1024px ve dar ekran genişliklerinde kontrol et.
3. Klavye ile sekme, yatay kaydırma, modal kapatma ve tablo satır aksiyonlarını doğrula.

## İlk düzenleme dilimi

İlk kod değişikliği P0 kapsamındaki iki somut şikâyete odaklanmalıdır:

- `MappingGrid`/`definitions.css`: diyagram ve özellik panelinin yükseklik/scroll sınırlarını netleştirmek.
- `MappingKmOptions`/`definitions.css`: fiziksel seçenek gruplarını dengeli kolonlara almak, seçenek içeriklerinin taşmasını önlemek ve risk uyarısını yoğun karttan ayırmak.

Sonraki dilimde liste tabloları ile modal gövdeleri ortak bir overflow standardına taşınmalıdır.

## Bu turda uygulanan ve doğrulananlar

- `MappingGrid` çevresindeki genel `overflow: hidden` etkisi kaldırıldı; diyagram/özellik paneli viewport'a göre kontrollü büyüyor ve ana sayfa kaydırmasıyla diyagramın altına inilebiliyor.
- Fiziksel seçenek kartları yalnızca seçili modül sayısına göre `auto-fit` kolonlanıyor. CKM seçili değilken LKM ve IKM artık boş üçüncü kolona sıkışmıyor.
- KM kartlarının iç alanları iki kolonlu, içerik yüksekliğine duyarlı ve uzun IKM risk uyarısı ayrı satırda olacak şekilde düzenlendi.
- Mevcut `MappingKmOptions` renderer’ı alanları `selected.options` üzerinden üretiyor; yeni kural, bu dinamik sözleşmenin dışına statik fiziksel davranış alanları eklenmemesi olarak plana işlendi.
- Geniş `ui-data-grid` tablolarında sağ aksiyon kolonları sticky hale getirildi; Şema Metadata ekranında yatay veri alanı genişken satır göz aksiyonlarının sağ kenarda erişilebilir kaldığı Chrome’da doğrulandı.
- Kolon Eşleşmeleri tablosunun kaldırma/aksiyon kolonu da `ui-grid-actions-column` ile sticky hale getirildi; tablo gövdesi yatay kayarken sayfalama tablo dışında ve erişilebilir kalıyor.
- Uzun modal formlarında içerik gövdesi kayarken form footer'ı sticky hale getirildi; kaydet/vazgeç gibi alt aksiyonlar uzun içerikte görünür kalıyor.
- Kurumsal footer, ortak `InnovaLogo` bileşenini kullanacak şekilde tamamlandı; login kalite testinin beklediği erişilebilir kurumsal işaret eklendi.
- İkinci metadata taramasında kolon başlığındaki yinelenen `#` kaldırıldı, ana katalog satırlarına renkli tablo ikonu eklendi ve `Sequence` başlığı Türkçeleştirildi.
- Şema Metadata özetinde kolon/kısıt/indeks/ilişki sayıları alt sorgular bitene kadar `…` gösteriyor; geçici `0` ile gerçek sıfır ayrıştırıldı.
- Chrome doğrulaması: Mantıksal diyagramda aşağı kaydırma ile alt kolonlara ulaşıldı; Fiziksel sekmede alt seçenekler ve uyarı görünür biçimde kaydırılarak kontrol edildi.
- Chrome metadata doğrulaması: ana katalog ikonları, sticky sağ aksiyonları, kolon/kısıt sekmelerindeki renkli başlık ikonları ve zorunlu/benzersiz durum etiketleri kontrol edildi.
- `npm run lint`, `npm run build`, mapping/brand testleri ve güncel tam frontend suite başarılı; 108 dosya / 407 test geçti. Güncel Playwright mapping testi de başarılı.
- Tam ekran Playwright kalite turu kimlik bilgisi ortam değişkenleri (`AKIS_E2E_USERNAME`, `AKIS_E2E_PASSWORD`) verilmediği için login sonrasında ilerleyemedi; bu uygulama hatası değil, test ortamı önkoşuludur.
- Backend `/actuator/health` `UP`, frontend kök URL'si HTTP 200 döndürüyor.
- Son değişikliklerden sonra repo grafiği `npx -y @nanonets/graft build` ile yenilendi: 902 dosya, 8.806 düğüm ve 28.296 kenar.

## Kabul kontrol listesi

- [x] Mantıksal sekmede kaynak ve hedef kolonları görünür; sayfa aşağı kaydırılarak alt kolonlara ulaşılabiliyor.
- [x] Kolon Eşleşmeleri ekranında tablo ve alt sayfalama yatay/dikey taşmadan kullanılabiliyor.
- [x] Fiziksel sekmede seçili LKM/IKM kartları dengeli; kritik uyarı görünür ve alt seçeneklere kaydırmayla ulaşılabiliyor.
- [x] Fiziksel sekmede KM sözleşmesinde olmayan `DROP WORK TABLE`, `TRUNCATE TARGET` ve benzeri alanlar gösterilmiyor; görünen her alan seçili KM option’ından geliyor.
- [x] Akış, bağlantı ve metadata tablolarında sağ aksiyonlara yatay kaydırma ile ulaşılabiliyor.
- [x] Model, çalıştırma, metadata ve uzun form modallarında gövde kaydırılırken alt form aksiyonları görünür kalıyor.
- [x] Şema Metadata katalog ve detay ekranlarında ikonlar anlamına göre renkli, başlıklar tekrarsız ve Türkçe/İngilizce metinler tutarlı.
- [x] Şema Metadata özet kartları, alt istatistikler yüklenmeden yanıltıcı `0` göstermiyor.
- [x] Frontend build/lint/test komutları başarılı.
- [x] Backend `/actuator/health` sonucu `UP`.
