# SKY - DIT Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** UAT'ta 50 kolonlu test nesneleri, otomatik cihaz/DB ölçümleri ve kendiliğinden dolan SKY - DIT raporu hazırlamak.

**Architecture:** Python CLI kayıtlı bağlantıları kullanır, gerçek DIT çalıştırmalarını izler ve schemaVersion=1 JSON/HTML üretir. Şema üretimi, bağlantı/env toplama, örnekleme ve çalıştırma orkestrasyonu ayrı modüllerdir.

**Tech Stack:** Python 3, oracledb, psycopg, psutil, cryptography, requests; mevcut HTML/SVG ve Edge/Playwright doğrulaması.

**Spec:** `docs/superpowers/specs/2026-10-22-sky-dit-performance-design.md`

## Global Constraints

- Hacimler 100.000 / 250.000 / 500.000 / 1.000.000 / 2.500.000 / 5.000.000, üç tekrar.
- Harici repository bağlam indeksleyicisi kullanılmaz. Kullanıcının ilgili talimatı geçerlidir.
- Mevcut PROD bağlantısı ve kullanıcı değişiklikleri korunur. Parola rapor ve loglara girmez.
- DROP/TRUNCATE yok; uyuşmayan test nesnesinde durulur. Yük üretimi ayrı komuttur.
- İstemci ve DB host metrikleri ayrıdır; erişilemeyen değer null kalır ve neden yazılır.
- DIT testi uygulama motorunu kullanır; dış kopyalama benchmark'ı eklenmez.

## Review Focus

- Boş/bozuk bağlantı ve servis-SID karışıklığı: yanlış DB'ye sessiz düşme olmamalı.
- Süreç yeniden başlama/erişim kaybı: PID reuse ve erişilemeyen metrik sıfır olmamalı.
- Eksik/farklı tip/Unicode/binary/ondalık: satır sayısı eşit olsa da içerik kaybı yakalanmalı.
- Hata/timeout/kısmi commit: başarılı durum veya kesin hata sayısı uydurulmamalı.
- Yeniden hazırlama/rapor üretimi: mevcut tablo korunmalı, JSON/HTML bozulmamalı.

### Task 1: Tip zengin şema ve doğrulama

**Files:** `scripts/performance/dit_schema.py`, `scripts/performance/tests/test_schema.py`.

**Interfaces:** `COLUMNS`, `oracle_ddl`, `postgres_ddl`, `oracle_seed_sql`, `source_select`, `canonical_row`.

- [ ] 50 kolon, identifier koruması, bind parametreli veri üretimi ve canonical içerik için başarısız test yaz/çalıştır.
- [ ] Şema ve canonicalization uygula; `python -m unittest discover -s scripts/performance/tests -p test_schema.py` geçir.

### Task 2: Bağlantı, envanter ve saniyelik ölçüm

**Files:** `scripts/performance/dit_config.py`, `dit_metrics.py`, `tests/test_infrastructure.py`, `requirements.txt`.

**Interfaces:** registry metadata connection, encrypted profile, `HostSampler`, optional metric agent, safe inventory builders.

- [ ] Yanlış servis/SID, şifre çözümü, CPU normalizasyonu ve eksik örnek için başarısız testleri çalıştır.
- [ ] Kayıtlı DB bağlantılarını, psutil örneklemesini ve yetki kontrollü DB envanterini uygula.
- [ ] Gerçek cihazdaki kısa sampler kontrolüyle fiziksel RAM/CPU değerlerini doğrula; erişim eksikliği raporda kalsın.

### Task 3: CLI, hazırlama, izleme ve otomatik HTML

**Files:** `scripts/performance/sky_dit_perf.py`, `dit_report.py`, `tests/test_runner.py`, `config.example.json`, `docs/testing/performance/README.md`.

**Interfaces:** `inventory`, `prepare`, `seed`, `monitor`, `suite`, `agent`; atomik JSON ve HTML çıktısı.

