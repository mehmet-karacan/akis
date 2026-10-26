# SKY - DIT performans testi altyapısı

Kullanıcının onayladığı deney: Oracle SELECT → PostgreSQL INSERT, 100.000 / 250.000 / 500.000 / 1.000.000 / 2.500.000 / 5.000.000 kayıt, kademe başına üç tekrar. Test cihazında günlük işler devam eder. Gerçek aktarım çalışmaları uygulamanın kendi motorundan başlatılır/izlenir; ayrı Python kopyalayıcısı DIT performansı diye raporlanmaz.

Kullanıcı UAT için ayrı servis ve hesap verdi. Mevcut SKY/TTBP2/PROD kaydı değiştirilmez; SKY_UAT/TTBPSKY/TTBP kaydı ve UAT ortamı eklenir. Parola yalnız yerel kayıtlı bağlantının mevcut AES-GCM şifrelemesiyle saklanır; kaynak koduna, plana, konsola veya HTML'e yazılmaz.

Kaynak `TTBP.SKY_DIT_PERF_50`, hedef kayıtlı PG bağlantısında ayrılmış `sky_dit_perf."SKY_DIT_PERF_50"`. Tam 50 kolon: sayılar, hassas ondalıklar, Unicode, sabit/uzun metin, null, tarih/saat/saat dilimi, RAW/BLOB/CLOB. Hazırlama yalnız boş test nesneleri oluşturur. Veri üretimi ayrı komuttur; mevcut nesne uyumsuzsa işlem durur, DROP/TRUNCATE uygulanmaz. Her hacim için ID aralığı sorgusu sağlanır.

Python CLI bağlantı envanterini ve cihaz donanımını toplar; psutil ile toplam cihaz ve açıkça seçilmiş backend/worker PID ağacını saniyelik örnekler. Süreç CPU toplam mantıksal kapasiteye normalize edilir. Oracle OS istatistikleri yetki varsa toplanır. PG/Oracle sunucu donanımı ve host CPU/RAM erişilebilir bir yerel/uzak ajan varsa otomatik alınır; aksi halde erişim eksikliği açıkça raporlanır, konteyner belleği host RAM gibi sunulmaz.

Rapor üreticisi JSON'u mevcut bağımsız HTML'e gömer ve her güncellemede atomik kaydeder. `inventory` ve `prepare` gerçek ortam bilgili, ölçümsüz rapor oluşturur. `monitor` mevcut DIT run UUID'sini izler; `suite` açıkça verilen proje/yayın eşlemeleri ile normal HTTP API üzerinden kademeleri başlatır. Uygulama kapalıysa hazırlık yapılabilir; büyük test otomatik başlamaz.

Veri doğrulaması sayaç eşitliğine ek olarak 50 kolonun sıralı, tip farkları normalize edilmiş streaming digest kontrolüdür. Doğrulanamayan çalışma passed olamaz. İzleme hatası/timeout mevcut uygulama çalışmasını bitmiş gibi göstermez. Ölçülemeyen aşama süreleri null kalır. Anlık HTML ayrı tarayıcı yenilemesiyle güncellenebilir; kayıtlı rapor dış kaynağa ihtiyaç duymaz.

Kabul: gerçek envanter ve hazırlama raporu, kaynak/hedefte doğrulanmış 50 kolonlu boş tablolar, tekrarlanabilir CLI, test edilmiş sampling/rapor/koruma davranışı ve mevcut HTML tarayıcı kontrolleri. Büyük hacim testi sonraki çalıştırma aşamasıdır.
