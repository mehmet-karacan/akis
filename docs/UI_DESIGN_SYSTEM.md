# Akış UI standardı: Ant Design

## Karar

Ant Design 6.6.4 (MIT) kullanılır. Ücretli tema veya eklenti yoktur. Sürüm ve
kilit dosyası depoda tutulur; üçüncü taraf lisans bildirimleri korunur.

## Tek kaynak

- `frontend/src/core/theme/AntDesignProvider.tsx`: açık/koyu algoritmalar, TR/EN,
  renkler, font ve boyutlar. Açık gri/koyu lacivert sayfa zemini, ayrı içerik yüzeyi; 14px gövde,
  34px standart kontrol. Dokunmatik kullanım ayrıca tarayıcıda doğrulanmalıdır.
- İş ekranları `core/ui/Button` ve `core/ui/Dialog` kullanır. Yerel CSS ile ayrı
  buton teması oluşturulmaz. Button `type` HTML form davranışını ifade eder.
- Native JSX'ten taşınan form butonlarının submit davranışı korunur; yeni
  butonların türü açık yazılır. Kaydet/Çalıştır/İptal farklı davranışlardır.
- Ant Input ve Input.TextArea metin alanları içindir. CodeMirror SQL editörü ve
  React Flow diyagramı korunur; uygulama temasını tüketir.
- Alan CSS dosyaları `akis-layout` katmanında yalnız yerleşimi tutar. Eski renk,
  font, kenarlık ve native kontrol temaları kaldırılmıştır. Görsel kurallar
  `ant-design.css` ve Ant tokenlarından gelir.
- Yeni ekran: başlık → filtre → özet → kayıtlar. Düzenleme merkezî Modal içinde;
  yoğun prosedür editörü üstte adımlar, altta seçili adım detayları düzenini korur.

## Bileşenler ve davranış

- Button, Input, Select/AutoComplete, Checkbox, Radio/Segmented, Switch,
  Tree, Tabs, Table, Collapse, Card, Tag, Modal ve notification Ant tabanlıdır.
- Yeni dropdownlar `core/ui/Select` kullanır; bu adaptör zorunlu alan kontrolü
  ve mevcut FormData sözleşmesini korur. Native option JSX yalnız seçenek verisidir.
- Kaydet/ekle/sorgula ana eylemdir; mavi kullanır. Silme tehlikeli eylemdir;
  kırmızı ve onay gerektirir. Test/yenile nötr ikincil eylemdir. Renk tek başına
  anlam taşımaz: okunur etiket ve ilgili ikon bulunur.
- Başlık 20px, bölüm 17px, alt başlık 16px, gövde 14px, yardımcı metin 12px.
  SQL ve kod gösterimi dışında ayrı font kullanılmaz.
- Alanlar eşit sütunlarda, 12–16px aralıkla; bölümler 20px aralıkla yerleşir.
  Mobilde sütunlar alt alta iner; tablonun yatay kaydırması kendi alanındadır.
- Bağlantı tanımı, fiziksel şemalar ve kullanım merkezî pencerede sekmelerdir.
  Bağlantı testi kaydetmenin ön koşulu değildir. Test sonucu anlık erişim garantisi değildir.
- Çalıştırma detayı sonuç, zamanlar, adım ağacı, mevcut satır sayıları ve hatayı
  gösterir. Deneme, hash, teknik bağlam ve kanıt panelleri ana görünümde bulunmaz.
  Bilinmeyen sayaç sıfır gösterilmez; eklenen satır toplamı yalnız committed adımlardır.
- Başarı bildirimi 5 saniye, hata 8 saniye görünür; üzerine gelindiğinde süre durur.
  Diyaloglar Escape, odak sınırı ve tetikleyiciye odak dönüşünü destekler.

## Kabul sınırı

Liste ekranları `.page-stack` ile kullanılabilir genişliğin tamamını kullanır;
sayfaya özel 1380px gibi üst sınırlar eklenmez. Masaüstü kenar boşluğu ortak
ana yerleşimden gelir.

Ana gezinme Ant Design Menu ile sabit sol sütundadır. Proje ağacı bu sütunda
ayrı bir bileşendir; üstte ikinci bir ana sekme satırı bulunmaz. Dar ekranda
sol gezinme üstteki menü düğmesiyle açılır, seçimden sonra kapanır.
Kart/liste/tablo seçimi ortak Ant Design Segmented bileşenini kullanır.
DataGrid varsayılan olarak bu seçiciyi sağlar. Dışarıdan görünüm yönetilen
tablolarda ikinci seçici gösterilmez; hücre düzenlemeli eşleme matrisi ve
birleştirilmiş hücre içeren tabloların matrissel yapısı korunur.
Özet şeridi ile kayıt bölümü aynı dış kenarlara sahiptir; ikon sabit genişlikte,
rakam ve etiket ayrı esnek içerik alanındadır.
Bağlantı, mantıksal şema ve ortam kayıtları merkezî
pencerede yönetilir; liste satırı ayrı detay sayfasına yönlendirmez.
Mantıksal şema/ortam kodu referans bütünlüğü için sabittir. Güncelleme ve
arşivleme sürüm kontrolüyle yapılır; kullanımda olan kayıt arşivlenemez.

