# SKY - DIT · Data Integration Tool performans raporu

`rapor.html` bağımsız, çevrimdışı çalışan rapor taslağıdır. Python toplayıcısı gerçek cihaz/DB envanterini ve zaman örneklerini içine yerleştirir. HTML'in kendisi DB'ye bağlanmaz. Ölçüm yokken grafikler boş kalır.

## Otomatik toplama ve arayüzden çalıştırma

Depo kökünde:

```powershell
python -m pip install -r scripts/performance/requirements.txt
python scripts/performance/sky_dit_perf.py inventory
python scripts/performance/sky_dit_perf.py sample --seconds 12
```

Çıktılar Git dışındaki `.data/performance/sky-dit-uat/sky-dit-results.json` ve `sky-dit-report.html` dosyalarıdır. `sample` yalnız CPU/RAM/disk/ağ ve DB gözlem provasıdır; aktarım başlatmaz. Raporun çalıştırma seçicisinde **Ölçüm provası (aktarım yapılmadı)** olarak görünür; performans eğrisine veya tamamlanan aktarım sayısına katılmaz.

Kayıtlı `SKY_UAT` ve `PG` bağlantıları kullanılır. Bağlantı parolaları uygulamanın mevcut AES-GCM biçiminden bellekte çözülür; JSON/HTML'e veya konsola yazılmaz. Oracle JDBC ve Türkçe karakter seti desteği için yerel Maven deposundaki `ojdbc17` ve `orai18n` jar'ları kullanılır. Farklı yerdeyse `SKY_DIT_ORACLE_JDBC_JAR` ve `SKY_DIT_ORACLE_I18N_JAR` belirtin. JDBC varsayılandır; uygun yeni hesaplar için `SKY_DIT_ORACLE_TRANSPORT=thin` seçilebilir.

Kaynak: `TTBP.SKY_DIT_PERF_50`. Hedef: PostgreSQL `"SKY_DIT_PERF"."SKY_DIT_PERF_50"`. Tablolar 50 kolondur. `prepare` yalnız eksik nesneyi oluşturur, mevcut kolon sırası uyuşmazsa durur; DROP/TRUNCATE yapmaz. Oluşturulan DDL ve kademeli SELECT dosyaları çıktı klasörünün `sql/` altındadır. Tablo şeması kullanıcı tarafından incelenip onaylanmıştır.

Uygulamada **SKY** projesinde `LS_SKY_DIT_SOURCE` ve `LS_SKY_DIT_TARGET` mantıksal şemalarını **SKY UAT** ortamıyla eşleyin. Modellerde kaynak ve hedef tablosunu Reverse Engineer ile keşfedip seçili nesneyi kaydedin. PostgreSQL hedefinin ayrılmış şemasında filtreyi boş bırakın; büyük harfli tablo bu şekilde keşfedilip kaydedildi (tırnaklı filtre mevcut istek doğrulamasında reddediliyor). Flow Design ekranında bu iki metadata nesnesiyle 50 kolon eşlemesini oluşturup UAT'a yayınlayın.

### Arayüz kullanım notu ve sonraki UX düzeltmesi

22.10.2026 kullanıcı geri bildirimi: kaynak/hedef kartına sürüklenecek tablo, sol menünün **alttaki Models ağacından** alınmalıdır. Üstteki Project Objects / Flow / Interface ağacı metadata tablosu değildir ve bu bağlama için kullanılmamalıdır. Bu iki ağacın karışması gözlenen bir kullanılabilirlik sorunudur; sonraki arayüz düzenlemesinde açık ayrım ve doğru sürükleme kaynağı yönlendirmesi ele alınacak. Bu not, UX düzeltmesinin uygulanmış olduğu anlamına gelmez.

Gerçek arayüz çalıştırmasından **önce**, yayının UUID'siyle toplayıcıyı hazırlayın:

```powershell
python scripts/performance/sky_dit_perf.py watch --publication-uuid <UAT_YAYIN_UUID> --rows 100000
```

