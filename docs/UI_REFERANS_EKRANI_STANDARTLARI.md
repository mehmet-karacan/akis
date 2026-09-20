# AKIŞ UI referans sözleşmesi

## Schema Metadata Dictionary ekranı

Bu belge, `/schema-metadata` ekranını AKIŞ’ın kanonik katalog ekranı olarak
tanımlar. Yeni ekranlarda bu yapı korunur; yalnızca ekranın verisi, etiketleri,
semantic nesne ikonları ve nesneye özel detay alanları değişir.

Bu belge görsel bir öneri değil, uygulanacak ekran sözleşmesidir.

---

## 1. Referans ekranın amacı

Referans ekran aşağıdaki sorulara tek bakışta cevap verir:

- Kullanıcı hangi veri alanındadır?
- Hangi kayıtlar bulunmaktadır?
- Kayıtların ana kimliği nedir?
- Kayıtla ilgili özet sayaçlar nelerdir?
- Kayıt hangi ortak metadata/audit bilgilerine sahiptir?
- Kayıt detayına nasıl girilir?
- Kayıt kart, liste ve tablo görünümünde aynı bilgiyi koruyor mu?

Yeni ekranlar bu bilgi mimarisini bozmaz. Veri alanları farklı olabilir; bilgi
hiyerarşisi ve etkileşim modeli farklılaştırılamaz.

---

## 2. Zorunlu sayfa hiyerarşisi

Her katalog/yönetim ekranı aşağıdaki sırayı kullanır:

```text
PageHeader
  eyebrow
  semantic page icon + title
  short description
  optional right-side context/action

QueryFilter / FilterSection

SummaryStrip
  semantic summary cards

DataGrid / named record surface
  collection title + collection icon
  view label + Card/List/Table toggle
  records

RecordDetailDialog (only after View action)
```

Bu bölümlerin sırası değiştirilmez. Bir bölüm kullanılmıyorsa boş alan
bırakılmaz; kayıt yüzeyi bir önceki bölümün ortak boşluğuyla başlar.

### 2.1 PageHeader

`PageHeader` ortak bileşeni kullanılmalıdır.

- `eyebrow`: ekranın üst bağlamı; metadata’da `Data Dictionary`.
- `icon`: ekranın ana semantic nesnesini anlatan ikon; metadata’da
  `BookOpenText`.
- `title`: tek ve açık ekran adı; metadata’da `Schema Metadata Dictionary`.
- `description`: ekranın amacını tek kısa cümlede anlatır.
- Sağ aksiyon/context alanı yalnız ekran bağlamı seçimi veya ana aksiyon için
  kullanılır; metadata’da şema seçimi bulunur.
- Başlık ikonu ve metin aynı satırda, başlık ölçüsünde görünür.
- İkon başlıkta bir kez gösterilir; aynı bağlam ikonu içerikte gereksiz yere
  tekrar edilmez.

### 2.2 FilterSection

Filtre bölümü `FilterSection` ve `QueryFilter` ortak bileşenleriyle kurulmalıdır.

- Filtre alanı ayrı yüzeydir.
- Bölüm başlığında filtre ikonu bulunur.
- Filtreler varsayılan olarak açılıp kapanabilir.
- Arama etiketi ikon + metin biçimindedir; metadata’da `Search` + `Name or Code`.
- Arama yazılırken sonuçlar otomatik değişmez.
- `Search/Sorgula` ile filtre uygulanır.
- `Clear/Temizle` yazılan ve uygulanmış filtreyi temizler.
- Form submit Enter ile de çalışır.
- Filtre alanı geniş ekranda kullanılabilir genişliği doldurur.
- Mobilde alanlar tek kolona iner.

Birden fazla filtre aynı filtre yüzeyinde gruplanır. Her filtre etiketi üstte,
kontrolü altında hizalanır; yatay etiketli eski form düzeni kullanılmaz.

---

## 3. Global semantic nesne sistemi

Renk, bulunduğu karta veya kolona değil, temsil edilen nesneye aittir. Aynı
nesne nerede gösterilirse gösterilsin aynı renk ailesini kullanır:

