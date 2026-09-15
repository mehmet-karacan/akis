# ODI referanslarından AKIŞ revizyonu

## Tasarım kararı

ODI 12c ekranları görsel şablon değildir. AKIŞ'ın açık tema, modern bileşen,
bağlama duyarlı işlem ve sade ekran ilkeleri korunur. Referansların işlev ve
bilgi hiyerarşisi değerlendirilir. Desteklenmeyen motor özellikleri çalışır
kontroller gibi sunulmaz.

## İncelenen referanslar

| Görsel saati | Konu | Mevcut durum / takip |
| --- | --- | --- |
| 112644 | Proje ve model ağaçları | Proje tür grupları mevcut. Model ve veri nesneleri için isteğe bağlı açılan ağaç eklendi. Alt model hiyerarşisinin ağaca taşınması açık. |
| 112712 | Yürütme modülü kategorileri | Düzenleyici, backend sözleşmesindeki RKM/LKM/CKM/IKM/JKM/SKM/XKM türlerini açıklamalarıyla sunuyor. Ağaçta kategori bazında gruplama açık. |
| 113104 | Modül görevleri | Dikey görev listesi, seçili görev ayrıntısı, taşıma/silme ve seçenek düzenleme eklendi. Yaşam döngüsü aşamaları ve özel modül yürütücüsü açık. |
| 113120 | Paket diyagramı ve araçlar | Mevcut nesne seçici ve başarı/hata geçişleri korunuyor. Dosya/internet gibi ek araçlar motor desteği olmadan eklenmedi; ayrı yetenek geliştirmesi gerekiyor. |
| 113306 | Mantıksal mapping | Model kolonlarından çizilen ve doğrudan kolon eşleşmesi ekleyen diyagram eklendi. JOIN ve diğer dönüşüm düğümleri, çoklu kaynak planlama ve motor desteği açık. |
| 113313 | Fiziksel mapping | Çalışma yerleşimi ve kaynak/staging/hedef yürütme birimlerini gösteren gerçek plan görünümü açık. Görsel bir maketle tamamlanmış sayılmaz. |
| 113325 | Entegrasyon modülü seçenekleri | Modül seçeneklerinin tanımı düzenlenebiliyor. Mapping'e modül bağlama, seçenek tipleri/varsayılanları ve çalışma motoruna aktarım açık. |

## Koruma kuralları

- Kullanıcı prosedürleri, paketleri ve canlı Oracle verileri bu revizyonda değiştirilmez.
- Aynı hedef kolona ikinci diyagram bağlantısı oluşturulmaz; hedef-hedef ve boş uç bağlantıları engellenir.
- Yürütme modülünün bilinmeyen mevcut metadata alanları düzenlemede korunur.
- Model kataloğu proje ekranı açılışında topluca sorgulanmaz; ağaç genişletildiğinde yüklenir.
- Aktarım işlemleri bağlam menüsünde kalır. ODI'nin eski araç çubukları kopyalanmaz.

## Doğrulama

Yeni testler: modül görev sırası ve komut korunması, seçenek düzenleme,
mapping bağlantı doğrulaması, model ağacı gecikmeli yükleme ve hata sonrası
yeniden deneme. Tarayıcı testleri yeni düzenleyicilerin açılmasını, dikey görev
yerleşimini ve yatay taşma olmamasını kontrol eder.

Bu kayıt tam ODI yetenek eşitliği veya tüm revizyonun tamamlandığı anlamına gelmez.
