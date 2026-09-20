# Koşullu KM adımları

Bu belge mevcut sınırlı `AKIS_KM/2` yürütücüsünün sözleşmesini açıklar. Java kaynak kodu veya serbest SQL çalıştırma desteği değildir. Dil doğrulaması veritabanında modül çalıştırmaz.

## Tanım

Bir adım, aynı modülde daha önce tanımlanmış bir Boolean seçeneğe bağlanabilir:

```text
AKIS_KM/2
MODUL CKM
SECENEK CHECK_REQUIRED BOOLEAN ISTEGE_BAGLI true YOK
SECENEK CHECK_KEYS BOOLEAN ISTEGE_BAGLI false YOK
ADIM REQUIRED_FIELDS STAGING CHECK_NOT_NULL WORK_SOURCE_1 EGER CHECK_REQUIRED
ADIM UNIQUE_KEYS STAGING CHECK_UNIQUE WORK_SOURCE_1 EGER CHECK_KEYS
```

`EGER` olmayan adımlar her zaman yürütme planına alınır. `EGER` koşulu true ise adım alınır, false ise alınmaz. Metin olarak `"false"` bir Boolean değer değildir. Tanımsız, başka modüldeki veya Boolean olmayan seçenek koşul olarak kullanılamaz. Varsayılanı ve sağlanan değeri olmayan bir koşul, sessizce false kabul edilmez; plan derlenemez.

Modülün seçenekleri arayüzün Execution Modules alanında seçilen sabit modül sürümünden gelir. Kullanıcı değeri belirtmezse sürümdeki varsayılan kullanılır. Aynı adlı seçenekler farklı modül rollerinde birbirini etkilemez.

## Yayın ve çalıştırma

1. Yayında seçenek tipleri, zorunlulukları ve yerleşik anahtarların kuralları doğrulanır.
2. Koşullar çözümlenir ve etkin adımlardan fiziksel yürütme planı üretilir.
3. Modül kaynağı, sürüm referansı, çözülmüş seçenekler ve etkin adımlar yayın planına sabitlenir.
4. Çalıştırma öncesinde sabitlenmiş seçenekler yeniden doğrulanır ve kaynak yeniden derlenir. Adımlar fiziksel planla birebir uyuşmazsa yürütme reddedilir.
5. Adım günlüğü etkin planı izler. Kapalı adımlar için çalıştı veya atlandı kaydı üretilmez; koşul ve seçenek bilgisi sabitlenmiş modül kaynağında/planda bulunur.

## Korunan kurallar

- LKM çalışma slotlarının CREATE_WORK, TRANSFER_JDBC, SEAL_WORK sırası korunmalıdır. Yalnız aktarımı kapatıp mühürlemeyi açık bırakmak geçersizdir.
- LKM tamamen kapatılamaz. IKM mevcut sözleşmede tek zorunlu hedef uygulama adımı içerir; bu adım kapatılırsa plan reddedilir.
- CKM kontrolleri ayrı ayrı kapatılabilir. Bir kontrolün kapalı olması verinin kontrol edildiği anlamına gelmez.
- Koşulu false olan bir adım bile tanımsız çalışma slotuna referans veremez.
- `AKIS_KM/1` bu koşul söz dizimini desteklemez. Mevcut koşulsuz sürümler sessizce dönüştürülmez.

## Yerleşik yazma seçenekleri

LKM, DISTINCT ve ORACLE_HINT seçeneklerini kullanır. IKM, WRITE_MODE, KEY_COLUMNS, TRUNCATE_TARGET ve ORACLE_HINT seçeneklerini kullanır. TRUNCATE için hem `WRITE_MODE=TRUNCATE_LOAD` hem `TRUNCATE_TARGET=true` gerekir. Yalnız TRUNCATE_TARGET değerini açmak hedefi temizlemez. Oracle TRUNCATE işlemi geri alınabilir atomik DML gibi değerlendirilmez.

Bu koşullar mevcut sınırlı operasyonları kontrol eder; yeni bir operasyon, serbest Java/SQL veya özel RKM yürütücüsü tanımlamaz. Uzak Oracle üzerinde başarı, DDL davranışı ve kurtarma kabulü ayrıca doğrulanmalıdır.