| Semantic nesne/rol | Renk ailesi | Örnek ikon ailesi |
| --- | --- | --- |
| Tablo / ana kayıt | Mavi / info | `Table2` |
| Kolon | Turkuaz / teal | `Columns3`, `Type` |
| Kısıt | Amber / warning | `KeyRound`, `ShieldCheck` |
| İndeks | Mor / violet | `ListTree` |
| İlişki | Pembe | `Share2` |
| Sequence | Yeşil / success | `ListOrdered` |
| Açıklama | Soft gri-mavi | `FileText` |
| Bilgi/ölçüm | Mavi / info | `Hash`, `Ruler`, `RefreshCw` |
| Başarılı/doğrulanmış durum | Yeşil | `Check`, `BadgeCheck` |
| Uyarı/zorunluluk | Amber | `ShieldCheck`, `CircleAlert` |
| Silme/tehlike | Kırmızı | `Trash2`, `ShieldAlert` |
| Görüntüleme | Mavi | `Eye` |
| Düzenleme | Mavi veya amber | `Pencil` |
| Boş/pasif/bilinmeyen değer | Nötr gri | `X` veya nötr ikon |

### 3.1 Renk uygulama kuralları

- Renk tek başına anlam taşımaz; metin/etiket ve ikon birlikte bulunur.
- Büyük yüzeyler doygun renkle boyanmaz; renk ikon, üst çizgi, etiket, border ve
  odak vurgusunda kullanılır.
- Açık ve koyu temada semantic renk ailesi korunur; yalnızca kontrast için ton
  değişebilir.
- Ekran özelinde yeni hex renk üretilmez.
- `--schema-color-*` gibi geçici ekran tokenları global semantic tokenlara
  taşınmalıdır: `--color-info`, `--color-teal`, `--color-warning`,
  `--color-violet`, `--color-pink`, `--color-success`, `--color-danger`.
- Ant Design tokenı ile uygulama semantic tokenı çakışırsa semantic nesne
  anlamı önceliklidir ve tema üzerinden çözülür.

### 3.2 Metadata ekranının kesin renk eşleşmesi

- Tables: blue/info.
- Total Columns: teal.
- Constraints: amber.
- Indexes: violet.
- Relationships: pink.
- Sequences: success/green.
- `Description`: soft secondary.
- `Created By`: success/green.
- `Created At`: info/blue.
- `Updated By`: violet/neutral.
- `Updated At`: amber.
- `View`: blue.

Yeni katalogda nesne isimleri değişebilir; renk rolleri değişmez. Örneğin
bağlantı nesnesi bağlantı rengiyle, fiziksel şema teal, mantıksal şema violet
ailesiyle temsil edilir.

---

## 4. İkon sözleşmesi

### 4.1 Temel ilkeler

- Her başlık, etiket ve durum anlamına uygun ikon taşır.
- İkon metnin yerine geçmez; erişilebilir metin korunur.
- Aynı semantik bilgiyi anlatan ikon aynı alanda iki kez gösterilmez.
- Ortak bileşen ikon ekliyorsa ekran satır içi aynı ikonu yeniden eklemez.
- İkon rengi temsil ettiği semantic nesnenin rengiyle aynıdır.
- Başlık ikonu, alan ikonu, durum ikonu ve aksiyon ikonu birbirinden ayrıdır.
- Süs amaçlı ikon kullanılmaz.
- İkonların `aria-hidden` veya uygun `aria-label/title` davranışı bulunur.

### 4.2 Referans ikon haritası

| Alan | İkon |
| --- | --- |
| Sayfa | `BookOpenText` |
| Tablo kataloğu | `Table2` |
| Tablo adı | `Table2` |
| Kolonlar | `Columns3` |
| Kısıtlar | `KeyRound` |
| İndeksler | `ListTree` |
| İlişkiler | `Share2` |
| Sequence | `ListOrdered` |
| Açıklama | `FileText` |
| Kolon sıra numarası | `Hash` |
| Kolon adı | `Type` |
| Veri tipi | `Braces` |
| Uzunluk | `Ruler` |
| Zorunlu | `ShieldCheck` |
| Varsayılan | `RefreshCw` |
| Benzersiz | `BadgeCheck` |
| CHECK ifadesi | `Code2` |
| Hedef tablo | `Table2` |
| Silme kuralı | `Trash2` |
| Güncelleme kuralı | `RefreshCw` |
| Arama | `Search` |
| Görünüm | `Eye` + `ViewToggle` |
| Görüntüle | `Eye` |
| Düzenle | `Pencil` |
| Başarılı | `Check`, `CheckCircle2` |
| Olumsuz/boş | `X`, `CircleAlert` |

