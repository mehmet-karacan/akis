# Execution control rollout note

Bu not, güvenli durdurma, aktif JDBC/COPY iptali ve paket yürütme değişikliklerinin
operasyonel sınırlarını özetler.

## Davranış sözleşmesi

- Kuyruktaki bir çalıştırma doğrudan `IPTAL` olur; sahiplenilmiş veya çalışan bir
  çalıştırmada stop isteği önce kalıcı `IPTAL_ISTENDI` durumuna yazılır.
- Worker heartbeat’i stop isteğini gördüğünde yeni iş başlatmaz, aktif owner-scoped
  Oracle/JDBC oturumlarını iptal etmeyi dener ve sonucu kesinleşmemiş işlemleri
  başarıyla kapatmaz.
- Runtime operasyonları `(runUuid, generation)` anahtarıyla kaydedilir; başka bir
  çalıştırmanın oturumu iptal edilmez.
- Paket adımları varsayılan olarak `SYNC` çalışır. `ASYNC` adımı child run’ı
  dispatch eder, parent cursor’ı ilerletir ve açık child kalırken parent’ı başarılı
  kapatmaz. `VARIABLE_*` adımları yalnızca `SYNC` olabilir.

## Rollout

1. Önce additive migration’ı (`V065__paket_cursor_async_join.sql`) izole bir
   PostgreSQL ortamında uygula ve migration/integration testlerini çalıştır.
2. Worker ve API’yi birlikte dağıt; eski UI’ın okuyamadığı yeni durumları üretim
   trafiğine açmadan önce frontend status mapping’inin dağıtıldığını doğrula.
3. ASYNC paket kabulünü kademeli aç. İlk aşamada parent/child event’leri, cursor
   güncellemelerini ve `IPTAL_ISTENDI` geçişlerini izle.
4. Gerçek Oracle/COPY iptali için sürücü ve ağ davranışını ayrıca doğrula; JDBC
   `cancel`/`abort` çağrısı fiziksel sonlanma için kesin süre garantisi değildir.

## Güvenli geri dönüş

- Yeni ASYNC kabulünü ve yeni paket start’ını feature flag ile kapat; cursor veya
  history tablolarını silme.
- Kabul edilmiş stop isteklerini geri alma; uyumlu worker’ın cleanup yoluyla
  sonuçlandırmasını bekle.
- `SONUC_BELIRSIZ` veya reconciliation gerektiren işlemler için force-release,
  genel pool kapatma ya da başka owner’ın session’ını sonlandırma kullanma.
- Migration geri dönüşü gerekiyorsa additive nesneleri drop etmek yerine uygulama
  kabulünü durdur ve veriyi koruyarak kontrollü drain yap.

## Doğrulama kaydı

Bu checkout’ta backend compile ve tam Maven test paketi (unit + Testcontainers,
PostgreSQL 16) başarıyla çalıştırıldı; V065 migration’ı ve PostgreSQL entegrasyon
senaryoları bu kapsamda doğrulandı. Frontend build, lint ve hedefli Vitest testleri
başarıyla çalıştırıldı. Tam Vitest paketi jsdom pseudo-element uyarıları altında
sonuç üretmeden durduruldu ve PASS sayılmadı (**NOT_RUN/INCONCLUSIVE**). Browser
Browser component E2E paketi sistem Edge binary’siyle çalıştırıldı: 37 testten
29’u **PASS**, 8’i **FAILED** oldu. Başarısızlar mevcut KM variable, mapping
compatibility, shell authoring ve SQL policy fixture senaryolarıdır; execution/package
değişikliklerine ait hedefli UI testleri PASS’tir. Mapping compatibility ve SQL
policy fixture’ları CSRF mock düzeltmesinden sonra ayrı tekrar çalıştırmada 2/2
**PASS** oldu; tam 37’li paket henüz yeniden koşturulmadı. Canlı backend gerektiren tam
browser acceptance paketi, yerel 8080 backend çalışmadığı için **NOT_RUN** durumundadır.
Gerçek Oracle sürücüsü/ağ davranışı ve production yükü bu kaydın kapsamı dışındadır;
fiziksel Oracle iptali için ayrıca yetkili ortam doğrulaması gerekir.