`ARMED` mesajından sonra uygulamada **Run** düğmesine basın. Toplayıcı yalnız bu projenin seçilen UAT yayınından gelen yeni çalıştırmaya bağlanır; çalıştırmayı kendisi başlatmaz. Ön ölçümü, aktarımı ve son ölçümü kaydeder; başarıdan sonra 50 kolonun tamamında sıralı streaming SHA-256 içerik doğrulaması yapar. Aynı run UUID tekrar yazılmaz. Rapor her örnekte atomik güncellenir; açık HTML'i yenileyerek son durumu görün.

Mevcut, henüz bitmemiş bir çalıştırmaya bağlanmak için `monitor --run-uuid <UUID> --rows 100000` kullanılabilir. Geçmiş bir çalıştırma için CPU/RAM örneği üretilmez. Toplayıcı timeout/iptal olduğunda uygulama çalışması otomatik iptal edilmez; Run History ekranından gerçek durum izlenmelidir.

Kaynak veri oluşturma performans aktarımından ayrıdır. `seed --rows 100000` yalnız ayrılmış UAT test tablosuna deterministik sentetik satır ekler, boşluklu/uyumsuz ID aralığını reddeder. Bu deneyde kullanıcı talimatıyla kaynak 100.000 kayıt uygulama kullanılmadan doğrudan Oracle bağlantısıyla üretildi ve ayrı bağlantıda commit sonrası sayıldı: 100.000 benzersiz ID, aralık 1–100.000. Hazırlık procedure taslağı çalıştırılmaz; DIT yalnız Oracle → PostgreSQL aktarımını yapar. **100 bin kaydın üstüne kendiliğinden çıkılmaz.** `suite` yalnız açıkça sağlanan hacim→publication UUID eşlemesiyle API'den çalışma başlatır; arayüz çalışması istendiğinde `watch` kullanılır.

Çalışma alanı kapalıysa Connections → PostgreSQL Hedef → Physical Schemas → SKY DIT Performans detayındaki **Work Area Policy** panelini kullanın. Bu deneyin ayrılmış şeması için açık, hedef şemada çalışma tablosuna izinli, en fazla 10 nesne / 100.000 satır / 1.073.741.824 byte / 24 saat seçildi. Interface → Design → Physical bölümünde batch/fetch 1000, maxRows 100.000, maxBytes 1 GiB; IKM APPEND, TRUNCATE_TARGET=false kullanılır. Byte bütçesi taşınacak satır sınırı değildir; uzun CLOB ve diğer 49 kolon için hata halinde durma bütçesidir.

İstemci CPU, toplam mantıksal CPU kapasitesinin %0–100'üdür. Backend tekil olarak komut satırındaki uygulama sınıfıyla bulunur; belirsizse config `processIds` veya `--pid` kullanın. Alt süreç working set toplamı paylaşılan sayfaları birden çok sayabilir; private commit ayrı ölçüdür. Uygulama kapalıysa süreç metrikleri null kalır.

Oracle CPU/RAM `V$OSSTAT` yetkisi ve mevcut OS sayaçlarına bağlıdır; instance SGA/PGA host RAM gibi gösterilmez. Yerel PostgreSQL konteyneri kayıtlı hedef portuyla doğrulanırsa CPU/RAM `/proc` üzerinden DB'nin bulunduğu Linux/WSL2 VM toplamı olarak toplanır; konteyner veya yalnız PG süreç tüketimi değildir. Uzak sunucularda `agent` komutu ve `SKY_DIT_AGENT_TOKEN` ile loopback metrik ajanı çalıştırılıp HTTPS/SSH tüneliyle yapılandırılabilir. Yetki/erişim eksikliği raporun toplama kapsamına yazılır. Client örnekleme varsayılan 1 sn, DB/global metrikler 5 sn'dir; DB değerleri aradaki boş örneklere kopyalanmaz.

## Kullanım

1. **JSON şablonu indir** ile veri sözleşmesini indirin.
2. Ortam bilgilerini doldurun, her çalıştırmayı `runs` listesine ekleyin.
3. **Ölçüm yükle** ile JSON dosyasını seçin. Dosya tarayıcı içinde okunur; sunucuya gönderilmez.
4. **Dolu HTML indir** ile ölçümleri içinde taşıyan raporu kaydedin. Bu HTML tekrar açıldığında veriler korunur. **CSV indir** çalıştırma özetlerini dışarı verir. Yazdırma görünümü de bulunur.