---

## 5. Özet kart standardı

`SummaryStrip` ve `SummaryMetric` kullanılmalıdır.

- Her kart tek bir toplamı/ölçümü temsil eder.
- İkon, etiket ve değer ayrı alanlardır.
- İkon semantic nesnenin rengindedir.
- Kartın üst çizgisi aynı semantic renktedir.
- Etiket kısa ve standarttır; değer görsel olarak öne çıkar.
- Kartlar geniş ekranda eşit grid kolonlarına bölünür.
- Kartlar dar ekranda sırayla alt satıra iner; metin aşağı taşmaz.
- Aynı kart içinde aynı değer farklı etiketlerle tekrarlanmaz.
- Bilinmeyen değer `0` olarak uydurulmaz.
- Özet kartları kayıt yüzeyiyle aynı dış kenar hizasını paylaşır.

Metadata referansında masaüstü genişlikte 6 kart vardır. Bu sayı veri alanına
bağlıdır; yeni ekran 4, 5 veya 6 karta sahip olabilir, ancak grid, yükseklik,
padding ve renk standardı değişmez.

---

## 6. Kayıt yüzeyi ve DataGrid

Kayıt yüzeyi `DataGrid` ortak bileşenidir. Yeni kataloglar kendi kart, liste ve
tablo renderer’ını yazmaz.

```tsx
<DataGrid
  collectionTitle="..."
  collectionIcon={<SemanticIcon />}
  auditKind="..."
  view={view}
  onViewChange={setView}
>
  <thead>...</thead>
  <tbody>...</tbody>
</DataGrid>
```

### 6.1 Ortak kayıt sözleşmesi

Her kayıt aynı kolon/alan sırasını korur:

1. Ana kimlik
2. Açıklama veya kısa tanım
3. Ana içerik alanları
4. Sayaçlar/durumlar
5. Bağlı nesne/sequence gibi teknik bilgiler
6. Kayıt audit bilgileri
7. En sağda aksiyon

Ekran verisi bu sıralamaya uymuyorsa veri adapter’ı hazırlanır; görünüm
bileşeni istisna olarak değiştirilmez.

### 6.2 Katalog başlığı

- Kayıt yüzeyinin bir adı bulunur; metadata’da `Table Catalog`.
- Katalog başlığında semantic koleksiyon ikonu bulunur.
- Başlık ile View kontrolü aynı toolbar’da bulunur.
- View etiketi `Eye` ikonuyla gösterilir.
- `Cards/List/Table` seçimi ortak `ViewToggle` kullanır.
- Dışarıdan bir görünüm seçici veriliyorsa DataGrid içindeki ikinci seçici
  gizlenir.

### 6.3 Kart görünümü

- Kayıt kartı `RecordCard` ile oluşturulur.
- Desktop kart grid’i metadata referansındaki kırılımları izler: geniş alanda
  4, orta alanda 3/2, dar alanda 1 kolon.
- Kart dışı soft border ve hafif gölge taşır.
- Kart başlığı sabit bir yatay bölümdür.
- Ana kayıt adı başlıkta tek semantic ikonla gösterilir.
- Öne çıkarılmış teknik bilgi varsa başlığın sağında gösterilir; metadata’da
  sequence bu alandadır.
- Kart gövdesindeki alanlar soft border, hafif radius ve `surface-subtle`
  yüzeyle birbirinden ayrılır.
- Açıklama gibi geniş alanlar gerektiğinde tam satır kaplar.
- Sayaç alanları eşit grid kolonlarına hizalanır.
- Hover yalnız border/gölgeyi hafifçe değiştirir.
- Kayıt bilgileri kartın alt bölümünde ayrı ve kompakt bir bloktur.
- Aksiyon alanı alt border ile ayrılır ve sağa hizalanır.

### 6.4 Liste görünümü

