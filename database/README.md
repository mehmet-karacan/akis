# Metadata veritabanı

PostgreSQL metadata baseline şeması `entegrasyon` namespace'inde sürümlü SQL
migrasyonlarıyla tanımlanır. Baseline 58 tablo içerir ve ortak tanım kataloğu
üzerinden şu dokuz geliştirme nesnesini saklar:

- Mapping ve Yeniden Kullanılabilir Mapping
- Paket ve Prosedür
- Değişken ve Sequence
- Kullanıcı Fonksiyonu ve Knowledge Module
- Load Plan

Scenario, düzenlenebilir tanım değildir; doğrulanmış tanım sürümünden üretilen
immutable executable olarak ayrı tabloda tutulur. Proje/klasör sahipliği, topology,
model/datastore keşfi, yayın, zamanlama, run/session/step/task, değişken ve Sequence
değer olayları, checkpoint, audit ve lineage aynı baseline içindedir.

Şema testini çalıştırmak için yerel PostgreSQL container'ı açıkken:

    .\database\test-schema.ps1

Test geçici bir veritabanı oluşturur; önce V003 legacy run kayıtlarını hazırlar,
V004, V005 ve V006 yükseltmelerini uygular ve son Flyway çalıştırmasının no-op olduğunu doğrular.
58 tabloyu, dokuz tanım türünü, 24 RBAC yetkisini, dört varsayılan proje rolünü,
run durum makinesini, DB-time claim/heartbeat davranışını, hedef sahipliği ile
fencing neslini, checkpoint kanıtını, tenant FK'larını ve append-only negatif
testlerini denetler. PostgreSQL fonksiyonları Oracle veya başka bir ağa bağlanmaz;
varsayılan heartbeat aralığı worker yapılandırmasında 10 saniye, varsayılan lease
süresi ise fonksiyon parametresinde 60 saniyedir.
Ardından geçici veritabanını siler; geliştirme veritabanına ve Oracle
kaynak/hedeflerine dokunmaz.

V006, pilot worker için lease ve target generation doğrulamalı
`HAZIRLANIYOR -> CALISIYOR -> YAYINLANIYOR` geçişlerini, atomik
checkpoint/başarı/target-release tamamlamasını ve `SONUC_BELIRSIZ` sonrasında
ayrı reconciliation lease, heartbeat ve sonuçlandırma sınırını ekler. Marker
yokluğu kararı yalnız Oracle fence bariyeri dışarıda tamamlandıktan sonra bu
fonksiyonlara verilebilir; migration kendi başına Oracle'a bağlanmaz.

Oracle 19c hedefinde kullanılacak target-local ledger/fencing DBA scriptleri,
transaction sözleşmesi ve doğrulama adımları `database/oracle/README.md`
belgesindedir. Bu scriptler otomatik migration/CI parçası değildir ve bağlantı
bilgisi içermez.

Testler geçtikten sonra baseline'ı `.env` içindeki yerel geliştirme PostgreSQL'ine
Flyway ile uygulamak için:

    .\database\migrate.ps1

Komut aynı migration checksum'ıyla tekrar çalıştırılabilir; uygulanmış sürümü
yeniden yürütmez. Migration değiştirilirse Flyway checksum uyuşmazlığıyla durur.
