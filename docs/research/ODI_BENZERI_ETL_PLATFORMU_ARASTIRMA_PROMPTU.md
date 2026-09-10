# ODI Benzeri Veri Entegrasyon Platformu - Teknik Arastirma Promptu

## Onerilen calistirma profili

Bu prompt, **GPT-5.6 Sol + Pro mode + web aramasi** ile tek ve kapsamli bir teknik
arastirma raporu uretilmesi icin hazirlanmistir.

Arayuzden calistiriliyorsa:

- Model: `GPT-5.6 Sol`
- Calisma modu: `Pro`
- Web aramasi: acik
- Cikti: tek, kendi basina okunabilen Markdown raporu

Responses API ile calistiriliyorsa esdeger hedef:

```text
model: gpt-5.6-sol
reasoning.mode: pro
reasoning.effort: max
web_search: enabled
text.verbosity: high
```

`reasoning.mode` ile `reasoning.effort` birbirinden bagimsizdir. Bu calisma
kalite-oncelikli, cok kaynakli ve cok kararli oldugu icin `max` onerilir. Ortamda `max`
yoksa `xhigh` kullan; modeli degistirme.

## Kullanim amaci

Bu metin, ayrintili teknik arastirma yapacak bir yapay zeka ajanina, yazilim mimarina
veya veri muhendisligi ekibine dogrudan verilecek ana arastirma promptudur.

Arastirmanin sonunda kod yazmaya baslanmayacak; once uygulanabilir hedef mimari,
teknoloji kararlari, veri modeli, ekran tasarimi, guvenlik sinirlari ve asamali uygulama
plani ortaya cikarilacaktir.

---

# Arastirma gorevi

## Yurutme talimati

Bu talimatlar arastirmanin tamaminda baglayicidir:

1. Once gereksinimleri ve arastirma sorularini cikar; sonra web aramasina basla.
2. Teknoloji secimi veya mimari sonuc vermeden once ilgili resmi kaynaklari acip oku.
   Arama sonucu ozetini kaynak olarak kullanma.
3. Bagimsiz konu gruplarini paralel arastirabilirsin; ancak son kararlari tek bir
   tutarli mimaride birlestir.
4. Kullaniciya ara onay sormadan, makul ve geri alinabilir varsayimlarla arastirmayi
   tamamla. Sonucu maddi olarak degistirecek eksik bilgi varsa varsayimi ve alternatif
   sonucu birlikte yaz.
5. Kod, repository, veritabani, cloud kaynagi veya dis sistemde degisiklik yapma.
   Bu is yalnızca salt okunur web arastirmasi ve mimari rapor uretimidir.
6. Tek bir nihai cevap ver. Dusunce zincirini veya ham arama gunlugunu yazma; kaniti,
   karar gerekcesini, alternatifleri ve belirsizlikleri yaz.
7. Son cevap uzun olmak zorundaysa ayrintiyi azaltma. Tekrari, pazarlama dilini ve
   genel gecis cumlelerini kisalt; karar, kanit, risk ve kabul kriterlerini koru.
8. Bir bilgi dogrulanamiyorsa tahmin etme. `DOGRULANAMADI`, `VARSAYIM` veya
   `PROTOTIPLE DOGRULANACAK` olarak isaretle.

## Web arastirmasi ve citation protokolu

- Web aramasi zorunludur; yalniz model bilgisinden cevap verme.
- Kaynak onceligi:
  1. resmi urun/veritabani/framework dokumantasyonu,
  2. resmi teknik spesifikasyon ve standart,
  3. resmi kaynak kod deposu, release note ve lisans,
  4. guvenilir bagimsiz benchmark veya teknik analiz.
- Surum, lisans, fiyat, destek, guvenlik ozelligi, performans iddiasi ve urun
  yetenegi gibi degisebilen her maddi iddiayi guncel kaynaktan dogrula.
- Her onemli iddianin hemen yanina dogrudan ilgili sayfaya Markdown baglantisi koy.
  Arama sonucu sayfasina baglanti verme.
- Ayni kaynagi cok sayida ilgisiz iddianin dayanagi olarak kullanma.
- Vendor iddiasiyla bagimsiz olcumu birbirinden ayir.
- Kaynaklar celisirse her iki gorusu, surum/tarih farkini ve tercih nedenini yaz.
- Belirsiz cikarma veya yorumlari `Cikarim:` etiketiyle belirt.
- Uzun alinti yapma; kaynagi kendi cumlelerinle ozetle.
- Nihai kaynakcada her kaynak icin baslik, kurum, URL ve erisim tarihi bulunsun.
- Arastirma kesim tarihini raporun basinda acikca yaz.

## Cevap kalitesi kapisi

Nihai cevabi vermeden once sessizce su kontrolleri yap:

- Her zorunlu cikti bolumu var mi?
- Onerilen mimari Oracle -> Oracle MVP'yi gereksiz karmasiklik olmadan calistiriyor mu?
- PostgreSQL metadata/control database karari korunuyor mu?
- Yeni PostgreSQL ve MySQL connector'lari cekirdegi yeniden yazmadan eklenebiliyor mu?
- UI graph kutuphanesi mapping semantiginin sahibi haline gelmis mi?
- Transaction ve `exactly once` konusunda gercek disi vaat var mi?
- Secret, proje izolasyonu ve production context riski ele alinmis mi?
- Karar matrislerinin kriter, agirlik, puan ve gerekceleri tutarli mi?
- Her teknoloji karari guncel ve dogrudan kaynakla desteklenmis mi?
- Bulgular ile oneriler, dogrulanmis bilgi ile cikarim birbirinden ayrilmis mi?
- Yol haritasi prototip, test kapisi ve olculebilir kabul kriteri iceriyor mu?