- Liste tek geniş kayıt yüzeyi olarak gösterilir.
- Karttaki alan sırası korunur.
- Aynı etiketler ve ikonlar kullanılır; yalnızca kolonlar yatay hizalanır.
- Kayıt adı ve ana kimlik solda, aksiyon en sağda bulunur.
- Audit alanları genişlik uygunsa normal okunabilirlikte gösterilir.
- Liste gereksiz boşlukla ekranı uzatmaz; fakat her kaydı ayırt edecek border
  veya satır ayrımı bulunur.

### 6.5 Tablo görünümü

- Her `th` ikon + etiket biçimindedir.
- Kart/liste ile aynı alanlar ve aynı sıra kullanılır.
- Kimlik alanı soldadır.
- Numeric sayaçlar sağa veya ortaya hizalanır.
- Teknik identifier değerlerinde gerekirse monospace kullanılır.
- Aksiyon kolonu en sağda bulunur.
- Tablo kendi yüzeyinde yatay kaydırılır; sayfanın tamamı yatay taşmaz.
- Tablo satırına tıklama detay açmaz; yalnızca View aksiyonu açar.

---

## 7. Kayıt bilgileri / audit standardı

Kayıt bilgileri metadata ekranında aşağıdaki dört ayrı alandır:

- Created By / Oluşturan
- Created At / Oluşturulma Zamanı
- Updated By / Güncelleyen
- Updated At / Güncellenme Zamanı

Bu alanlar tek büyük metin veya tek kolon halinde birleştirilmez.

- Kartta kompakt, dört kolonlu küçük metadata bloğudur.
- Liste ve tabloda genişlik uygunsa normal okunabilirlikte gösterilir.
- Her alanın kendi ikon rengi vardır.
- Eksik değer için sabit boş durum metni kullanılır: Türkçe `Yok`, İngilizce
  `Not available` veya ürünün belirlediği ortak karşılık.
- Yapay zekâ tarafından üretilmiş rastgele tireler kullanılmaz.
- Geçmiş kullanıcı bilgisi uydurulmaz.
- Audit bilgisi yalnız gerçek audit kaydından veya güvenilir kaynak alanından
  gelir.
- Türetilmiş alt kayıtlar için sahte audit bilgisi üretilmez.

Ortak bileşenler: `useRecordAudit`, `RecordAuditFields`,
`recordAuditPresentation` ve DataGrid’in `auditKind` standardı.

---

## 8. Aksiyon ve etkileşim standardı

### 8.1 Görüntüleme

- `RecordActionButton` düzenlenemeyen kayıtta `Eye` ikonuyla `View/Görüntüle`
  action’ını gösterir.
- Metadata ekranında detay dialogu yalnızca bu butonla açılır.
- Kartın, listenin veya tablonun gövdesine tıklamak detay açmaz.
- Görüntüleme butonu aksiyon kolonunda/kart footer’ında sağda bulunur.

### 8.2 Düzenleme

- Düzenlenebilir kayıtta aynı ortak buton `Pencil` ikonuyla `Edit/Düzenle`
  anlamını taşır.
- Düzenleme ile görüntüleme birbirinin yerine kullanılmaz.
- Silme `Trash2` + danger tone + onay akışı gerektirir.

### 8.3 Detay dialogu

- `RecordDetailDialog` / ortak `Dialog` kullanılır.
- Dialog başlığında kayıt türünün semantic ikonu ve kayıt adı bulunur.
- Dialog merkezi açılır; sağdan özel panel/drawer tasarlanmaz.
- Dialog gövdesi gerektiğinde kaydırılır.
- Escape kapatır.
- Odak tetikleyiciye geri döner.
- Arka plan etkileşime kapanır.
- Dialog genişliği içeriğin bilgi yoğunluğuna göre belirlenir; metadata detay
  paneli geniş tablo içerdiği için geniş modal kullanır.

---

## 9. Metadata detay paneli referansı

Metadata detay paneli yeni detay ekranları için içerik standardıdır.

### 9.1 Panel başlığı ve açıklama

- Panel başlığı `Table2` + tablo adıdır.
- Başlık ikonunun rengi tablo semantic rengidir.
- Açıklama `FileText` + metin şeklindedir.
- Açıklama ikincil gri-mavi renktedir.

### 9.2 Sekmeler

Sekmeler aynı veri türünün alt bölümleridir ve kendi semantic ikonlarını taşır:

