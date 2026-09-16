# KM Kurulum ve Kabul

Bu belge otomatik canlı çalıştırma izni değildir. Yalnız ayrılmış test ortamında uygulanır.

## İlk kapsam

Tek Oracle kaynak, aynı hedef DB/PDB içindeki yönetilen çalışma şeması ve tek hedef tablo.
Doğrudan kolon eşlemesi; atomik tam hedef yenileme. MERGE, APPEND, JOIN, CDC kapsam dışıdır.
DSL sonlu işlem listesidir; Java/Groovy/serbest SQL çalıştırma veya eval değildir.

## Ön koşullar

1. Test projesi, ortamı, kaynak tablosu ve tamamen yenilenmesine izin verilen hedef tablo seçilir.
2. Hedef DB/PDB üzerinde yalnız AKIŞ'ın kullanacağı çalışma hesabı ayrılır. Kendi tabloları için
   CREATE/DROP ve hedef veri hesabına SELECT grant gerekir. Kaynak hesabı salt okunurdur.
3. Hedef ledger/fence kurulumu mevcut runtime sözleşmesine göre DBA tarafından hazırlanır.
   Çalışma hesabı ve hedef hesabı varsayılan olarak ayrıdır; aynı şema istisnası açık politika gerektirir.
4. Metadata yedeği alınır; V017–V020 migrationları sıralı uygulanır. İzole test komutu:
   `./database/akis-baseline/test-knowledge-modules.ps1`.
5. Bağlantı veya fiziksel şemada loading/integration/error prefixleri tanımlanır.
   İlk sürümde loading çalışma tablosu kullanılır; IKM/CKM için gereksiz tablo üretilmez.
6. Fiziksel şemada çalışma alanı etkinleştirilir; nesne/satır/byte kotaları seçilir.
   Maksimum nesne kotasına inceleme bekleyen nesneler de dahildir.
7. Kaynak/hedef metadata keşfi ve snapshot'ları, ortam–mantıksal şema eşleşmeleri hazırlanır.

## Tanım ve yayın

- LKM/IKM ve gerekirse CKM `AKIS_KM/1` diliyle oluşturulur; değişmez sürümleri kaydedilir.
- Mapping sürüm 3'te açıkça ATOMIC_DELETE_INSERT seçilir; çalışma mantıksal şeması,
  KM sürümleri ve kotalar atanır. Boş kaynak varsayılan olarak hedefi boşaltamaz.
- Yayın önizlemesinde kaynak, çalışma, hedef, modül sürümleri, prefix ve adımlar incelenir.
- Yalnız kontrollü kabul ortamında `AKIS_EXECUTION_STAGED_RUNTIME_ENABLED=true` açılır.
  Mevcut manual-request/procedure-runtime/worker flag ve worker kimlik ayarları da gerekir.
  Bu depo değişikliği mevcut `.env` içinde hiçbir flag'i açmaz.
- Flag kapalıyken oluşturulan DEFINITION_ONLY yayını kendiliğinden yürütülebilir hale gelmez;
  flag açıkken yeni, doğrulanmış yayın oluşturulur.

## Kabul matrisi

| Senaryo | Beklenen kanıt |
|---|---|
| 1.201 satır, batch 500 | Bütün satırlar aktarılır; tek atomik hedef yayın; sonuçta aynı satır sayısı |
| Boş kaynak, izin kapalı | Hedef değişmez; yükleme başarısız |
| Satır/byte kotası aşımı | Hedef değişmez; çalışma nesnesi incelemeye kalır |
| CKM null/unique ihlali | Hedef oturumu/yayını başlatılmaz |
| Politika değişimi veya lease kaybı | Sonraki etki durur; eski işleyici yayınlayamaz |
| Hedef DML hatası | Hedef işlemi rollback; kör tekrar yok |
| Commit cevabı kaybı | Sonuç belirsiz; yeni bağlantı/fence ile ledger mutabakatı |
| Mutabakat PUBLISHED | Başarı kanıtı görünür; ilk belirsiz kayıt korunur; ikinci DML yok |
| Yanlış object ID/kolon yapısı | DROP reddedilir; inceleme durumu |
| Başarılı normal akış | Kendi çalışma nesnesi temizlenir; kaynak tablo değişmez |
| Başka proje/sürüme erişim | Tanım, yayın, günlük ve mutabakat reddedilir |

Başarısız/belirsiz çalışma nesneleri otomatik silinmez. Saklama süresi dolması tek başına
DROP yetkisi değildir. DBA incelemesi gerekir. Reconciliation eski nesil için cleanup yetkisi üretmez.

## Kabul kaydı

Her deneme için yayın UUID/hash, çalıştırma UUID, beklenen/gerçek kaynak ve hedef sayıları,
adım sonuçları, kontrol düzlemi/Oracle ledger kanıtı ve cleanup sonucu kaydedilir.
Kimlik bilgileri ve iş satırı payload'ları rapora alınmaz.
Tam ürün tarayıcı akışı ve gerçek Oracle matrisi geçmeden canlı kullanım onayı verilmez.