Eksik olanlari tamamla, celiskileri gider ve ancak sonra tek nihai raporu sun.

## Rolun

Kidemli bir yazilim mimari, veri platformu mimari, ETL/ELT uzmani, Oracle ve PostgreSQL
uzmani, urun tasarimcisi ve uygulama guvenligi uzmani gibi davran.

Oracle Data Integrator (ODI), Informatica, Talend, Apache NiFi, Airbyte, Meltano,
Dagster ve benzer veri entegrasyon urunlerinin guclu ve zayif taraflarini incele.
Bunlardan birini kopyalamak yerine, asagida anlatilan ihtiyac icin daha sade, daha
anlasilir ve genisletilebilir bir urun mimarisi oner.

Bu gorevde uygulama kodu yazma. Guncel kaynaklari arastir, mimari alternatifleri
karsilastir, belirsizlikleri acikca belirt ve uygulanabilir teknik kararlar uret.

---

# 1. Urun vizyonu

ODI'ye benzeyen fakat ondan daha sade, daha hizli ogrenilen ve daha net bir veri
entegrasyon platformu tasarlanacaktir.

Sistem temel olarak bir kaynak veritabaninda sorgu calistiracak, kayitlari kontrollu
bicimde okuyacak, gerekli donusumleri uygulayacak ve hedef veritabaninda insert,
update, upsert veya kontrollu yukleme islemi yapacaktir.

Kullanici giris yaptiginda:

1. Yalniz yetkili oldugu projeleri gorur.
2. Bir proje icindeki tanimli baglantilari, fiziksel semalari, mantiksal semalari ve
   context'leri gorur.
3. Kaynak ve hedef veri yapilarini kesfeder.
4. Yeni bir mapping/interface tasarlar.
5. Mapping'i dogrular, ornek veriyle onizler, surumler ve calistirir.
6. Calisma durumunu, satir sayilarini, sureyi, hatalari ve yeniden calistirma
   seceneklerini izler.

Ilk hedef, Oracle'dan Oracle'a guvenilir veri tasimadir. Mimari bastan asagidaki
yonlere genisleyebilmelidir:

- Oracle -> MySQL
- MySQL -> Oracle
- Oracle -> PostgreSQL
- PostgreSQL -> Oracle
- PostgreSQL -> MySQL
- MySQL -> PostgreSQL
- Ileride dosya, API, obje deposu, mesaj kuyrugu ve CDC kaynaklari

Bu genisleme icin cekirdegin yeniden yazilmasi gerekmemelidir.

---

# 2. Kesin kapsam ve varsayimlar

## 2.1 Ilk surum - MVP

- Tek deployment veya moduler monolit tercih edilir.
- Metadata ve kontrol veritabani PostgreSQL olacaktir.
- Veri tasima yonu yalniz Oracle -> Oracle olacaktir.
- Ilk asamada temel yukleme modlari:
  - append/insert,
  - truncate-and-load seceneginin guvenli ve acik onayli hali,
  - primary/natural key tabanli upsert/merge,
  - watermark tabanli artimli yukleme.
- Kaynak nesnesi tablo, view veya kullanicinin yetkili oldugu SELECT sorgusu olabilir.
- Hedef nesnesi mevcut tablo veya kontrollu sekilde uretilen hedef tablo olabilir.
- Mapping tasarimi surukle-birak destekler; ancak basit kolon eslestirme islemi icin
  kullanici karmasik bir grafik cizmek zorunda kalmaz.
- Mapping kaydedilmeden ve calistirilmadan once semantik ve teknik dogrulama yapilir.
- Zamanlama ilk surumde basit cron-benzeri planlama olabilir; gerekirse manuel calisma
  once gelir.
- Calisma gecmisi, log, metrik, checkpoint ve audit kaydi tutulur.

## 2.2 Ilk surumde kapsam disi tutulmasi degerlendirilecek konular

- Gercek zamanli streaming ve CDC
- Dagitik sorgu motoru
- Buyuk veri/Spark zorunlulugu
- Cok bolgeli aktif-aktif mimari
- Tam veri katalogu veya kurumsal MDM
- Yapay zeka ile kontrolsuz SQL/donusum uretimi
- Marketplace seviyesinde yuzlerce connector
- Bir mapping icinde sinirsiz dongu, script veya keyfi kod calistirma

Bu maddeler gelecekteki genislemeyi engellemeyecek bicimde sinirlandirilmali, fakat MVP'ye
gereksiz karmasiklik olarak eklenmemelidir.

---

# 3. Cevaplanmasi gereken ana mimari sorular

## 3.1 Kontrol duzlemi ve veri duzlemi

Asagidaki ayrimin gerekli olup olmadigini arastir ve hedef mimariyi ciz:

- **Kontrol duzlemi:** kullanici, proje, yetki, metadata, mapping tanimi, surum,
  planlama, calisma kaydi ve audit.
- **Veri duzlemi:** kaynak baglantisi, okuma, donusum, buffer/batch, hedefe yazma,
  commit, retry ve checkpoint.

Kontrol duzlemi PostgreSQL kullanirken asil tasinan verinin PostgreSQL'e zorunlu olarak
ugramamasi gerekip gerekmedigini acikla. Buyuk veri setlerinde uygulama sunucusunun
bellek, disk ve ag kullanimini sinirlayan akis/batch modelini oner.

In-process worker, ayri worker process'i ve kuyruk tabanli worker seceneklerini su
acilardan karsilastir:

- basitlik,
- hata izolasyonu,
- yatay olcekleme,
- iptal,
- retry,
- deployment,
- operasyon maliyeti.

## 3.2 Moduler monolit mi, servisler mi?

Baslangic icin moduler monolit varsayimini degerlendir. En az su modullerin sinirlarini
tanımla:

