# Knowledge Module Uygulama İlerlemesi

## Güncel durum — 15 Eylül 2026, worker bağlantısı ve kabul kapısı

Alttaki bölümler tarihsel ilerleme notlarıdır. Güncel uygulama durumu bu bölümdür;
önceki “worker'a bağlı değil” ifadeleri artık güncel değildir.

- `ORACLE_STAGED_MAPPING_V1` plan çözücüsü, worker dispatch, amaçla sınırlı staging
  oturumları ve atomik hedef yayın adaptörü bağlandı. Flag varsayılan olarak kapalıdır.
- `AKIS_EXECUTION_STAGED_RUNTIME_ENABLED=false` korunur. Flag açıkken yeni yayın,
  değişmez senaryo/plan/binding/politika/hash kontrollerinden geçmeden yürütülebilir olmaz.
- Bağlantı/fiziksel şema prefix mirası yanında çalışma alanı politikası eklendi:
  etkinlik, aynı şemaya izin, nesne/satır/byte kotası ve saklama süresi.
  Saklama süresi belirsiz nesneleri otomatik silme yetkisi değildir.
- CKM zorunlu alan ve benzersiz anahtar kontrolleri typed çalışma verisinde çalışır.
  Hedef oturumu açılmadan önce yayımlanan adımların başarı kayıtları doğrulanır;
  politika hedef kilidi altında tekrar kontrol edilir.
- V019 çalışma alanı politikası, V020 KM adım günlüğü eklendi. Günlük SQL'indeki
  yayın ilişkisi `calistirma → is_talebi → yayin` olarak düzeltildi ve gerçek PG testi eklendi.
- Başarılı çalışma tablosu temizliği kayıtlı sahiplik, nesil, Oracle object ID ve kolon
  yapısı doğrulanarak yapılır. Başarısız/belirsiz nesnelerde otomatik DROP/tekrar yoktur.
- KM sonuç ekranında adımlar, kaynak/hedef satırları ve çalışma nesneleri görünür.
  Yetkili mutabakat eylemi hedef ledger'ını okur, hedef DML'yi tekrarlamaz.
  Mutabakat sonucu ilk belirsiz adım kaydını silmeden ayrıca gösterilir.

Doğrulama kapsamı:

- Son backend birim/regresyon paketi: 500 test, 0 hata/başarısızlık.
- V001–V020 migration zinciri izole PostgreSQL'de geçti. Üç repository testi:
  prefix/politika, KM sürüm sabitleme, adım günlüğü ve lease/proje sınırları.
- Frontend build ve lint geçti. İlgili altı dosyada 20 regresyon testi;
  ayrıca KM sonuç/mutabakat görünümü için 2 test geçti.
- Gerçek Edge tarayıcısında iki izole bileşen senaryosu geçti. KM sonuç ekranı
  açık/koyu tema, 390/1366 genişlikte incelendi; sayfa düzeyinde yatay taşma yok.
  Dar ekranda geniş tablolar kendi içinde yatay kayar.
- Bunlar canlı Oracle kabulü veya girişten yayına kadar tam ürün kabulü değildir.
  Kaynak payload hash'i aktarım akışında hesaplanır; hedefte bütün satırların kriptografik
  yeniden okunması yapıldığı iddia edilmez. Count/shape/type ve ledger kanıtı doğrulanır.

Henüz kapanmamış kabul maddeleri:

1. Ürün ekranından KM oluşturma → mapping bağlama → sürüm → önizleme → yayın →
   worker → sonuç/temizlik zincirinin izole Oracle ile uçtan uca kabulü.
2. Gerçek Oracle'da >1.000 satır, kota/CKM hatası, bağlantı kaybı ve commit belirsizliği testleri.
3. DBA'nın münhasır çalışma hesabı, izinleri, hedef ledger kurulumu ve kaynak/hedef test
   nesnelerini onaylaması. Çalışma şemasının diğer süreçlerce değiştirilmemesi gerekir.

Mevcut iş tablolarında yıkıcı test yapılmadı; uygulama veritabanına yeni migrationlar
uygulanmadı. Canlı kabul için kullanıcıdan izole ortam/bağlantı/çalışma şeması ve
kaynak/hedef test tablolarının belirtilmesi beklenir. Tamamlandı denmemelidir.

Kurulum ve kabul adımları: `KNOWLEDGE_MODULE_ACCEPTANCE.md`.