- [ ] Boş rapor enjeksiyonu, tekrar hazırlama güvenliği, timeout ve secret sızıntısı için başarısız testleri çalıştır.
- [ ] Yalnız yeni UAT bağlantısını kaydet; kaynak/hedef tabloyu oluştur ve 50 kolon doğrula.
- [ ] Test üretimi ve application-run izlemeyi uygula, çalıştırma kılavuzunu yaz.
- [ ] `python -m unittest discover -s scripts/performance/tests` ve CLI gerçek envanter/hazırlama kontrollerini çalıştır.

### Task 4: SKY - DIT rapor entegrasyonu

**Files:** `docs/testing/performance/rapor.html`, `verify-report.cjs`.

**Interfaces:** mevcut schemaVersion=1, optional `collection` coverage metadata.

- [ ] Branding ve coverage notlarının tarayıcı davranışını doğrulayan kontrol ekle.
- [ ] Dış bağımlılıksız rapor ve verili HTML yeniden açılmasını `node docs/testing/performance/verify-report.cjs` ile doğrula.
- [ ] Gerçek hazırlama raporunu aç ve büyük test yapılmadığını açıkça teslim et.

## 22.10.2026 arayüzden 100 bin kayıt ilerleme kaydı

- [x] Kaynak 100.000 sentetik kayıt uygulama dışında Oracle'a yazıldı; ayrı bağlantıyla commit sonrası 100.000 benzersiz ID (1..100.000) doğrulandı.
- [x] Oracle ve PostgreSQL modelleri arayüzde Reverse Engineer ile 50 kolon / 1 constraint olarak kaydedildi.
- [x] Kullanıcı alttaki Models ağacından iki tabloyu sürükledi; agent 49 öneriyi uygulayıp toplam 50/50 eşlemeyi, ID filtresini ve APPEND modunu kaydetti.
- [x] Kullanıcı onayıyla yalnız test şemasının Work Area Policy yönetim paneli ve Physical aktarım bütçesi alanları eklendi; RED→GREEN testleri, 420 frontend testi ve build geçti. İşlem başına limitler: 100.000 satır / 1 GiB; batch/fetch 1000.
- [x] UI'dan v2 / scenario v1 / UAT publication `35617abd-e08c-4eb3-afe7-0994cfbbfb72` üretildi. Kaynak SKY_UAT/TTBPSKY/TTBP, hedef PG/SKY_DIT_PERF doğrulandı.
- [x] İlk UI Run `0ef3e5ea-e745-4320-8f6a-99c1c706b054` kuyrukta kalınca UI'dan iptal edildi; aktarım adımı başlamadı. Neden: mevcut `.env` worker poll süresi 3.600.000 ms. Toplanan bu deneme gerçek aktarım başarısı sayılmaz.
- [x] Test süreçleri bittikten sonra `.env` dosyası değiştirilmeden süreç argümanıyla 2000 ms worker poll kullanılarak yeni UI Run ve ölçüm yapıldı: `e36c1a5f-9b8b-4600-9230-4ef131516fe5`.
- [x] Başarılı aktarım: 180,773588 sn / 100.000 satır (553,178 kayıt/sn), 186 kaynak örneği. Oracle ve PostgreSQL'in 50 kolon SHA-256 özeti aynı: `1586c459c58b33ad6baee9f20e7d8d7ba78e257cf4e4e6ef9f9305c7a22ff1cb`. Hedef toplamı 100.000; eksik/mükerrer kayıt yok. Ölçüm sonrası doğrulama 166,244 sn sürdü ve örnek/süre/event hash'i değişmedi.
- [x] JSON ve bağımsız HTML'e üç gerçek bulgu eklendi. Varsayılan ortam UI'dan PROD'a geri alındı; backend özgün `.env` tarama ayarıyla yeniden açıldı. Sonraki hacim veya tekrar otomatik başlatılmadı; tablo adları korunuyor.

Doğrulama okuyucusu notu: JDBC locator `free()` çağrısının her LOB hücresinde Oracle `isOpenLob` RPC'sine yol açtığı thread dump ile saptandı. Bounded benchmark LOB'ları materialized getString/getNString/getBytes ile okuyan değişiklik RED→GREEN doğrulandı; Python 27 test geçti. Yavaş ilk doğrulama okuyucusu durdurulup yalnız içerik kontrolü tekrarlandı; uygulama aktarımı veya CPU/RAM serileri tekrar üretilmedi.