- kimlik ve yetki,
- proje ve metadata,
- connection/secret yonetimi,
- topology ve context,
- schema discovery,
- mapping tasarimi,
- mapping compiler/validator,
- connector runtime,
- execution/orchestration,
- scheduling,
- audit ve lineage,
- bildirim ve gozlemlenebilirlik.

Hangi kosullarda bir modulun ayri servise donusmesi gerektigini olculebilir esiklerle
belirt.

## 3.3 Backend teknoloji secimi

Asagidaki secenekleri en azindan degerlendir; gerekirse daha iyi alternatif ekle:

- Java/Kotlin + Spring Boot + JDBC,
- Python + FastAPI + SQLAlchemy + veritabani suruculeri,
- .NET + ASP.NET Core + ADO.NET,
- hibrit kontrol duzlemi ve worker yaklasimi.

Degerlendirme kriterleri:

- Oracle surucu ve connection pool olgunlugu,
- JDBC/DB-API/ADO.NET connector soyutlamasi,
- batch okuma ve batch yazma performansi,
- backpressure ve streaming row handling,
- tip donusumlerinin guvenilirligi,
- worker ve job iptali,
- concurrency modeli,
- test edilebilirlik,
- uzun sureli bakim,
- ekibin ogrenme maliyeti,
- connector gelistirme kolayligi,
- lisans ve deployment kosullari.

Tek bir onerilen backend stack'i sec; ancak secilmeyen guclu alternatifi ve neden
secilmedigini de yaz.

## 3.4 Frontend teknoloji secimi

React + TypeScript tabanli bir arayuz ile Vue ve Svelte gibi alternatifleri
karsilastir. Node/edge tabanli editor icin React Flow/xyflow, Rete.js, JointJS,
GoJS ve uygun diger secenekleri su acilardan degerlendir:

- lisans,
- buyuk semalarda performans,
- erisilebilirlik ve klavye kullanimi,
- undo/redo,
- copy/paste,
- otomatik yerlesim,
- cycle/edge validation,
- custom node/port,
- kolon seviyesinde baglanti,
- surumlenebilir JSON state,
- test edilebilirlik,
- ticari kullanim maliyeti.

Grafik kutuphanesi mapping semantiginin sahibi olmamalidir. UI graph state ile
backend'deki kanonik mapping modeli arasindaki siniri acikla.

---

# 4. ODI kavramlarinin yeniden tasarlanmasi

ODI'deki kavramlari bire bir kopyalama. Asagidaki sade kavram modelini incele,
gerekirse adlarini ve sinirlarini iyilestir:

## 4.1 Project

- Mapping'lerin, baglantilarin gorunumlerinin, context baglarinin ve calisma
  yetkilerinin organizasyon siniridir.
- Kullanici yalniz uye veya yetkili oldugu projeyi gorebilir.
- Proje bazli roller en az `GORUNTULEYEN`, `GELISTIREN`, `CALISTIRAN`, `YONETEN`
  gibi yeteneklere ayrilabilir.

## 4.2 Physical architecture

- Gercek veritabani sunucusu ve teknik erisim bilgisidir.
- Ornek alanlar: veritabani turu, host, port, service/SID/database, surucu secenegi,
  SSL/TLS ayari, connection pool politikasi ve secret referansi.
- Parola veya token metadata tablosunda acik metin tutulmaz.

## 4.3 Physical schema

- Gercek connection uzerindeki Oracle schema/user, PostgreSQL schema veya MySQL
  database gibi fiziksel namespace'i temsil eder.

## 4.4 Logical schema

- Mapping'lerin fiziksel ortama baglanmadan kullandigi kararlı mantiksal addir.
- Ornek: `MUSTERI_KAYNAK`, `RAPORLAMA_HEDEF`.

## 4.5 Context / environment

- `GELISTIRME`, `TEST`, `KABUL`, `URETIM` gibi ortam baglamidir.
- `(logical_schema, context) -> physical_schema` bagini cozer.
- Context seciminin mapping tanimini degistirmeden fiziksel hedefi degistirmesi gerekir.
- Yanlis context'te calistirmayi engelleyen acik renk, etiket, onay ve policy tasarla.

## 4.6 Datastore ve schema snapshot

- Kesfedilen tablo/view ve kolon metadata'sinin nasil tutulacagini belirle.
- Canli sema ile kayitli snapshot arasindaki drift nasil bulunacak?
- Kolon silinmesi, tip degisikligi veya nullability degisikligi mapping'i nasil
  `GECERSIZ` veya `UYARI` durumuna gecirecek?

Bu kavramlar icin bir domain modeli, iliski diyagrami ve yasam dongusu oner.

---

# 5. Mapping ve surukle-birak deneyimi

ODI'den daha sade bir mapping deneyimi tasarla. Amac, kullanicinin en sik isi olan
"kaynak kolonlari hedef kolonlara esle" islemini en az adimla yapmasidir.

## 5.1 Onerilen ekran yapisini degerlendir

- Sol panel: proje nesneleri, kaynaklar, hedefler, tablolar ve aranabilir kolonlar.
- Orta alan: yalniz veri akisinin ana dugumleri.
- Sag panel: secili dugum/kolonun ozellikleri ve dogrulama sonucu.
- Alt panel veya ayri sekme: SQL onizleme, ornek veri, validation, run log ve hata.
- Sabit context gostergesi: kullanicinin hangi ortamda oldugu her zaman gorunur.

## 5.2 Basit eslestirme davranisi

- Kaynak ve hedef tablolar yan yana acilabilmeli.
- Kolon tek tek suruklenebilmeli.
- Ayni veya benzer adli kolonlar icin otomatik eslestirme onerisi verilebilmeli.
- Toplu secim ve toplu eslestirme desteklenmeli.
- Otomatik oneriler kullanici onayi olmadan kalici mapping'e donusmemeli.
- Tip uyumsuzlugu baglanti kurulurken gorunur olmali.
- Donusum gerekiyorsa edge uzerinde karmasik kod yerine acik bir transformation
  chip/node veya expression alani kullanilmali.