## 15 Eylül 2026 — F0 sözleşme hizalama

Uygulananlar:

- Yeni MAPPING taslağı şema 2 olarak oluşturulur. Mevcut şema 1 taslaklar otomatik yükseltilmez;
  Yazma Stratejisi alanında açık geçiş eylemi vardır. Kaydetme ve yeni değişmez sürüm oluşturma ayrıdır.
- Editör adı/katalog/snapshot ipuçları `dataset.ui` altında saklanır ve editörde geri açılır.
  Bunlar fiziksel binding değildir. Bilinmeyen semantik alanlar silinmez; motor reddetmeye devam eder.
- Varsayılan APPEND sessizce yıkıcı tam yenilemeye çevrilmez.
- `POST /api/v1/projects/{projectUuid}/mapping-design/assess` yetkili, veri yazmayan yapı kontrolüdür.
  Mevcut pilotun gerçek şekil doğrulamasını kullanır. Sonuç hiçbir zaman fiziksel yürütmeyi onaylamaz.
- UI'da desteklenmeyen strateji, satır sınırı, tam yenileme etkisi ve eksik ortam/binding kontrolleri görünür.
- Düzenleme sonrası eski kontrol sonucu gizlenir; geç gelen cevap yeni içeriğe uygulanmaz.
- Mevcut publication/pilot hash sözleşmesi ve prosedür yürütme kodu değiştirilmedi.

Doğrulama:

- Backend birim paketi: 431 test, hata yok; ardından eklenen senaryo→pilot uyum testiyle
  ScenarioPlanCompilerTest ayrıca tekrar çalıştırıldı ve geçti.
- Yeni frontend testleri: 7; mapping/binding/App regresyon grubu: 6;
  son binding/prosedür/SQL panel regresyon grubu: 22 geçti. Gruplar örtüşür, toplam olarak toplanmamalıdır.
- Frontend production build ve lint geçti.
- Gerçek tarayıcıda mock API ile izole bileşen testi geçti: açık/koyu tema,
  390 ve 1366 genişlik, açık kontrol, sürüm geçişi ve eski sonuç gizleme.
- Bu tarayıcı testi giriş→mapping→yayın akışının veya Oracle çalışmasının kabulü değildir.

Kalanlar:

- F0 tam kabul kapısındaki gerçek arayüz→kalıcı sürüm→binding→ortam yayını senaryosu henüz çalıştırılmadı.
- F1 sürümlü KM sözleşmesi, bağımlılık çözümü ve fiziksel plan/SQL önizlemesi henüz uygulanmadı.
- F2/F3 staging nesnesi, streaming aktarım ve yeni IKM yürütmesi henüz uygulanmadı.
- Canlı staging kurulumu ve test verileri için mimari belgedeki DBA/ortam kararları gereklidir.

`mapping-design/assess`, KM fiziksel plan önizleme API'si değildir. Bu fark ürün metninde de korunur.

## 15 Eylül 2026 — AKIŞ KM dili ve prefix temeli

Uygulananlar:

- `AKIS_KM/1` ayrıştırıcısı: MODUL ve ADIM bildirimleri, tür/konum/işlem izin listesi,
  boyut sınırları, yinelenen kimlik ve CREATE → TRANSFER → SEAL sıra kontrolü.
- KM editöründe dil tanımı ve yetkili `/knowledge-language/validate` API'si. Dil doğrulaması
  yürütme onayı değildir. Eski dolu modüller sessizce dönüştürülmez.
- Sonlu yorumlayıcı tüm LKM/CKM/IKM metinlerini ilk etkiden önce doğrular; bilinmeyen slotu reddeder.
  CKM hatası entegrasyonu durdurur. Runtime portu vardır; Oracle adaptörü henüz bağlı değildir.
- Bağlantı varsayılanı ve fiziksel şema özel prefix API/editörleri. Kayıt için bağlantı testi aranmaz.
  Yetki, proje sınırı, iyimser sürüm kontrolü ve güvenli karakter kontrolleri uygulanır.
- V017 migration dosyası eklendi, veritabanına henüz uygulanmadı.
- İsim üreticisi sabit AKIS_ işareti, özel prefix ve proje/run/nesil/slot hash'i üretir;
  30 ASCII karakter sınırı vardır. İsim sahiplik kanıtı değildir.

