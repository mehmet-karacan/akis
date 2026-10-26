# Data Integrator Tools Video Demonstration Plan

> **For agentic workers:** This document is a recording plan; execute the videos in order and keep the same demo project and synthetic data throughout.

**Goal:** Show the complete Oracle-to-PostgreSQL data integration lifecycle in short, understandable videos.

**Architecture:** Use one synthetic Oracle source table and one PostgreSQL target table. Demonstrate discovery, sensitive-field classification, interface mapping, procedure input, validation, publication, execution, and audit without exposing real data.

**Tech Stack:** Data Integrator Tools, Oracle, PostgreSQL.

**Spec:** The agreed demo tables are `INNOVA_ODI.KAYNAK_TABLO_ORACLE` and `akis_demo.hedef_tablo_postgre` with `ID`, `KULLANICI_ADI`, `EPOSTA`, `SIFRE_HASH`, and `KAYIT_ZAMANI`.

## Global Constraints

- Use only synthetic demo rows; do not show production data or real credentials.
- Keep `SIFRE_HASH` masked and never use a plaintext password.
- Keep the same project, tables, and row set in every video.
- Use Turkish UI copy for the main recording; optionally repeat key screens in English.

## Recording Sequence

### Video 1 — Giriş ve çalışma alanı

- Login ekranını ve Data Integrator Tools kurumsal kimliğini göster.
- Türkçe dil seçimini, proje/çalışma alanını ve güvenli erişim mesajını anlat.

### Video 2 — Kaynak ve hedef bağlantıları

- Oracle `Sky Prod / INNOVA_ODI` bağlantısını göster.
- PostgreSQL hedef bağlantısını göster.
- Bağlantı testi, ortam bilgisi ve kimlik bilgilerinin ekranda açığa çıkmamasını anlat.

### Video 3 — Reverse / metadata keşfi

- Oracle bağlantısından `INNOVA_ODI` şemasını seç.
- `KAYNAK_TABLO_ORACLE` tablosunu reverse et.
- Kolonların ve veri tiplerinin metadata olarak çıkarıldığını göster.

### Video 4 — Hassas alanların işaretlenmesi

- `EPOSTA` ve `SIFRE_HASH` alanlarını hassas olarak işaretle.
- Hassas alanların çalışma tanımında korunması ve ekran/log çıktılarında maskelenmesini anlat.

### Video 5 — Interface oluşturma

- Kaynak tabloyu ve PostgreSQL hedef tablosunu seç.
- Aynı isimli kolonların otomatik eşleşmesini göster.
- `KAYIT_ZAMANI` alanının kaynakta elle girilen değer olarak hedefe taşındığını anlat.

### Video 6 — Procedure ve manuel parametre

- Interface içinde procedure/SQL adımını oluştur.
- `KAYIT_ZAMANI` veya benzeri bir çalışma değişkenini manuel gir.
- Parametrenin işlem içinde kullanılmasını ve kaydedilebilir/versionlanabilir olmasını göster.

### Video 7 — Kontrol ve simülasyon

- Eksik kolon, tip uyumsuzluğu ve hassas alan kontrollerini çalıştır.
- Pre-run/simülasyon sonucunu ve düzeltilmesi gereken uyarıları göster.

### Video 8 — Yayınlama ve versiyonlama

- Tanımı versiyonla.
- Yayınlama öncesi doğrulama, onay ve değişiklik geçmişini anlat.
- Yayınlanan planın değiştirilemez/sürüm bazlı çalıştığını göster.

### Video 9 — Çalıştırma ve izleme

- Oracle’dan PostgreSQL’e aktarımı başlat.
- Adım durumu, aktarılan satır sayısı, süre ve hata bilgisini göster.
- Hassas değerlerin run ekranında görünmediğini doğrula.

### Video 10 — Sonuç ve denetim izi

- PostgreSQL hedef tablosundaki sentetik sonuçları kontrol et.
- Kaynak/hedef satır sayılarını karşılaştır.
- Run geçmişi, audit kaydı ve tekrar çalıştırma davranışını göster.

## Review Focus

- Gerçek parola veya kişisel veri videoya girmemeli; her ekran kaydından önce değerleri kontrol et.
- `SIFRE_HASH` kaynak/hedef ekranlarında maskelemeli görünmeli.
- Reverse sonrası kolon tipi ve hassasiyet bilgisinin interface’e taşındığı doğrulanmalı.
- Procedure manuel parametresi boş veya hatalı olduğunda anlaşılır doğrulama göstermeli.
- Başarılı aktarım sonrası kaynak/hedef satır sayıları aynı olmalı.