- Her eslestirme icin kaynak, donusum, hedef ve hedef yazma davranisi okunabilir olmali.

## 5.3 Mapping yetenekleri

Ilk surum ve sonraki surum ayrimini yaparak su ozellikleri ele al:

- bire bir kolon eslestirme,
- sabit deger,
- null/default politikasi,
- cast ve tip donusumu,
- string, tarih ve sayisal fonksiyonlar,
- filtre,
- join,
- lookup,
- expression,
- aggregate,
- sequence/identity davranisi,
- insert/update/upsert/merge,
- hata satirlarini ayirma,
- reject/dead-letter tablosu,
- kaynak SQL ile hedef SQL onizleme,
- parametre ve degisken,
- mapping yeniden kullanimi.

Kullanicinin serbest SQL yazabildigi alanlari sinirla. SQL injection, yetki asimi,
sonsuz veya cok maliyetli sorgu ve loglara hassas veri sizmasi risklerini acikla.

## 5.4 Kanonik mapping modeli ve derleme

UI'dan bagimsiz, surumlenebilir bir ara temsil (IR) oner. En az su kavramlari icersin:

- source dataset,
- target dataset,
- node,
- port/column,
- edge,
- expression,
- filter/join,
- write strategy,
- key definition,
- parameter,
- validation result,
- engine/connector capability requirement.

Mapping'in su asamalardan gecmesini tasarla:

```text
Taslak -> Semantik dogrulama -> Tip dogrulama -> Calistirma plani
       -> SQL/pipeline uretimi -> Onizleme -> Yayinlama -> Calistirma
```

Mapping JSON'unun dogrudan calistirilmamasi; once dogrulanmis, immutable ve
surumlenmis bir execution plan uretilmesi gerekip gerekmedigini degerlendir.

Pushdown transformation ile uygulama icinde transformation seceneklerini
karsilastir. Oracle -> Oracle MVP icin hangi islemlerin tek SQL/DB link, staging veya
uygulama uzerinden batch transfer ile yapilacagini guvenlik ve performansla birlikte
acikla. Oracle database link'i zorunlu varsayma.

---

# 6. Connector mimarisi

Veritabani turlerinden bagimsiz bir connector SPI/SDK sozlesmesi tasarla. Kaynak ve
hedef connector yetenekleri ayrilabilsin.

En az su capability'leri modelle:

- baglanti testi,
- schema/table/view kesfi,
- kolon ve constraint kesfi,
- SELECT veya partitioned read,
- fetch size ve streaming cursor,
- batch insert,
- update/upsert/merge,
- transaction ve savepoint,
- truncate yetenegi,
- DDL yetenegi,
- parameter binding,
- identifier quoting,
- veri tipi esleme,
- hata siniflandirma,
- cancel/timeout,
- watermark,
- CDC destegi,
- pushdown expression destegi.

Connector capability negotiation tasarla. Ornegin Oracle `MERGE` desteklerken baska
bir hedefin farkli upsert semantigi kullanmasi cekirdek kodda daginik `if database ==`
bloklarina donusmemelidir.

Asagidakileri arastir:

- Oracle JDBC veya ilgili secilen surucunun connection pooling davranisi,
- batch/array DML,
- fetch size,
- bind variable,
- LOB/CLOB/BLOB streaming,
- NLS, character set ve timezone,
- NUMBER hassasiyet/esleme sorunlari,
- DATE ile TIMESTAMP farklari,
- quoted identifier ve buyuk-kucuk harf,
- Oracle hata kodlarinin yeniden denenebilir/kalici hata siniflandirmasi,
- hedef commit boyutu,
- source ve target ayni global transaction'da degilken tutarlilik siniri.

Oracle, PostgreSQL ve MySQL icin ortak bir kanonik veri tipi sistemi oner. Asagidaki
tip aileleri icin kayipsiz, kontrollu kayipli ve desteklenmeyen donusum matrisi iste:

- integer ve buyuk integer,
- decimal/numeric,
- float/double,
- char/varchar/text,
- date/time/timestamp/timezone,
- boolean emulasyonu,
- binary/blob/clob,
- UUID,
- JSON,
- interval,
- vendor-specific tipler.

Airbyte CDK, Debezium connector modeli, JDBC tabanli adaptorler ve benzer acik kaynak
yaklasimlari yeniden kullanma veya tasarim referansi olarak incele. Hazir bir connector
ekosistemine baglanmak ile kendi dar connector SDK'sini yazmak arasinda karar matrisi
uret.

---

# 7. Execution, tutarlilik ve hata yonetimi

## 7.1 Calistirma modeli

Asagidaki varliklari ve iliskilerini tasarla:

- mapping,
- mapping_version,
- deployment/publication,
- schedule,
- job,
- job_run,
- task_run/step_run,
- checkpoint,
- row_count_metric,
- error_sample,
- audit_event.

Ayni mapping'in eszamanli calistirilmasi, queue politikasi, duplicate run ve idempotency
kurallarini belirle.

## 7.2 Transaction siniri

Kaynak ve hedef farkli veritabanlari oldugunda gercek `exactly once` vaadinin neden
problemli oldugunu incele. Dagitik transaction/2PC kullanmak, idempotent upsert,
staging+swap, run id kolonlari, checkpoint ve reconciliation yaklasimlarini karsilastir.

Kullaniciya sunulacak garanti seviyelerini net adlandir:

- en az bir kez,
- idempotent tekrar calistirma,
- atomik hedef publish,
- best-effort append,
- snapshot tutarliligi.

## 7.3 Incremental load