Doğrulama: backend test paketi geçti; yeni dil/yorumlayıcı/prefix/controller testleri ve
iki frontend prefix testi geçti. Frontend build ve lint geçti (ardından yalnız erişilebilir
alan kimlikleri ve testler eklendi). Bunlar nihai uçtan uca kabul değildir.

Tamamlanmayan uygulama: değişmez KM bağımlılıkları ve yayın snapshot'ına prefix sabitleme,
fiziksel staging planı/önizleme, kalıcı sahiplik yaşam döngüsü, JDBC streaming adaptörü,
mevcut hedef ledger/fencing ile IKM bağlantısı ve tam arayüz akışı.
Bu eksikler yalnız canlı ortam bilgisine bağlı değildir; kodlama çalışması da gerektirir.

Canlı kabul ön koşulu: kullanıcının ayrı test bağlantısı/ortamı/çalışma şemasını belirtmesi.
Mevcut iş tabloları üzerinde kör CREATE/DROP/TRUNCATE testi yapılmadı.

## 15 Eylül 2026 — sürüm, plan ve staging bileşenleri

Bu turda eklenenler:

- Mapping içerik sürümü 3 ve KM içerik sürümü 2. Eski sürümler korunur.
- `KnowledgeModuleRegistry`: aynı projedeki değişmez KM UUID/hash doğrulaması;
  yeni mapping sürümünde `MODUL_KULLANIR` bağımlılıkları.
- `StagedMappingPlanner`: ortam çalışma şeması, bağlantı sürümü, snapshot referansları,
  prefix değerleri, modül kaynakları, seçenekler ve adımları kanonik plana sabitler.
- Yayın önizleme API'si ve arayüzü. Önizleme özeti değişmişse yayın reddedilir.
  İş veritabanına bağlanmadan metadata üzerinden plan hazırlanır.
- Mapping editöründe çalışma şeması, LKM/IKM/isteğe bağlı CKM ve satır/byte/batch limitleri.
  KM'ye geçiş ancak açık atomik tam yenileme seçimiyle mümkündür; APPEND sessizce dönüştürülmez.
- `JdbcStagingTransfer`: tek cursor, typed bind, sınırlı batch belleği, satır/byte kotası,
  boş kaynak reddi ve belirsiz commit'te tekrar etmeme. İzole 1.201 satır testi mevcut.
- V018 çalışma nesnesi tablosu ve lease/nesil kontrollü `WorkObjectStore`.
- `OracleWorkTableManager`: ayrı çalışma hesabı/DB kimliği doğrulaması, kayıtlı tahsis,
  CREATE öncesi sahiplik kaydı ve belirsiz DDL'de inceleme durumu. Henüz worker'a bağlanmadı.
- `JdbcStagedAtomicRefreshWriter`: aynı bağlantıda hedef DML + Oracle ledger;
  commit belirsizliği ve zaten kayıtlı yayın için ikinci DML yapmama testleri.
  Bu bileşen henüz production worker tarafından çağrılmaz.

Doğrulananlar:

- Backend birim paketi geçti; yeni atomik writer testleri 4, streaming testleri 5.
- Frontend regresyon grubu 47 test; ayrıca önizleme/storage grubu 6 test geçti
  (örtüşen testler vardır, toplam olarak toplanmamalı).
- Frontend production build ve lint geçti.
- V001–V018 boş, geçici PostgreSQL veritabanında uygulandı. Prefix mirası/sürüm/kapsam
  ve sabit KM sürümü/hash için 2 gerçek repository testi geçti.
- Test PostgreSQL veritabanları temizlendi. Uygulamanın mevcut veritabanına migration uygulanmadı.
- Canlı Oracle DDL/DML, tam tarayıcı kabulü veya >1.000 satır gerçek Oracle kabulü yapılmadı.

**Henüz tamamlanmayanlar:** yeni capability için worker dispatch ve bağlantı adaptörleri,
çalışma alanı yetki/kota politikası, KM yorumlayıcısına staging/CKM/writer bileşenlerinin
tam bağlanması, sahiplik doğrulamalı gerçek cleanup ve mutabakat akışı, çalıştırma detayları,
nihai uçtan uca kabul. Bunlar yalnız ortam bilgisine bağlı değil; ek uygulama gerektirir.

Bu nedenle schema-3 yayınları hâlâ `DEFINITION_ONLY` olarak saklanır. Yürütülebilir capability
erken açılmadı; mevcut prosedür worker'ına bu planlar gönderilmemelidir.