- Columns / Kolonlar: `Columns3`, teal.
- Constraints / Kısıtlar: `KeyRound`, amber.
- Indexes / İndeksler: `ListTree`, violet.
- Relationships / İlişkiler: `Share2`, pink.

Sekme etiketinde kayıt sayısı parantez içinde gösterilebilir. Sekme ikonları
sekme başlığı ve içerik kolonlarıyla aynı renkte olur.

### 9.3 Detay tablo kolonları

Detay tablolarında her kolon başlığı `MetadataColumnTitle` standardını kullanır:

- # → `Hash`, info.
- Name → `Type`, teal.
- Type → `Braces`, violet.
- Length → `Ruler`, info.
- Required → `ShieldCheck`, amber.
- Default → `RefreshCw`, info.
- Sequence → `ListOrdered`, success.
- Description → `FileText`, secondary.
- Constraint name → `KeyRound`, amber.
- Constraint type → `Tags`, amber.
- CHECK expression → `Code2`, amber.
- Index name → `ListTree`, violet.
- Unique → `BadgeCheck`, success.
- Source constraint → `KeyRound`, amber.
- Target table → `Table2`, blue.
- Target constraint → `KeyRound`, amber.
- ON DELETE → `Trash2`, danger.
- ON UPDATE → `RefreshCw`, info.

### 9.4 Durum etiketleri

- Required = Yes/Evet: amber + `Check`.
- Required = No/Hayır: subdued + `X`.
- Constraint type: amber + `KeyRound`.
- Unique = Yes/Evet: success + `Check`.
- Unique = No/Hayır: subdued + `X`.
- Sequence eşleşmesi: success + `ListOrdered`.
- Boş değer: nötr `—` veya ortak boş değer; yapay tire üretilmez.

### 9.5 Sequence eşleşmesi

Sequence adı yalnızca kesin tablo + kolon eşleşmesiyle gösterilir:

```text
{tableName}_{columnName}_seq
```

Benzer veya başka tabloya ait sequence gösterilmez. Kart başlığındaki sequence,
tabloya ait kullanılan sequence bilgisidir; detay kolonunda ise kolonun gerçek
sequence eşleşmesi gösterilir.

---

## 10. Responsive standardı

### Geniş desktop

- Sayfa kullanılabilir genişliğin tamamını kullanır.
- Özet kartları eşit grid kolonlarındadır.
- Metadata referansında katalog kartları 4 kolon, özetler 6 kolondur.
- Kayıt kartı alanları 4 kolonlu grid kullanır.
- Audit alanları dört küçük kolona ayrılır.

### Orta genişlik

- Özet kartları 3/2 kolona iner.
- Katalog kartları 3 veya 2 kolona iner.
- Kayıt alanları 2 kolona iner.
- Tablo yatay olarak kendi yüzeyinde kayar.

### Mobil

- Sayfa bölümleri tek kolona iner.
- Özet kartları tek kolona iner.
- Katalog kartları tek kolona iner.
- Alan kutuları tek kolona iner.
- Audit bilgileri iki küçük kolonda kalabilir; okunmazsa tek kolona iner.
- Dialog ekran genişliğini güvenli kenar boşluklarıyla kullanır.
- Sekme çubuğu yatay kaydırılabilir.
- Kontroller dokunma için yeterli hedef alanını korur.

Hiçbir responsive kırılımda başlık, ikon, değer veya aksiyon birbirinin üzerine
binmez. Uzun identifier değerleri taşabilir veya ellipsis kullanabilir, fakat
layout’u genişletmez.

---

## 11. Tema ve kontrast

- Açık ve koyu tema aynı semantic renk sözleşmesini kullanır.
- Koyu temada `surface`, `surface-subtle`, `line`, `line-strong`, `ink` ve
  `ink-soft` tema tokenları kullanılır.
- Hardcoded beyaz, siyah veya açık tema rengi koyu temada kullanılmaz.
- İkon görünürlüğü uygun kontrast ve yüzeyle sağlanır.
- Tag, border ve ikon aynı semantic rengin farklı yoğunluklarını kullanabilir.
- Hover, focus ve selected durumları ana nesnenin rengini korur.

Her yeni ekran en az şu iki tema ve üç görünüm kombinasyonunda kontrol edilir:

```text
light + card/list/table
dark  + card/list/table
```

---

