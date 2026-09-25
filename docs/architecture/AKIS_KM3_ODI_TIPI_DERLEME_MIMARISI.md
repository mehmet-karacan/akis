# AKIS KM/3 — ODI Tipi Güvenli Derleme Mimarisi

## Karar

AKIŞ Knowledge Module yapısı, ODI'deki görev + komut + substitution API ayrımını benimseyecek; ancak KM içinde kullanıcı Java kodu veya sınırsız SQL çalıştırılmayacaktır. KM sürümü, sıralı görevleri ve görevlerin teknolojiye özgü komut şablonlarını taşır. Yayın sırasında bu şablonlar sabitlenmiş mapping metadata'sıyla derlenir ve değişmez fiziksel yürütme planına dönüşür.

Yeni sözleşme `AKIS_KM/3` olacaktır. `AKIS_KM/1` ve `AKIS_KM/2` yalnız geriye dönük çalıştırma için korunur.

## İncelenen ODI davranışı

Yerel `11G_SmartExport.xml` dosyasında bağlantı ve parola gibi hassas alanlar da bulunduğundan yalnız KM görevleri ve komut metinleri incelendi; dosya veya hassas değerler projeye alınmadı.

Örnek LKM görev sırası:

1. Drop work table
2. Create work table
3. Lock journalized table
4. Load data
5. Create temporary indexes on work
6. Analyze work table
7. Cleanup journalized table
8. Drop work table

Dosyada en sık görülen substitution çağrıları `getInfo`, `getColList`, `getOption`, `getTable`, `getPop`, `getModel`, `getDataSet` ve `getSrcTablesList` oldu. ODI komutları `<%= odiRef... %>` ifadelerini ve Java kontrol bloklarını tasarım metadata'sından SQL üretmek için kullanır.

Oracle belgeleri de substitution metodlarının KM veya prosedür görevlerinin metninde kullanıldığını ve Java ifadesinin döndürdüğü metnin komuta yazıldığını tanımlar:

- https://docs.oracle.com/middleware/11119/odi/develop-km/odiref_reference.htm
- https://docs.oracle.com/middleware/1212/odi/ODIKD/api_intro.htm

## AKIŞ karşılığı

| ODI | AKIŞ KM/3 |
| --- | --- |
| Knowledge Module task | Sıralı `ADIM` |
| Source/target command | Adıma bağlı `KOMUT` |
| `odiRef.getTable(...)` | `akisRef.table(...)` |
| `odiRef.getColList(...)` | `akisRef.columns(...)` |
| `odiRef.getOption(...)` | `akisRef.option(...)` |
| `odiRef.getInfo(...)` | `akisRef.context(...)` |
| Java/JSP kontrol bloğu | Tipli `EGER` ve güvenli liste şablonu |
| Çalışma anında substitution | Yayın anında derleme ve hash ile sabitleme |

## Önerilen dil

```text
AKIS_KM/3
MODUL LKM
TEKNOLOJI ORACLE POSTGRESQL

SECENEK DROP_WORK_TABLE BOOLEAN ISTEGE_BAGLI true YOK

ADIM ONCEKI_CALISMAYI_TEMIZLE STAGING SQL WORK_SOURCE_1 HATA_YOKSAY
KOMUT ONCEKI_CALISMAYI_TEMIZLE SQL
<<<
drop table {{ akisRef.table("WORK", "QUALIFIED") }}
>>>

ADIM CALISMA_TABLOSUNU_OLUSTUR STAGING SQL WORK_SOURCE_1
KOMUT CALISMA_TABLOSUNU_OLUSTUR SQL
<<<
create table {{ akisRef.table("WORK", "QUALIFIED") }} (
{{ akisRef.columns("TARGET", "DDL", ",\n") }}
)
>>>

ADIM KAYNAKTAN_VERIYI_AL SOURCE_TO_STAGING JDBC_BATCH WORK_SOURCE_1
KOMUT KAYNAKTAN_VERIYI_AL SOURCE_SQL
<<<
select {{ akisRef.columns("SOURCE", "EXPRESSION_AS_TARGET", ",\n") }}
from {{ akisRef.tables("SOURCE", "FROM_JOIN") }}
{{ akisRef.filters("SOURCE") }}
>>>
KOMUT KAYNAKTAN_VERIYI_AL TARGET_SQL
<<<
insert into {{ akisRef.table("WORK", "QUALIFIED") }}
({{ akisRef.columns("TARGET", "NAME", ",") }})
values ({{ akisRef.columns("TARGET", "BIND", ",") }})
>>>

ADIM CALISMA_ALANINI_TEMIZLE STAGING SQL WORK_SOURCE_1 EGER DROP_WORK_TABLE
KOMUT CALISMA_ALANINI_TEMIZLE SQL
<<<
drop table {{ akisRef.table("WORK", "QUALIFIED") }}
>>>
```

Bu örnekteki sözdizimi nihai parser sözleşmesidir; serbest Java değildir. Yalnız kayıtlı `akisRef` fonksiyonları, sabit string argümanları ve tipli KM seçenekleri kabul edilir.

## `akisRef` ilk sürüm kataloğu