Asagidaki artimli yukleme yontemlerini karsilastir:

- monotonik ID,
- update timestamp,
- composite watermark,
- Oracle SCN,
- change tracking/CDC,
- full comparison/hash.

Watermark'in ne zaman ilerletilecegini, gec gelen kayitlari, saat farklarini,
guncellenen/silinen kaynak satirlari ve yeniden calistirmayi tasarla.

## 7.4 Veri kalite ve mutabakat

Her run icin en az su kanitlari degerlendir:

- okunan satir,
- yazilan satir,
- eklenen/guncellenen/reddedilen satir,
- kaynak ve hedef sayim kontrolu,
- null/unique/foreign key kurallari,
- toplamsal kontrol,
- hash veya orneklem kontrolu,
- sure ve throughput,
- checkpoint,
- hata sinifi.

Ham veri degerlerinin loglara kontrolsuz yazilmasini engelle. Hata orneklerinin
maskelenmesi, sinirlanmasi ve saklama suresi icin politika oner.

## 7.5 Retry, resume ve cancel

- Gecici ag hatasi ile kalici SQL/veri hatasini ayir.
- Exponential backoff, maksimum deneme ve circuit breaker gereksinimini tartis.
- Kullanici iptalinde source cursor, aktif statement ve target transaction'in nasil
  sonlandirilacagini acikla.
- Yarim kalan run'in bastan mi checkpoint'ten mi devam edecegini yukleme stratejisine
  gore belirle.
- Worker crash, uygulama restart ve PostgreSQL kesintisi senaryolarini kapsa.

---

# 8. PostgreSQL metadata veritabani

PostgreSQL yalniz kontrol ve metadata veritabani olacaktir. Tasarim, ilk gunden coklu
kullanici ve proje izolasyonuna hazir olmali; ancak gereksiz tenant karmasikligi
olusturmamalidir.

En az su tablo aileleri icin kavramsal ve fiziksel model oner:

- kullanici, rol, yetki,
- proje, proje_uyeligi, proje_rolu,
- baglanti, baglanti_surumu, secret_referansi,
- fiziksel_sema,
- mantiksal_sema,
- context,
- context_sema_eslemesi,
- veri_nesnesi/schema_snapshot,
- kolon_snapshot,
- mapping,
- mapping_surumu,
- mapping_dugumu,
- mapping_baglantisi veya kanonik JSON/normalize hibrit modeli,
- yayin/deployment,
- zamanlama,
- calistirma,
- calistirma_adimi,
- checkpoint,
- metrik,
- hata_ozeti,
- denetim_olayi.

Mapping graph'ini tamamen normalize tablolar, tamamen JSONB veya hibrit saklama
secenekleriyle karsilastir. Sorgulanabilir alanlar, migration, diff, versiyonlama,
referential integrity ve UI save/restore etkilerini acikla.

## 8.1 Baglayici tablo standardi

Onerilecek PostgreSQL semasi asagidaki fiziksel standarda uymali:

```text
id
sahiplik ve ust varlik foreign key'leri
diger foreign key'ler
dogal anahtarlar ve dis kaynak referanslari
tur, durum ve yasam dongusu alanlari
tarih, tutar ve diger domain alanlari
ad, aciklama ve serbest metin alanlari
uuid
tabloya ozel audit alanlari
```

Kurallar:

- Tablo ve kolon adlari Turkce ASCII `snake_case`, tablo adlari tekil olur.
- Her tabloda ilk kolon `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` olur.
- Her tabloda `UUID NOT NULL UNIQUE` bulunur; dis API kimligi olarak `uuid` kullanilir.
- Foreign key adi `<hedef_tablo>_id` bicimindedir.
- Varsayilan FK silme davranisi `RESTRICT` veya `NO ACTION` olur.
- Sorgulanan FK kolonlari ihtiyaca gore ayrica indekslenir.
- Constraint adlari `pk_`, `fk_`, `uq_`, `ck_`; indeksler `ix_` on eki kullanir.
- Zaman noktasi `_zamani`, takvim tarihi `_tarihi`, parasal alan `_tutari` ile biter.
- Zaman noktasi `TIMESTAMPTZ` ve UTC, yalniz takvim gunu `DATE` olur.
- Audit icin tarih ve saat ayri kolonlara bolunmez.
- Bilinmeyen veri `0`, bos metin veya uydurma kodla doldurulmaz; gerekli yerde
  nullable birakilir.
- Serbest JSONB yalniz semasi belli olmayan veya surumlu payload icin kullanilir;
  core iliskiler JSON icine gizlenmez.
- Secret, parola ve connection string acik metin saklanmaz.

Degisebilen varliklarda audit blogu:

```text
olusturulma_zamani
olusturan_kullanici_id
guncellenme_zamani
guncelleyen_kullanici_id
versiyon_no
```

Append-only calistirma, metrik ve denetim tablolarinda yalniz gerekli olusturma audit'i
bulunur. Her tablo icin unique, check, index, retention, silme ve duzeltme politikasi
ayrica yazilir.

---

# 9. Kimlik, yetki ve guvenlik

Security ozellikleri sonraki fazlarda genisletilebilir; fakat mimari bunlari sonradan
eklenemeyecek kadar kapatmamalidir.

## 9.1 Kimlik ve yetkilendirme

- OIDC/OAuth2 tabanli giris ile yerel kullanici yonetimini karsilastir.
- Sistem rolu ile proje rolu ayrimini tasarla.
- Kullanici, proje, connection, mapping, run ve production context seviyelerinde
  authorization matrisi oner.
- Sadece UI gizlemesine guvenme; her API ve worker islemi yetki kontrolunden gecsin.
- Uretim context'inde calistirma icin ek yetki, onay veya four-eyes secenegini tartis.

## 9.2 Secret yonetimi