Review: SQL önizlemesi kaynak katalog pinini ortamda çözülmüş binding'den alacak şekilde regresyon testiyle düzeltildi. Minor/deferred: Physical sayısal limit girdileri boş ara düzenleme durumunu korumuyor; geçerli tam değer yapıştırılarak değiştirilebiliyor. Üst Project Objects ile alt Models ağacının karışması kullanıcı geri bildirimi olarak performans README'sine kaydedildi; bu UX düzenlemesi henüz uygulanmadı.

## 22.10.2026 — 100 bin bloğu 3/3 tamamlandı

- [x] Kullanıcı onayıyla bekleyen PROD Run `6e057ad2-7cfe-41d6-b24a-a224e15ef3ae` UI'dan iptal edildi; `TTBP_PAKET_3_SAAT` zamanlaması test boyunca UI'dan duraklatıldı. Başka PROD işi başlatılmadı.
- [x] Aynı immutable UAT yayını ve aynı APPEND/batch/fetch 1000 ayarlarıyla iki yeni Run arayüzden başlatıldı. Tekrar 2: `c09d8f3c-d7a8-4a91-bc8e-6e781bd1b4ac`, 151,958988 sn (658,072 kayıt/sn), 163 örnek. Tekrar 3: `e9959147-7895-4b07-a62f-47ed72df1f7a`, 160,528138 sn (622,944 kayıt/sn), 172 örnek.
- [x] İkinci ve üçüncü tekrar öncesi yalnız PostgreSQL `"SKY_DIT_PERF"."SKY_DIT_PERF_50"` hedefinde 100.000 test kaydı transaction/guard ile DELETE edildi ve boş hedef doğrulandı; Oracle kaynağı değiştirilmedi, tablolar yeniden adlandırılmadı. Hedef temizliği ölçüm dışında kaldı.
- [x] Her tekrarın 50 kolon streaming SHA-256 kontrolü geçti: kaynak/hedef `1586c459c58b33ad6baee9f20e7d8d7ba78e257cf4e4e6ef9f9305c7a22ff1cb`; her biri 100.000 kayıt, eksik/mükerrer yok. Son bağımsız sayım iki DB'de 100.000 ve ID 1..100.000.
- [x] İlk iki tekrarın özeti üçüncü Run öncesi yazıldı; üçüncü Run sırasında collector tek yazıcı olarak HTML/JSON serilerini canlı güncelledi. Son 3/3 özet ve medyan 160,528138 sn HTML'e işlendi; gömülü JSON ile sonuç JSON'u eşit doğrulandı. İptal edilmiş eski kuyruk denemesi istatistik dışında tutuldu.
- [x] Transfer fazı cihaz CPU aritmetik ortalaması tekrar 1/2/3: %18,314 / %12,977 / %10,875; backend: %1,094 / %0,862 / %0,650. Backend working set tepe: 553,777 / 454,633 / 444,742 MiB. İlk Run PID 32920, tekrar 2/3 PID 26156; cache kontrol edilmedi ve normal masaüstü yükü sürdü. İçerik doğrulaması örnekleme/süre penceresi dışında.
- [x] Test sonunda backend özgün `.env` ayarlarıyla açıldı (geçici 2000 ms argümanı kaldırıldı; özgün poll 3.600.000 ms). Zamanlama UI'dan yeniden etkinleştirildi ve DB'de AKTIF doğrulandı; özgün `0 0 */1 * * *` cron/Europe-Istanbul değişmedi, sıradaki tetikleme 14:00. Zamanlamanın adı ile cron sıklığı arasındaki mevcut fark değiştirilmedi.
- [x] Daha büyük hacim eklenmedi veya çalıştırılmadı. Çıktılar: `.data/performance/sky-dit-uat/sky-dit-report.html`, `sky-dit-results.json`, `100k-r2-success.jpg`, `100k-r3-success.jpg`, `schedule-restored.jpg`.
