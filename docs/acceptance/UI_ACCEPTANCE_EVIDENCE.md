# AKIŞ UI kabul kanıtı

Bu kayıt, `AKIS_NIHAI_UI_UX_VE_BILGI_MIMARISI_RAPORU.md` içindeki AC52–AC60 kapanış kapılarını izler. Otomatik kontrol ile insan gözlemi birbirine karıştırılmaz.

## Otomatik kapılar

| Kriter | Durum | Kanıt |
|---|---|---|
| AC52 — Klavye ve odak | Geçti | Dialog odak tuzağı/odak dönüşü testleri; proje ağacı menülerinde ok, Home, End ve Escape klavye testleri; ortak `:focus-visible` sözleşmesi. |
| AC53 — Kontrast, renk, hedef alanı | Geçti | Light/dark ana metin, ikincil metin, birincil eylem ve hata renkleri için WCAG AA oran testi; durum rozetleri metin taşır; coarse pointer hedefleri en az 44 px. |
| AC54 — Responsive ve zoom | Viewport matrisi geçti; gerçek %200/%400 zoom gözlemi bekliyor | 820 px kompakt kabuk, tek kolon feature kırılımları, yatay tablo kapsayıcıları ve paket için grafik dışı erişilebilir liste testle korunur. Gerçek tarayıcıda 320/768/1920 px ölçümlerinde `scrollWidth = clientWidth`; 768 px ölçümünde bulunan yatay taşma düzeltilip yeniden doğrulandı. |
| AC55 — Çeviri kapsamı | Geçti | EN/TR anahtar kümeleri birebir eşit; prosedür, paket, mapping, model ve çalıştırma kodları kullanıcı etiketine çevrilir; bilinmeyen kod okunur metne dönüştürülür, saklanan kod değişmez. |
| AC56 — Eski URL’ler | Geçti | `/topology`, `/runs`, `/runs/:uuid` ve eski definition query bağlantıları için kanonik yönlendirme testleri. |
| AC57 — Rol görünümü | Geçti | Operasyon rolünde Operasyonlar ilk sırada; yazma eylemleri gizli. Backend proje erişim profili ve doğrudan `TANIM_*` izin kontrolleri birim ve temiz PostgreSQL entegrasyon testleriyle doğrulandı. |
| AC59 — Performans fixture’ları | Geçti | 10.000 klasör, 10.000 prosedür adımı, 1.000 mapping satırı ve 500 paket düğümü için 20 örnekli p95 testi. O(n²) kök-klasör ekleme yolu kaldırıldı. 2026-09-12 son tam-suite ölçümü: 8,68 ms / 0,35 ms / 0,24 ms / 0,65 ms; bütçeler sırasıyla 300 / 200 / 100 / 2000 ms. |
| AC60 — Kapsam ve regresyon | Geçti | Frontend lint, production build ve tüm testler; backend birim testleri, 11 temiz-veritabanı grubu ve API smoke testi. Drawer yalnız tüketici taraması sıfır sonuç verdikten sonra kaldırıldı; değişmez sürüm/hash/pin verileri değişmedi. |

Performans rakamları bu makinedeki jsdom istemci-projeksiyon ölçümleridir; ağ veya sunucu p95 iddiası değildir. Tekrar komutu: `cd frontend; npm test -- --run src/acceptance/performance.test.ts`.

## AC58 — 5 saniyelik yön bulma deneyi

Bu kapı otomasyonla veya geliştirici kanaatiyle geçirilmez. Ürünü geliştirmeyen 12 kişi gerekir: 6 veri geliştirme, 6 operasyon deneyimli. Her katılımcıya ilgili ekran 5 saniye gösterilir, ekran kapatılır ve şu dört soru sorulur:

1. Hangi projedesiniz?
2. Hangi nesne veya çalışma açık?
3. Buradaki birincil eylem nedir?
4. Üretim ya da yıkıcı bir işlem riski var mı?

Kabul: en az 10/12 kişi proje ile birincil eylemi doğru söyler ve hiçbir katılımcı üretim ekranını test ortamı sanmaz. Üretim/test karışıklığında tasarım düzeltilip deney tekrarlanır.

| Katılımcı | Profil | Proje doğru | Nesne/iş doğru | Birincil eylem doğru | Üretim riski doğru | Not |
|---|---|---:|---:|---:|---:|---|
| 01 | Veri geliştirme |  |  |  |  |  |
| 02 | Veri geliştirme |  |  |  |  |  |
| 03 | Veri geliştirme |  |  |  |  |  |
| 04 | Veri geliştirme |  |  |  |  |  |
| 05 | Veri geliştirme |  |  |  |  |  |
| 06 | Veri geliştirme |  |  |  |  |  |
| 07 | Operasyon |  |  |  |  |  |
| 08 | Operasyon |  |  |  |  |  |
| 09 | Operasyon |  |  |  |  |  |
| 10 | Operasyon |  |  |  |  |  |
| 11 | Operasyon |  |  |  |  |  |
| 12 | Operasyon |  |  |  |  |  |

## Görsel matris kaydı

| Dil | Tema | Genişlik / zoom | Geliştirme | Bağlantılar | Operasyon | Sonuç / bulgu |
|---|---|---|---|---|---|---|
| TR | Light | 320 px / %100 | Proje girişi görüldü | Filtreler tek kolon, alt navigasyon görünür | — | `320 = 320`; genel yatay taşma yok. |
| EN | Dark | 768 px / %100 | Kompakt navigasyon ve Tanımlar görüldü | — | Liste/filtre/uyarı görüldü | İlk ölçüm `830 > 753` idi; breakpoint düzeltmesi sonrası `768 = 768`. |
| TR | Light | 1920 px / %100 | — | — | Liste, filtreler ve sol navigasyon görüldü | `1920 = 1920`; genel yatay taşma yok. |
| EN/TR | Light/Dark | %200 / %400 |  |  |  | In-app tarayıcı zoom kısayolu ölçülebilir zoom değeri üretmedi; gerçek tarayıcı/ekran okuyucu oturumunda doldurulacak. |

AC58 ve gerçek %200/%400 zoom satırı gerçek katılımcı/cihaz gözlemi girilmeden “geçti” olarak işaretlenmemelidir.