- PostgreSQL'de yalniz secret referansi tutulsun.
- HashiCorp Vault, cloud secret manager, Kubernetes secret ve yerel encrypted vault
  seceneklerini deployment profiline gore karsilastir.
- Secret'in UI'a geri donmemesi, loglanmamasi, worker'a kisa sureli verilmesi,
  rotasyonu ve audit'ini tasarla.
- Connection test sonucunda parola veya hassas connection ayrintisi sizmasin.

## 9.3 Veritabani erisim guvenligi

- Kaynak icin varsayilan read-only hesap.
- Hedef icin yalniz gereken schema ve DML yetkileri.
- DDL, truncate ve delete gibi yuksek riskli yetenekler ayri capability/policy olsun.
- TLS, wallet, Oracle TCPS, sertifika dogrulama ve network allowlist konularini incele.
- SQL injection'a karsi bind variable ve identifier allowlist kullan.
- Connection pool'lar proje/kullanici sinirini delmemeli.

## 9.4 Audit ve veri gizliligi

- Kim, hangi mapping surumunu, hangi context'te, ne zaman yayinladi ve calistirdi?
- Hangi connection kullanildi, ancak secret neydi sorusunun cevabi audit'e yazilmasin.
- SQL ve hata metinlerinde PII/secret maskelemesi yapilsin.
- Audit olaylari append-only olsun; retention ve arsivleme politikasi bulunsun.

Tehdit modeli uret: STRIDE veya benzeri bir yontemle en az credential theft, SQL
injection, privilege escalation, cross-project access, malicious mapping, data
exfiltration, log leakage, worker compromise ve supply-chain risklerini kapsa.

---

# 10. API tasarimi

REST, GraphQL ve komut tabanli API seceneklerini degerlendir. Baslangic icin sade bir
REST API oneriliyorsa en az su yuzeyleri tasarla:

- auth/session,
- projects ve memberships,
- connections ve test,
- topology/contexts,
- schema discovery ve refresh,
- mappings ve versions,
- validation ve preview,
- publish/deploy,
- runs, cancel, retry ve resume,
- schedules,
- metrics, logs ve audit.

Uzun suren isler icin polling, Server-Sent Events ve WebSocket seceneklerini
karsilastir. API'nin senkron connection test ile uzun sureli veri tasima run'ini ayni
request icinde tutmamasini degerlendir.

Optimistic locking, idempotency key, pagination, filtreleme, hata sozlesmesi,
correlation ID ve API surumleme standartlarini belirt.

---

# 11. Ekran ve urun tasarimi

ODI'yi bilen kullanici kavramlari taniyabilmeli; bilmeyen kullanici ise egitim almadan
temel bir mapping olusturabilmelidir.

## 11.1 Ana gezinme

En az su ekranlari degerlendir:

- Projeler
- Proje genel bakis
- Baglantilar ve topology
- Mantiksal semalar ve context'ler
- Veri nesneleri/schema browser
- Mapping'ler
- Mapping editoru
- Calistirmalar
- Zamanlamalar
- Audit/yetki yonetimi

## 11.2 Ortak ekran standardi

Her ana ekran su sirayi korusun:

1. Ana gezinme ve breadcrumb
2. Aktif proje ve context gostergesi
3. Tek baslik ve ekrani anlatan tek cumle
4. En fazla uc kritik ozet karti
5. Arama ve ekrana ozel filtreler
6. Ana detay alani
7. Duruma uygun birincil ve ikincil eylemler

Ek kurallar:

- Ayni kavram her yerde ayni adla kullanilsin.
- Buton metni eylemi anlatsin: `Baglantiyi test et`, `Mapping'i dogrula`,
  `Onizleme calistir`, `Yayinla`, `Calistir`.
- `Kaydet`, `Yayinla` ve `Calistir` birbirinden farkli eylemler olsun.
- Production context'i her zaman belirgin fakat yalniz renge bagli olmayan bir uyari
  ile gosterilsin.
- Hata mesaji yalniz Oracle kodu degil, anlasilir neden, ilgili adim ve onerilen
  sonraki eylemi gostersin.
- Tablo ve kolon listeleri aranabilir, sanallastirilmis ve klavye ile kullanilabilir
  olsun.
- Koyu/acik tema ayni bilgi hiyerarsisini korusun.
- 320, 390, 768 ve masaustu genisliklerinde kabul kriterleri tanimlansin. Mapping
  editorunun mobilde tam tasarim yerine goruntuleme/sinirli duzenleme sunup sunmayacagi
  acikca kararlastirilsin.

## 11.3 Mapping editoru icin UX arastirmasi

- Basit mapping ile karmasik flow ayni ekranda nasil dengelenmeli?
- Kolon sayisi yuzlerce oldugunda canvas nasil kullanilabilir kalmali?
- Otomatik eslestirme onerisi nasil aciklanmali ve geri alinmali?
- Validation hatalari ilgili node, kolon ve edge'e nasil baglanmali?
- SQL onizleme teknik kullaniciyi desteklerken yeni kullaniciyi nasil korkutmamali?
- Draft auto-save, explicit save, version, publish ve rollback nasil ayrilmali?
- Undo/redo, diff ve iki mapping surumunu karsilastirma nasil sunulmali?
- Calistirma oncesi kaynak/hedef/context ozeti nasil tek ekranda dogrulanmali?

En az su ekranlar icin dusuk detayli wireframe veya metinsel yerlesim cizimi uret:

1. Proje listesi
2. Connection/topology yonetimi
3. Context esleme ekrani
4. Mapping editoru
5. Validation ve preview
6. Run detail ve hata inceleme

---

# 12. Gozlemlenebilirlik, lineage ve operasyon

OpenTelemetry, Prometheus uyumlu metrikler, yapilandirilmis log ve distributed tracing
gereksinimlerini deployment olcegine gore degerlendir.