Bileşen dönüşümü otomatik olarak tam işlev, erişilebilirlik veya tüm ekranların
mobil kabulü anlamına gelmez. Mevcut birim ve tarayıcı senaryolarının tamamı
yeşil olmadan geçiş tamamlandı olarak raporlanmaz. Canlı prosedür yürütmek bu
tasarım doğrulamasının parçası değildir.

## Doğrulama

`AntDesignProvider.test.ts`: iki temada gövde/ikincil metin kontrastı ve ölçüler.
`ant-design.e2e.spec.ts`: login 390/1440px, iki tema; bağlantılar, ortamlar,
çalıştırma geçmişi ve gerçek prosedür editöründe yüklenme ve yatay taşma kontrolü.
Bu test veri kaydetmez ve prosedür çalıştırmaz. Diğer etkileşimler mevcut birim ve
tarayıcı testleriyle korunur. Ekran görüntüsü testinin geçmesi tek başına tam E2E
işlev veya tüm ekranların mobil kabulü anlamına gelmez.

### 15 Eylül 2026 geçiş doğrulaması

- Üretim derlemesi ve lint başarılı.
- Birim/bileşen paketi: 173/173 başarılı, atlanan test yok.
- Playwright paketi: 24/24 başarılı. Son hizalama ilavesiyle görsel senaryo ayrıca başarılı.
- Açık/koyu ekran görüntüleri incelendi; Adım Adı ve Log Sayacı alanlarının
  konum ve ölçü farkları en fazla 2 piksel olarak doğrulandı.
- Canlı SQL/prosedür yürütülmedi; bu sonuçlar arayüz geçişinin doğrulamasıdır.
# Record icons and attribution

## Screen hierarchy review — 15 September 2026

### SQL editing panel

- A command has an editable inline SQL area and an optional Edit SQL panel. Both share formatting, validation, copy and project-variable completion. Cancelling the expanded panel restores the inline text present when it opened.
- `@NAME` is the editor notation for a project variable (`${NAME}` is also accepted for compatibility). Apply resolves its definition, type, refresh query, logical schema and history mode, then stores the existing canonical `:NAME` bind plus parameter metadata. Runtime still uses typed JDBC binds; values are never interpolated into SQL text. Source-row `:ID` binds and Oracle `TABLE@DBLINK` remain unchanged. Ambiguous names are rejected.
- Cancel discards local edits; Apply changes the procedure in memory; the document Save persists it. Failed or late variable responses cannot overwrite the parent after the panel is gone.
- Transaction management remains per source/target command, not a single General setting shared across connections.

- A screen has one title, a filter section, a summary strip and a named record surface. Use 24px desktop outer spacing, 16px mobile spacing and 16–20px between sections.
- Keep view controls with the record heading. Keep the view label and segmented control together when wrapping. Cards identify the record by its name, not a count column.
- Dialog forms scroll in the dialog body. Form actions stay in normal flow and must never overlay inputs. Record audit information can use a separate disclosure above the editor.
- Procedure steps and the selected step editor are separate bordered surfaces. SQL has a usable minimum height of 260px (300px on mobile); do not apply flex rules that collapse CodeMirror to one line.
- Desktop procedure exception (at least 992px wide and 650px high): use a viewport-bounded flex workbench. The compact step list is capped at 180px; the detail panel and SQL consume the remaining height. SQL scrolls internally rather than pushing the page below the viewport. On smaller screens use normal document scrolling. Document header, step list and detail panel share their outer edges.
- Navigation becomes compact below the Ant large breakpoint so tablet content is not squeezed beside a 264px sidebar. Catalogs initially use cards below the medium breakpoint; explicit view choices remain available.
- This review included live browser inspection of project entry, connections and editing, logical schemas, environments, procedure/SQL, runs and run details, models and metadata import. Desktop, mobile and dark-theme checks are UI checks, not live database execution tests.


- `RecordFieldIcon` owns semantic field icons in Turkish and English. Card/list labels and table headings use the same mapping; color never replaces the label.
- Record catalogs use `DataGrid auditKind` for Created By, Created At, Updated By and Updated At. Supported catalogs: connections, physical schemas, logical schemas, environments and models. Action columns remain last.
- Connection cards and connection/context dialogs use `RecordAuditFields`; compact metadata stays separate from connection settings.
- Attribution is read from successful immutable HTTP audit events and existing row audit fields. Creation uses the response Location to identify the new record. Failed updates and connection tests do not change editor attribution.
- Historical unknown identities display Not Recorded; loading and retrieval failures have distinct messages. Do not backfill the current user as the historical creator.
- All audit text, icons and borders use shared typography/theme tokens. Derived rows (SQL columns, procedure steps, run metrics) do not invent individual creation metadata.
- Verified with targeted UI tests, isolated PostgreSQL attribution tests, request audit filter tests and desktop/mobile/light/dark Playwright checks. These are scoped checks, not a claim that the entire project suite was rerun.
