# SKY-DIT Linux servis yönetimi

Bu dosyalar mevcut `akis.service` birimini yönetir. Hedef yerleşim
`/u01/akis/bin` dizinidir. Backend, systemd içindeki `User=akis` hesabıyla
çalışmaya devam eder. `start.sh` ve `stop.sh` root veya yetkili sudo oturumu
gerektirir; `status.sh` servis bilgilerini okuyabilen normal hesapla da çalışır.

| Dosya | İşlev |
|---|---|
| `start.sh` | Backend'i başlatır, `active` ve kök sağlık yanıtında `status=UP` bekler |
| `stop.sh` | Backend'i systemd üzerinden durdurur, `inactive` ve `MainPID=0` bekler |
| `status.sh` | Backend durum/PID/sağlık bilgisi ile PostgreSQL ve Nginx durumunu gösterir |
| `akis-service.sh` | Üç scriptin aynı dizinde bulunması gereken ortak yardımcı dosyası |

`start.sh` zaten aktif backend'i yeniden başlatmaz; sağlık kontrolü yapar.
`stop.sh` zaten durmuş backend için başarılı döner. Scriptler Java'yı doğrudan
başlatmaz, PID dosyası üretmez ve ek bir `kill` uygulamaz. Servisin mevcut
`TimeoutStopSec=60` ve systemd durdurma davranışı korunur.

Başlatma/durdurma istekleri `--no-block` ile verilir; sonuç script tarafından
izlenir. Başlatma sağlık beklemesi varsayılan 120, durdurma beklemesi 90 saniyedir.
İsteğe bağlı tek süre argümanı 1–600 saniye olabilir. systemd komutları da
ayrıca zaman sınırıyla çağrılır. Süre aşımında hata döner; durum gizlenmez veya
otomatik geri alma yapılmaz. Başlatma sağlık kontrolü başarısızsa backend otomatik
durdurulmaz. Durdurma süresi aşılırsa systemd işi arka planda devam edebilir;
durum/journal incelenir.

PostgreSQL ve Nginx için bu scriptler başlatma/durdurma isteği vermez.
`akis.service` içindeki mevcut `Requires=postgresql.service` nedeniyle
systemd, backend başlatılırken PostgreSQL'i gerekirse başlatabilir. Nginx
ayrı işletilir. `stop.sh` bakım öncesi çalışan veri işlerinin tamamlanmasını
sağlayan bir uygulama prosedürü değildir; işlerin durumu önce değerlendirilir.

## Ön koşullar

- Mevcut `akis.service` ve aynı sunucuda systemd.
- Bash, coreutils `timeout`, `id`, `sleep`; sağlık kontrolü için `curl` ve Python 3.
- Backend sağlık adresi `http://127.0.0.1:8080/actuator/health`.
- PostgreSQL birimi `postgresql.service`, Nginx birimi `nginx.service`.

Bu araçlar mevcut Rocky Linux kurulumuyla uyumludur; yeni Python modülü
kurulmaz. Sağlık isteği proxy kullanmaz. Yanıt JSON olarak ayrıştırılır;
yalnız bir alt bileşenin `UP` olması başarılı sonuç sayılmaz. Sağlık adresi
ve servis adı farklı olan başka ortamda `akis-service.sh` birlikte güncellenir.
YAML, parola, şifreleme anahtarı ve TLS dosyaları okunmaz veya değiştirilmez.

## İlk kurulum

İşletim paketi ile `.sha256` yan dosyası `/u01/akis/releases` altına aktarılır.
Aşağıdaki blok **sunucudaki root terminalinde** çalıştırılır. Kurulum dosyaları
yerleştirir ve salt okunur status kontrolü yapar; uygulamayı yeniden başlatmaz.

```bash
cd /u01/akis/releases &&
sha256sum -c sky-dit-ops-2026.10.21-01.tar.gz.sha256 &&
tar --no-same-owner -xzf sky-dit-ops-2026.10.21-01.tar.gz &&
cd sky-dit-ops-2026.10.21-01 &&
sha256sum -c SHA256SUMS &&
install -d -o root -g root -m 0755 /u01/akis/bin &&
install -o root -g root -m 0755 bin/start.sh bin/stop.sh bin/status.sh /u01/akis/bin/ &&
install -o root -g root -m 0644 bin/akis-service.sh /u01/akis/bin/ &&
restorecon -Rv /u01/akis/bin &&
/u01/akis/bin/status.sh
```

Servis yönetim dosyaları root sahipliğinde tutulur; uygulama hesabına yazma
yetkisi verilmez. Kurulumda SELinux kapatılmaz ve mevcut servis birimi değiştirilmez.

## Kullanım

Komutlar ihtiyaç oldukça ayrı ayrı çalıştırılır. Bakım planlanmadıkça çalışan
uygulama üzerinde durdurma/başlatma provası yapılmaz.

```bash
/u01/akis/bin/status.sh
```

```bash
/u01/akis/bin/start.sh
```

```bash
/u01/akis/bin/stop.sh
```

Özel bekleme süresi örneği: `start.sh 180`. Her script `--help` destekler.
Start başarısı backend sağlığını doğrular; `status.sh` ayrıca PostgreSQL ve
Nginx durumlarını değerlendirir. Bu kontroller tarayıcı girişi, dış HTTPS erişimi
veya veri taşıma işlev testi yerine geçmez.

| Çıkış kodu | Anlam |
|---|---|
| `0` | İstenen işlem başarılı; status için backend UP, PostgreSQL/Nginx active |
| `1` | İşlem/sağlık hatası, süre aşımı veya aktif olmayan bağımlılık |
| `2` | Geçersiz argüman |
| `3` | Yalnız status: backend durmuş; HTTP sağlık sorgusu yapılmadı |
| `4` | Ön koşul, yetki, servis yükleme veya systemd bilgisi okuma sorunu |

Hata incelemesinde `systemctl --no-pager --full status akis.service` ve
`journalctl -u akis.service -n 100 --no-pager` kullanılır. Scriptler hata halinde
bu komutları hatırlatır; logları otomatik yazdırmaz.

## Yerel doğrulama

`tests/test_service_scripts.py`, gerçek scriptleri Bash ile çalıştırır.
Canlı systemd servisleri değiştirilmez; servis ve HTTP sınırlarında kontrollü
yanıtlar kullanılır, sağlık JSON'u gerçek Python ayrıştırıcısıyla kontrol edilir.
Git Bash üzerinde çalışabilir; Linux'ta sistem Bash'i kullanılır.

```text
python -m unittest discover -s scripts/linux/tests -p test_service_scripts.py -v
bash -n scripts/linux/start.sh
bash -n scripts/linux/stop.sh
bash -n scripts/linux/status.sh
bash -n scripts/linux/akis-service.sh
```

Testler ayrıcalık, eksik birim, geç açılan/bozuk/alt bileşende UP dönen sağlık,
başlatma/durdurma hatası, süre aşımı, tekrar çağrı, bağımlılık durumu ve çıkış
kodlarını kapsar. Hedef Rocky/systemd üzerinde ilk salt okunur `status.sh`
kontrolü ve planlı bakımda start/stop kabulü ayrıca kaydedilir.