- `table(role, format)`: `SOURCE`, `TARGET`, `WORK`, `ERROR`; fiziksel şema ve prefix politikasından isim üretir.
- `columns(role, projection, separator)`: sıralı ve sabitlenmiş kolon metadata'sından isim, DDL, ifade, alias veya bind listesi üretir.
- `tables(role, projection)`: mapping kaynaklarını ve join ağacını üretir.
- `filters(role)`: mapping filtre AST'sini teknoloji diyalektine çevirir.
- `option(name)`: yalnız KM sürümünde tanımlı ve yayında sabitlenmiş seçeneği döndürür.
- `context(name)`: allowlist içindeki çalışma şeması, teknoloji, sürüm ve çalışma kimliği bilgilerini döndürür.

## Güvenlik ve deterministik derleme kuralları

1. `java`, reflection, sınıf yükleme, dosya sistemi, ağ ve ortam değişkeni erişimi yoktur.
2. Şablon parser'ı yalnız allowlist fonksiyonları ve literal argümanları kabul eder.
3. SQL yorumuyla kaçış, çoklu statement ve teknolojiye uymayan DDL/DML yayın öncesi reddedilir.
4. Nesne adları fiziksel şema/snapshot metadata'sından gelir ve diyalektin identifier kurallarıyla doğrulanır.
5. Seçenek değerleri tipli sözleşmeden gelir; SQL metnine kontrolsüz string eklenmez.
6. Derlenen her komut; KM sürümü, mapping sürümü, bağlantı/snapshot kimliği ve seçeneklerle birlikte hash'e girer.
7. Worker yalnız hash'i doğrulanmış fiziksel planı yürütür. KM tanımını çalışma sırasında tekrar yorumlamaz.
8. Çalıştırılan SQL her adımın immutable kanıtına kaydedilir.

## Uygulama sırası

1. `AKIS_KM/3` parser: `TEKNOLOJI`, `ADIM`, `KOMUT`, `EGER`, `HATA_YOKSAY`.
2. Güvenli `akisRef` AST parser ve allowlist doğrulayıcı.
3. Mapping metadata'sını kullanan `KmTemplateCompiler`.
4. Derlenmiş komutları `stagedPlan.steps[].commands[]` içine koyma ve hash'e dahil etme.
5. `StagedSqlPreview` çıktısını hard-coded SQL yerine derlenmiş komutlardan üretme.
6. Runtime adapter'larını `SQL`, `JDBC_BATCH`, `VALIDATE`, `SEAL` görev türlerine bağlama.
7. KM editörüne kaynak/hedef komut sekmeleri, derleme önizlemesi ve satır bazlı hata gösterimi ekleme.
8. Mevcut LKM/IKM şablonlarını KM/3'e taşıma; KM/2 planlarını değişmeden çalıştırma.

## Uygulama durumu — 24.09.2026

- Tamamlandı: `AKIS_KM/3` başlığı, tipli `SECENEK`, sıralı `ADIM` ve çok satırlı `KOMUT` blokları.
- Tamamlandı: Java/JSP, reflection ve dinamik argümanları reddeden güvenli `akisRef` parser/renderer.
- Tamamlandı: kaynak SELECT, staging INSERT ve hedef entegrasyon SQL'lerinin mapping metadata'sından üretilip KM/3 şablonlarına bağlanması.
- Tamamlandı: derlenmiş komutların `compiledCommands` olarak fiziksel plana ve dolayısıyla `physicalPlanHash` kapsamına alınması.
- Tamamlandı: runtime çalışma tablosu adıyla aynı şablonların yeniden çözümlenmesi ve adım SQL kanıtına verilmesi.
- Tamamlandı: KM editöründe adım listesi, seçili adım özellikleri, kanal bazlı komut editörü ve KM/1–2 taslağını kontrollü KM/3'e yükseltme.
- Korundu: KM/1–2 yayınları eski sonlu üreticiyle çalışır; mevcut yayın ve sürümler yükseltme sırasında değiştirilmez.
- Tamamlandı (24.09.2026): yerel LKM/IKM taslakları KM/3'e yükseltildi ve yeni immutable sürümleri oluşturuldu.
- Tamamlandı (24.09.2026): 38 mapping yeni KM/3 modül sürümlerine geçirildi, sürümlendi, senaryoları derlendi ve PROD yayınları onaylandı.
- Tamamlandı (24.09.2026): 38 adımlı paket yeniden yayınlandı; üç saatlik zamanlama yeni yayına yönlendirildi.
- Kanıt çalıştırması: `f785d708-af00-4d62-abcc-0a32df420c7b` 38/38 başarılı. Örnek `SATIS_KANALI` çocuk çalışması kaynak SELECT, staging INSERT, hedef TRUNCATE/INSERT ve DROP SQL'lerini adım kanıtında sakladı; 12 satır seçildi ve 12 satır yazıldı.

## Kabul ölçütleri

- Aynı KM ve aynı sabitlenmiş metadata her zaman aynı fiziksel plan hash'ini üretir.
- Oracle → PostgreSQL yükleme planında çalışma tablosu adı fiziksel şemanın prefix bilgisinden gelir.
- Adımın kaynak ve hedef komutları çalıştırma detayında ayrı SQL kanıtları olarak görünür.
- `DROP WORK TABLE` hata-yoksay davranışı yalnız ilk temizleme adımında ve açıkça tanımlandığında uygulanır.
- Teknoloji uyumsuz KM yayınlanamaz.
- Kullanıcı Java'sı veya doğrulanmamış serbest SQL runtime'da çalıştırılamaz.