## 12. Veri ve boş durum standardı

- Loading, error ve empty durumları ortak `AsyncState` kullanır.
- Loading ekranı kaydı varmış gibi sahte veri göstermez.
- Error ekranında tekrar deneme eylemi bulunur.
- Empty ekranı açıklayıcı metin ve gerekiyorsa yönlendirici aksiyon taşır.
- Bilinmeyen sayaç `0` gösterilerek gizlenmez.
- `null` ve boş değerler ürünün ortak boş değer sözleşmesini kullanır.
- API’den gelmeyen kullanıcı, tarih, sequence veya durum bilgisi uydurulmaz.

---

## 13. Ortak bileşen kullanım listesi

Yeni katalog ekranları mümkün olduğu kadar aşağıdaki bileşenleri kullanır:

- `PageHeader`
- `FilterSection`
- `QueryFilter`
- `SummaryStrip`
- `DataGrid`
- `ViewToggle`
- `RecordCard`
- `RecordFieldIcon`
- `RecordActionButton`
- `RecordAuditFields`
- `useRecordAudit`
- `RecordDetailDialog`
- `Dialog`
- `AsyncState`
- ortak `Button`

Yeni ekran özel `Cards`, `List` ve `Table` renderer’ları yazmaz. Veri farkı için
adapter/presentation fonksiyonu yazılır; görünüm farkı için ortak bileşen
özellikleri kullanılır.

---

## 14. Kabul kontrol listesi

### Yapı

- [ ] PageHeader, filter, summary ve records sırası doğru.
- [ ] Sayfa yatay alanı dolduruyor.
- [ ] Kayıt yüzeyi adlandırılmış ve ikonu var.
- [ ] Kart/liste/tablo aynı veri alanlarını gösteriyor.

### İkon ve renk

- [ ] Her ikon metniyle anlamsal olarak uyumlu.
- [ ] Eksik ikon yok.
- [ ] Aynı ikon gereksiz tekrar edilmiyor.
- [ ] Nesnenin rengi tüm görünümlerde aynı.
- [ ] Açık tema kontrol edildi.
- [ ] Koyu tema kontrol edildi.

### Kayıt ve aksiyon

- [ ] Ana kimlik açıkça görünüyor.
- [ ] Açıklama ve teknik alanlar ayrılmış.
- [ ] Kayıt bilgileri dört ayrı alanda.
- [ ] Boş değerler ortak metinle gösteriliyor.
- [ ] View yalnızca görüntüle butonuyla açılıyor.
- [ ] Edit ve View ikon/etiket olarak ayrılmış.
- [ ] Aksiyonlar sağda.

### Responsive

- [ ] Geniş desktop.
- [ ] Orta desktop/tablet.
- [ ] Mobil genişlik.
- [ ] Uzun metin ve identifier.
- [ ] Tablo yatay kaydırma.
- [ ] Dialog ve sekme taşması.

### Teknik doğrulama

- [ ] Hedefli component testleri geçti.
- [ ] İlgili ekran testleri geçti.
- [ ] Production build geçti.
- [ ] Canlı tarayıcıda veri yükleme kontrol edildi.
- [ ] Console error yok.
- [ ] Görüntüleme, boş, hata ve loading durumları kontrol edildi.

---

## 15. Uygulama kararı

Yeni bir ekran için varsayılan yaklaşım:

1. Ekranın semantic nesneleri ve veri adapter’ı tanımlanır.
2. Metadata ekranındaki ortak sayfa iskeleti aynen kullanılır.
3. Aynı `DataGrid` kolon sözleşmesiyle kart/liste/tablo üretilir.
4. Nesneye özel ikonlar global semantic renk ailesine bağlanır.
5. Audit, boş durum, aksiyon ve dialog kuralları değiştirilmez.
6. Açık/koyu tema ve responsive matris doğrulanır.
7. Test, build ve canlı ekran kontrolü tamamlanmadan iş bitmiş sayılmaz.

Özetle: yeni ekranda değişebilecek şeyler kayıt verisi, etiketler, semantic
ikonlar ve nesneye özel detay içerikleridir. Sayfa hiyerarşisi, görünüm seçimi,
kayıt bilgileri, aksiyon davranışı, renk mantığı, responsive düzen ve tema
kuralları ortak kalır.