Planlanan hacimler: **100.000 / 250.000 / 500.000 / 1.000.000 / 2.500.000 / 5.000.000 kayıt**. Varsayılan hedef her hacimde üç ölçüm tekrarıdır; bu bir başarı eşiği değildir. Isınma çalıştırmaları `purpose: "warmup"` olarak saklanır ve özetlere katılmaz. Birden çok deney için ayrı JSON/HTML dosyaları kullanın; hacim deneyinde paralellik, batch, fetch, commit ve veri profili sabit kalmalıdır.

## Veri sözleşmesi

İndirilen şablonun `schemaVersion` alanı `1` olmalıdır. Ölçülmeyen sayısal alanları **null**, gerçek sıfırları **0** girin. Süreler saniye; zaman çizelgesi saniye; bellek MiB; veri boyutu byte; CPU yüzde olarak girilir. Kimlik bilgisi, parola veya hassas satır verisi eklemeyin.

`environment.client`, `environment.oracle`, `environment.postgresql` bağımsız ortam envanterleridir. `databaseSettings` ve `settings` alanlarında etkili ayarları kaydedin. `snapshotAt` envanterin alındığı zamanı belirtir. Bilinmeyen alanları boş bırakın.

`runs` içindeki bir çalıştırma aşağıdaki alanları kullanır (indirilen JSON'daki `runTemplate` doğrudan kopyalanabilir; kendisi ölçüm sayılmaz):

```json
{
  "id": "100k-r1",
  "targetRows": 100000,
  "repetition": 1,
  "purpose": "measurement",
  "status": "completed",
  "startedAt": "2026-10-22T10:00:00+03:00",
  "durationSeconds": null,
  "oracleReadSeconds": null,
  "transformSeconds": null,
  "postgresWriteSeconds": null,
  "commitSeconds": null,
  "rowsRead": null,
  "rowsCommitted": null,
  "failedRows": null,
  "retryCount": null,
  "missingRows": null,
  "duplicateRows": null,
  "validationStatus": "pending",
  "batchLatencyMs": [],
  "samples": [],
  "events": [],
  "notes": ""
}
```

- `purpose`: `measurement` veya `warmup`.
- `status`: `completed`, `failed`, `cancelled`, `running`. `completed` aktarımın bitmesidir; veri doğrulaması ayrıca yapılır.
- `validationStatus`: `pending`, `passed`, `failed`. `passed` için hedef, okunan ve commit edilen kayıt sayıları eşit; başarısız, eksik ve mükerrer kayıt sayıları sıfır olmalıdır. Şablon bu koşulu kontrol eder, fakat kayıt sayımı dışında içerik doğruluğunu kanıtlamaz. Uygulanan checksum/anahtar/içerik kontrolünü `method.validationMethod` içinde açıklayın.
- `batchLatencyMs`: tek bir çalıştırmadaki gerçek batch gecikme örnekleri, milisaniye. p50/p95/p99 nearest-rank yöntemiyle hesaplanır; başka çalıştırmaların yüzdelikleri birleştirilmez. Zamanlama kapsamını `method.batchLatencyScope` içinde belirtin.
- `events`: `{ "tSeconds": 12.5, "label": "IDE derlemesi başladı" }`. Kullanıcının günlük işleri dahil gözlenen olayları kaydedin.
- Başlangıç ve bitiş zamanları saat dilimi içeren ISO 8601 olmalıdır. Cihaz, uygulama ve DB ölçümleri ortak bir zaman referansına hizalanmalıdır.

Bir `samples` kaydı (bütün metrikler isteğe bağlıdır; alan yoksa ölçüm eksiktir):

```json
{
  "tSeconds": 0,
  "phase": "transfer",
  "hostCpuPercent": null,
  "processCpuPercent": null,
  "hostMemoryUsedMiB": null,
  "hostMemoryAvailableMiB": null,
  "processWorkingSetMiB": null,
  "processPrivateMiB": null,
  "rowsPerSecond": null,
  "diskReadMiBps": null,
  "diskWriteMiBps": null,
  "networkReceiveMiBps": null,
  "networkSendMiBps": null,
  "oracleCpuPercent": null,
  "postgresCpuPercent": null,
  "oracleMemoryUsedMiB": null,
  "postgresMemoryUsedMiB": null,
  "oracleWaitMs": null,
  "postgresWalBytesPerSecond": null,
  "postgresLockWaitCount": null
}
```

`phase`: `baseline`, `transfer`, `cooldown`. Başlangıç `tSeconds=0` aktarım başlangıcıdır; ön ölçüm negatif zaman kullanabilir. Önerilen örnekleme aralığı bir saniye; gerçek aralığı `method.sampleIntervalSeconds` alanına yazın. Grafikler eksik değerleri sıfıra çevirmez veya boşluklar üzerinden birleştirmez. CPU her iki seri için de **cihazın toplam mantıksal işlemci kapasitesine göre %0–100** olmalıdır. Birden fazla uygulama/worker süreci varsa süreç kapsamını ve toplama yöntemini `method.processScope` içinde belirtin. Working set, private memory ve cihaz belleği farklı ölçülerdir; birbirinden çıkarılarak “diğer uygulamalar” hesabı yapılmaz.

## Hesaplar ve yorum sınırları

- Hız = commit edilen kayıt / uçtan uca süre. Süre bağlantı/başlatma kapsamı dahil açıkça `method.durationScope` içinde tanımlanır.
- Hacim eğrileri yalnızca tamamlanmış `measurement` çalışmalarını kullanır. Her metrik kendi mevcut değerleri üzerinden hesaplanır; adetleri tabloda gösterilir. İşaret medyanı, dikey çizgi min–maks aralığını gösterir. Üç tekrar bulunması istatistiksel güven aralığı anlamına gelmez.
- Başarısız/iptal/running çalışmalar ayrıntı tablosunda kalır; kapasite eğrilerine katılmaz. Doğrulaması başarısız tamamlanmış çalışmalar performans eğrisine dahil olabilir; doğrulama durumu ayrıca görünür.
- Oracle okuma ve PostgreSQL yazma pipeline içinde çakışabilir. Aşama süreleri toplanarak uçtan uca süre türetilmez. PostgreSQL yazma süresinin commit'i içerip içermediğini `method.stageTimingScope` içinde belirtin.
- Ayrı bir ölçüm yoksa ağ süresi hesaplanmaz. Oracle/PG sunucu kaynakları istemci kaynaklarından ayrıdır; uzak sunuculardan toplanmamış metrikler “ölçülmedi” kalır.
- Cihaz toplam CPU/RAM değerleri günlük işleri de kapsar. Eşzamanlı artış, başka bir işin yavaşlamaya neden olduğunu tek başına kanıtlamaz. Bu rapor günlük kullanım devam ederken gözlenen performansı gösterir.
- Başarı eşikleri, durdurma koşulları ve içerik doğrulaması test öncesinde `method` içinde tanımlanır. Henüz sonuç yokken rapor otomatik kapasite/darboğaz iddiası üretmez. Değerlendirmeleri kanıtlarıyla `findings` listesine ekleyin.

`findings` kaydı: `{ "title": "...", "detail": "...", "runIds": ["100k-r1"] }`.

Yerel HTML/JSON raporu bir veritabanı bağlantısı açmaz. Veri toplama ve test çalıştırma bu taslağın dışındadır.

## Taslağı doğrulama

Depo kökünde `node docs/testing/performance/verify-report.cjs` çalıştırın. Kontrol, `frontend` içindeki Playwright bağımlılığını ve kurulu Microsoft Edge tarayıcısını kullanır. Boş görünümü, JSON yüklemeyi, medyan/yüzdelik hesaplarını, geçersiz verilerin reddini, mobil taşmayı, çevrimdışı HTML/CSV çıktısını ve PDF yazdırmayı kontrol eder. Sentetik veriler yalnızca doğrulama sırasında kullanılır; ekran görüntüleri ve kontrol çıktıları işletim sisteminin geçici dizinine kaydedilir.