En az su metrikleri kapsa:

- aktif/bekleyen/basarisiz run,
- okunan ve yazilan satir,
- rows/second,
- source read ve target write latency,
- batch boyutu,
- retry sayisi,
- connection pool kullanimi,
- checkpoint yasi,
- veri kalite hatasi,
- schema drift.

OpenLineage standardini ve alternatiflerini incele. Mapping tanimi ile gercek run
lineage'ini ayir. Kaynak kolon -> expression -> hedef kolon seviyesinde lineage'in ilk
surumde ne kadarinin tutulmasi gerektigini belirle.

Log, metrik, audit ve lineage'in ayni sey olmadigini veri modeli ve ekranlarda acikca
ayir.

Backup/restore, metadata migration, disaster recovery, PostgreSQL high availability,
worker deployment ve sifir kesintili surum yukseltebilme icin MVP ve sonraki faz
onerileri ver.

---

# 13. Test stratejisi

Asagidaki test katmanlarini tasarla:

- domain unit testleri,
- mapping parser/compiler golden testleri,
- connector contract testleri,
- veri tipi donusum matrisi testleri,
- Oracle -> Oracle integration testleri,
- PostgreSQL metadata migration testleri,
- Testcontainers veya gercek lisansli/izole Oracle test ortami secenekleri,
- hata enjeksiyonu,
- network timeout ve worker crash,
- idempotent retry,
- schema drift,
- buyuk veri performans testi,
- yetki izolasyonu ve guvenlik testleri,
- frontend component ve mapping editor E2E testleri,
- erisilebilirlik testleri.

Sentetik test verisi kullan. Gercek musteri verisi, secret veya connection bilgisi
repository, fixture, ekran goruntusu ve loglara girmesin.

Oracle surumleri, PostgreSQL surumu, MySQL surumu ve surucu versiyonlari icin
destek/uyumluluk matrisi oner.

---

# 14. Performans arastirmasi

MVP icin sayisal varsayimlar tanimla ve farkli hacimler icin benchmark plani uret:

- 100 bin satir,
- 1 milyon satir,
- 10 milyon satir,
- genis satir ve LOB iceren veri,
- yuksek gecikmeli ag,
- ayni anda birden fazla run.

Asagidaki parametreleri olc:

- fetch size,
- batch/array DML boyutu,
- commit araligi,
- worker bellek siniri,
- kaynak/hedef CPU ve I/O etkisi,
- network throughput,
- index ve constraint etkisi,
- staging ve direct load farki,
- paralel partition okuma/yazma.

Varsayilan ayarlari sabit "en iyi" deger olarak verme. Otomatik tuning, profil tabanli
ayar veya belgeli konfigurasyon arasinda karar ver. Kaynak uretim veritabanini asiri
yukten koruyacak rate limit, timeout ve calisma penceresi tasarla.

---

# 15. Build vs. reuse arastirmasi

Asagidaki urun ve standartlari en az tasarim referansi olarak incele:

- Oracle Data Integrator
- Informatica PowerCenter / IDMC
- Talend
- Apache NiFi
- Airbyte ve Connector Development Kit
- Meltano/Singer taps-targets
- Dagster
- Apache Airflow
- Debezium
- dbt
- OpenLineage/Marquez
- React Flow/xyflow ve alternatif node editorleri

Her biri icin su sorulari cevapla:

- Hangi parcayi iyi cozuyor?
- Bu urun icin fazla veya eksik kalan nedir?
- Hangi fikir veya standart yeniden kullanilabilir?
- Kutuphane olarak gommek, dis servis olarak entegre etmek veya yalniz desenini almak
  mi daha dogru?
- Lisans ve vendor lock-in etkisi nedir?

"Her seyi sifirdan yaz" ve "hazir urunu oldugu gibi kullan" uclarinin arasinda,
urunlesmeye uygun dengeli bir secim oner.

---

# 16. Beklenen arastirma ciktisi

Nihai rapor asagidaki bolumleri ayni sirayla icermelidir:

1. **Yonetici ozeti** - En fazla iki sayfada ana kararlar
2. **Gereksinimlerin yeniden ifadesi** - Varsayimlar ve acik sorular
3. **MVP ile gelecek vizyonunun siniri**
4. **Benzer urun analizi** - ODI dahil karsilastirma tablosu
5. **Onerilen hedef mimari** - Bilesen ve deployment diyagramlari
6. **Kontrol duzlemi/veri duzlemi ayrimi**
7. **Backend teknoloji karar kaydi**
8. **Frontend ve mapping editor teknoloji karar kaydi**
9. **PostgreSQL metadata modeli** - ER diyagrami ve tablo katalogu
10. **Logical/physical architecture ve context modeli**
11. **Kanonik mapping IR ve mapping yasam dongusu**
12. **Connector SPI ve capability modeli**
13. **Oracle -> Oracle execution tasarimi**
14. **Veri tipi donusum matrisi**
15. **Transaction, idempotency, retry ve checkpoint modeli**
16. **API yuzeyi ve ornek istek/cevap sozlesmeleri**
17. **Ekran bilgi mimarisi ve wireframe'ler**
18. **Kimlik, yetki, secret ve tehdit modeli**
19. **Audit, log, metrik ve lineage modeli**
20. **Test ve performans plani**
21. **Deployment ve operasyon modeli**
22. **Asamali uygulama yol haritasi**
23. **Risk kaydi** - Olasilik, etki, azaltma ve karar tarihi
24. **Acik kararlar ve prototiple dogrulanacak konular**
25. **Kaynakca** - Resmi ve guncel baglantilar

## 16.1 Zorunlu karar matrisleri

En az su matrisleri puanli ve gerekceli uret:

