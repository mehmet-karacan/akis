# Bağlantı çalışma alanı kararı

## Amaç

Bağlantılar ekranı, bir projedeki veritabanı sunucularını ve bunların etkisini tek
bakışta göstermelidir. Ekran bir sürüm geçmişi veya genel topoloji diyagramı değildir.

## Bilgi mimarisi

- **Bağlantı**, ODI terminolojisindeki data server karşılığıdır. Sağlayıcı ve erişim
  noktasını temsil eder.
- **Fiziksel şema**, tek bir bağlantının altındadır.
- **Mantıksal şema**, bağlantının çocuğu değildir. Yeniden kullanılabilir bir iş
  takma adıdır.
- **Bağlam**, bir mantıksal şemayı belirli bir fiziksel şema ve bağlantı sürümüyle
  eşler.
- **Model**, mantıksal şemaya dayanır ve bağlantı ekranında yönetilmez.

Bu ayrım Oracle ODI'nin data server, physical schema, logical schema ve context
modeliyle uyumludur:

- <https://docs.oracle.com/middleware/11119/odi/develop/setup_topology.htm>
- <https://docs.oracle.com/middleware/1212/odi/ODIDG/intro.htm>

## Ekran sözleşmesi

Bağlantılar sağlayıcı başlığı altında kart olarak gösterilir. Her kartta:

- ad, kod, sağlayıcı ve durum,
- etkin/güncel uç nokta özeti,
- kullanıcı adı,
- fiziksel şema sayısı,
- bağlamlar üzerinden ilişkilendirilmiş benzersiz mantıksal şema sayısı,
- son test zamanı,
- `Bağlantıyı Test Et`, `Düzenle` ve `Sil` işlemleri

bulunur. Ortamlar bağlantı kartlarının yanında yer almaz; `Bağlamlar` alanında
eşlemelerle birlikte yönetilir.

## Güncelleme ve silme

- Ad, kod ve açıklama iyimser kilit (`versiyon_no`) ile güncellenir.
- Uç nokta ve kimlik bilgisi değişikliği denetlenebilir teknik kayıt olarak ele
  alınır; parola API tarafından geri döndürülmez.
- Kullanıcıdaki `Sil` işlemi fiziksel veri silmez. Bağlantı ile altındaki fiziksel
  şemaları aynı işlem içinde arşivler.
- Arşivlenen fiziksel şemalara ait bağlam eşlemeleri aktif API listelerinden çıkar.
- Mantıksal şemalar ve geçmiş çalışma kanıtları korunur.
- Aktif kayıtların kod benzersizliği kısmi indekslerle uygulanır; arşivlenen bir
  bağlantının kodu daha sonra yeniden kullanılabilir.
