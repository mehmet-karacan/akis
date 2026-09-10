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

Test geçici bir veritabanı oluşturur, üç migrasyonu uygular, ikinci Flyway
çalıştırmasının no-op olduğunu doğrular; 58 tabloyu, dokuz tanım türünü, 20 RBAC
yetkisini, varsayılan proje rollerini ve append-only negatif testlerini denetler.
Ardından geçici veritabanını siler; geliştirme veritabanına ve Oracle
kaynak/hedeflerine dokunmaz.

Testler geçtikten sonra baseline'ı `.env` içindeki yerel geliştirme PostgreSQL'ine
Flyway ile uygulamak için:

    .\database\migrate.ps1

Komut aynı migration checksum'ıyla tekrar çalıştırılabilir; uygulanmış sürümü
yeniden yürütmez. Migration değiştirilirse Flyway checksum uyuşmazlığıyla durur.