- backend dili/framework'u,
- mapping editor kutuphanesi,
- worker/orchestrator yaklasimi,
- connector gelistirme/reuse yaklasimi,
- mapping graph saklama modeli,
- secret yonetimi,
- scheduler,
- lineage standardi,
- Oracle yukleme stratejileri.

Puanlama kriterlerinin agirliklarini acikla. Salt toplam puanla karar verme; kritik
eleme kriterlerini ayrica belirt.

## 16.2 Zorunlu diyagramlar

Mermaid veya benzeri tasinabilir bir formatta en az sunlari uret:

- sistem baglam diyagrami,
- container/component diyagrami,
- metadata ER diyagrami,
- mapping validation ve execution sequence diyagrami,
- job state machine,
- logical schema + context -> physical schema cozumleme diyagrami,
- connector capability modeli,
- deployment diyagrami.

## 16.3 Zorunlu ADR taslaklari

En az su kararlar icin kisa ADR taslagi uret:

- backend stack,
- moduler monolit siniri,
- PostgreSQL metadata modeli,
- mapping IR,
- connector SPI,
- Oracle -> Oracle transfer stratejisi,
- mapping editor kutuphanesi,
- worker ve scheduler,
- secret yonetimi,
- idempotency ve calistirma garantisi.

---

# 17. Uygulama yol haritasi beklentisi

Yol haritasini somut kabul kriterleriyle fazlara ayir. En az su asamalari degerlendir:

## Faz 0 - Teknik spike'lar

- Oracle connection ve schema discovery
- Streaming/batch read ve batch insert benchmark'i
- NUMBER/DATE/TIMESTAMP/LOB tip denemeleri
- Basit React mapping editor prototipi
- Mapping IR -> Oracle SQL/transfer plan derleme prototipi
- Worker cancel/retry/checkpoint denemesi

## Faz 1 - Metadata ve proje cekirdegi

- Kullanici/proje/yetki
- Connection ve secret referansi
- Physical/logical schema ve context
- Schema snapshot ve drift

## Faz 2 - Oracle -> Oracle mapping MVP

- Kaynak/hedef secimi
- Kolon eslestirme
- Validation ve preview
- Insert ve kontrollu full load
- Manuel run ve calistirma gecmisi

## Faz 3 - Guvenilir calistirma

- Upsert/merge
- Watermark ve incremental load
- Retry, resume, cancel ve checkpoint
- Veri kalite/mutabakat
- Audit, metrik ve uyarilar

## Faz 4 - Urunlestirme

- Mapping version/publish/rollback
- Schedule
- Gelismis RBAC ve production onayi
- Backup/restore ve operasyon araclari

## Faz 5 - Heterojen connector'lar

- PostgreSQL connector
- MySQL connector
- Kanonik tip matrisi ve capability negotiation
- Oracle <-> PostgreSQL/MySQL contract testleri

Her faz icin:

- teslimatlar,
- bagimliliklar,
- kabul kriterleri,
- test kapilari,
- baslica riskler,
- ertelenenler

listelensin.

---

# 18. Basari kriterleri

Arastirma, asagidaki sorulara net cevap vermeden tamamlanmis sayilmaz:

- Neden secilen backend bu urun icin en uygun secimdir?
- Oracle -> Oracle veri akisinda satirlar uygulamadan nasil ve hangi batch sinirlariyla
  gececektir?
- Kaynak ve hedef ayri sistemlerken hangi calistirma garantisi gercekci olarak
  verilecektir?
- Yeni PostgreSQL veya MySQL connector'u eklemek hangi arayuzleri uygulamayi gerektirir?
- Mapping tanimi UI kutuphanesinden nasil bagimsiz kalacaktir?
- Logical schema ve context fiziksel baglantiyi nasil cozecektir?
- Bir mapping hangi validation'lardan gecmeden calisamayacaktir?
- Schema drift mevcut mapping'leri nasil etkileyecektir?
- Secret hicbir zaman UI, log veya metadata DB'ye acik metin sizmadan nasil
  kullanilacaktir?
- Kullanici neden yalniz yetkili oldugu projeyi, connection'i ve run'i gorecektir?
- Hangi metadata tablolari ilk migration'da kesinlikle bulunmalidir?
- MVP'de hangi ozellikler bilincli olarak yapilmayacaktir?
- Ilk kodlama sprintinden once hangi teknik spike'lar sonucu dogrulamalidir?

Nihai oneriler prototiplenebilir, test edilebilir ve asamali uygulanabilir olmali;
yalnizca genel teknoloji isimlerinden veya soyut mimari sloganlardan olusmamalidir.

---

# 19. Baslangic kaynak listesi

Arastirmaci bu listeyle sinirli kalmamakla birlikte once resmi kaynaklari incelemelidir:

- Oracle Database dokumantasyonu: <https://docs.oracle.com/en/database/oracle/oracle-database/>
- PostgreSQL resmi dokumantasyonu: <https://www.postgresql.org/docs/current/>
- MySQL resmi dokumantasyonu: <https://dev.mysql.com/doc/>
- React Flow dokumantasyonu: <https://reactflow.dev/>
- Airbyte connector gelistirme dokumantasyonu: <https://docs.airbyte.com/platform/connector-development/>
- Debezium dokumantasyonu: <https://debezium.io/documentation/>
- OpenLineage dokumantasyonu: <https://openlineage.io/docs/>
- OpenTelemetry dokumantasyonu: <https://opentelemetry.io/docs/>
- OWASP ASVS: <https://owasp.org/www-project-application-security-verification-standard/>
- OAuth 2.0 Security Best Current Practice: <https://datatracker.ietf.org/doc/html/rfc9700>

Kaynaklar arasinda celiski varsa surum ve tarih farkini belirt; uygulama karari icin
hangi kaynagin neden esas alindigini acikla.
