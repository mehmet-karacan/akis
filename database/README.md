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

Test geçici bir veritabanı oluşturur, migrasyonu tek transaction içinde uygular,
58 tabloyu ve dokuz tanım türünü denetler, sahiplik ve immutable kayıt negatif
testlerini çalıştırır; ardından geçici veritabanını siler. Geliştirme veritabanına
ve Oracle kaynak/hedeflerine dokunmaz.

Testler geçtikten sonra baseline'ı `.env` içindeki yerel geliştirme PostgreSQL'ine
Flyway ile uygulamak için:

    .\database\migrate.ps1

Komut aynı migration checksum'ıyla tekrar çalıştırılabilir; uygulanmış sürümü
yeniden yürütmez. Migration değiştirilirse Flyway checksum uyuşmazlığıyla durur.
