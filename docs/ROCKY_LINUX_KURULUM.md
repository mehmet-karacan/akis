# SKY-DIT — Rocky Linux üzerinde sıfırdan kurulum

Son güncelleme: 21 Ekim 2026.

Ürün adı **SKY-DIT — Sky Data Integration Tool** olarak kullanılacaktır.
İç teknik adlandırma `akis` olarak korunur: Linux kullanıcısı/grubu, servis,
`/u01/akis` dizini, `akis.jar`, `akis_db` veritabanı, `akis` şeması,
Java paketleri ve yapılandırma anahtarları bu nedenle değiştirilmez. Teslimat
paketinin mevcut teknik adı `akis-linux-<surum>` olarak kalır. Bu kurulum
belgesindeki adlandırma kararı arayüzdeki marka değişikliğinin uygulanmış olduğu
anlamına gelmez.

Bu rehber, uygulamayı başka bir makinede derleyip Linux sunucuya hazır paket
olarak taşıma yöntemini anlatır. Hedefte internet erişimi zorunlu değildir.
PostgreSQL aynı sunucuda, Docker kullanılmadan sistem servisi olarak çalışır.
Uygulama dosyaları ve verileri `/u01/akis` altında tutulur.

**Doğrulama durumu:** Rehber mevcut proje yapılandırması ve resmî belgelerle
karşılaştırılmıştır; hedef sunucuda uçtan uca uygulanmış kurulum kaydı değildir.
Sunucuda yapılan her adımın sonucu en sondaki kabul tablosuna işlenmelidir.
Bu belgeyi oluşturmak uygulama veya RPM paketlerinin hazırlanmış olduğu anlamına
gelmez. Komutlar sırayla ve ilgili makinede çalıştırılır; bir adım hata verirse
sonraki adıma geçilmez. Gerçek parolalar, anahtarlar ve sertifikalar bu belgeye
veya Git'e yazılmaz.

**Birlikte uygulama düzeni:** Sunucuya her seferinde tek adım verilir. Kullanıcı
komutu çalıştırıp sonucu veya `OK` bilgisini iletmeden sonraki sunucu adımına
geçilmez. Çıktı gerektiren kontrollerde çıktı da alınır. Yalnız gerçekleştiği
doğrulanan işlemler tamamlandı olarak kaydedilir. İlk sunucunun ilerleme kaydı:
[ATTBPSRV02L kurulum kaydı](kurulum/ATTBPSRV02L.md).

## 1. Hedef ortam ve değiştirilecek bilgiler

| Bilgi | Bu rehberdeki değer | Başka ortamda yapılacak işlem |
|---|---|---|
| İşletim sistemi | Rocky Linux 10 / x86_64 | Tam sürümü ve paket uyumluluğunu kaydet |
| İlk hedef sunucu | ATTBPSRV02L, Rocky Linux 10.2 | Yeni sunucunun adı/IP'siyle değiştir |
| Ana dizin | `/u01/akis` | Değişirse tüm yolları ve SELinux kurallarını birlikte güncelle |
| Uygulama adresi | `akis.example.internal` | DNS ve sertifikadaki gerçek adla değiştir |
| Java | 25 | JAR bu sürüm için derlenir |
| PostgreSQL | 18, Rocky AppStream paket düzeni | Paketlerin tam sürümlerini teslimat kaydına yaz |
| Veritabanı / şema | `akis_db` / `akis` | Uygulama şeması `akis` olarak kalır |
| Veritabanı rolü | `akis_app` | Ortama özel parola belirle |
| Backend | `127.0.0.1:8080` | Aynı sunucudaki Nginx erişir |
| PostgreSQL | Backend için `127.0.0.1:5432`; uzaktan yönetim için sunucu IP'si | Uzaktan erişim seçilirse 7.2'deki istemci/ağ kısıtı uygulanır |
| Kullanıcı erişimi | HTTPS / 443 | Gerekli istemci ağlarından izin ver |
| Uygulama Linux kullanıcısı | `akis` | Root olarak uygulama çalıştırma |

İlk hedefte gözlenen kaynaklar: 8 CPU, yaklaşık 15 GiB RAM ve kök diskte 241 GiB
boş alan. Saat eşitlemesi açık, saat dilimi Europe/Istanbul, SELinux Enforcing,
firewalld etkin ve FIPS kapalıdır. Java/PostgreSQL/Nginx kurulu görünmemektedir.
Bu gözlem 21 Ekim 2026'ya aittir; başka ortama taşınmış varsayılmaz.

Yeni ortam için önce sunucu, DNS, TLS sertifikası, `/u01` disk kararı, paket
tedarik yolu ve kaynak/hedef veritabanlarına ağ izinleri netleştirilir.
Kullanıcılar sunucunun 443 portuna; backend ilgili veritabanı portlarına erişir.
8080 için dış erişim kuralı eklenmez. PostgreSQL 5432 yalnız uzaktan bağlantı
gereken ortamda, belirlenen istemci/ağ için açılır; 7.2 uygulanır. SSH erişimi korunur.

## 2. Dizin ve sahiplik düzeni

```text
/u01/akis/
├── app/akis.jar                  # Etkin backend; root tarafından yönetilir
├── bin/                         # start.sh, stop.sh, status.sh ve ortak yardımcı
├── frontend/                    # Etkin arayüz; Nginx salt okunur erişir
├── conf/
│   ├── application.yaml         # Ortama özel ayarlar/sırlar; root:akis, 0640
│   ├── nginx.conf               # Nginx sanal sunucusu
│   └── tls/                     # Sertifika zinciri ve özel anahtar
├── data/postgresql/              # PostgreSQL kümesi; postgres:postgres, 0700
├── work/                        # Backend çalışma alanı; akis:akis
├── logs/nginx/                  # Nginx erişim/hata logları
├── backups/                     # DB yedekleri; postgres:postgres, 0700
└── releases/                    # Açılmış, sürümlü teslimatlar; root yönetir

/etc/systemd/system/akis.service
/etc/systemd/system/postgresql.service.d/override.conf
/etc/nginx/conf.d/akis.conf       # /u01 içindeki Nginx yapılandırmasını dahil eder
```

`/etc` altında yalnız işletim sistemi servis entegrasyonu kalır. Backend logları
systemd journal'a gider; `/u01/akis/logs` altında kendiliğinden Java log dosyası
oluşmaz. PostgreSQL logları bu tarifte kendi servisinin journal kaydındadır.

`/u01` tek başına bir klasör adı olabilir; ayrı disk anlamına gelmez. Sistem ekibi
ayrı disk sağlayacaksa **veri oluşturulmadan önce** bağlamalı ve kalıcı bağlama
ayarını yapmalıdır. Dolu dizinin üzerine disk bağlamak eski dosyaları gizler.

**Yer: hedef Linux sunucu, root.**

```bash
findmnt -T /u01
df -hT / /u01
```

`/u01` henüz yoksa bu iki komut hata verebilir. Ayrı disk planı yoksa 5. bölümde
normal dizin olarak oluşturulur. Ayrı disk gerekiyorsa burada sistem ekibinin
bağlama işlemi tamamlanır; bu rehber disk biçimlendirme komutu içermez.

## 3. Teslim edilecek paketler

### 3.1. Uygulama teslimatı

```text
akis-linux-<surum>/
├── backend/akis.jar
├── frontend/index.html
├── frontend/assets/...
├── KURULUM.md                    # Bu belgenin teslimat kopyası
├── VERSION                      # Sürüm, kaynak commit, derleme zamanı
└── SHA256SUMS                    # VERSION dahil dosyaların SHA-256 özeti
```

Arşiv adı `akis-linux-<surum>.tar.gz` olur. Arşivin SHA-256 değeri ayrıca teslim
kaydına yazılır. Gerçek `application.yaml`/`.env`, sertifika özel anahtarı, kaynak kod,
`node_modules`, Maven önbelleği ve geliştirme veritabanı pakete girmez.

JDBC sürücüleri ve `database/akis-baseline/V*.sql` migration'ları backend JAR'ına
paketlenir. Sunucuda Node.js, npm veya Maven gerekmez. Aşağıda verilen YAML,
systemd ve Nginx şablonları kurulum sırasında oluşturulur. Önceki taslakta
konuşulan `install.sh`, `check.sh`, `bootstrap-admin.sh` dosyaları henüz bu
repoda teslim edilmiş Linux otomasyonları değildir; bu belge onların varlığını
varsaymaz.

### 3.2. İşletim sistemi paketleri

Öncelik kurumun onaylı iç depolarıdır. Yoksa sistem ekibi, hedefle eşleşen
Rocky Linux 10.x / x86_64 ortamında aşağıdaki paketleri **tüm bağımlılıklarıyla**
hazırlar. Başka dağıtım/sürüm için RPM'ler karıştırılmaz.

- Java 25 çalışma ortamı; ilk hedefte Rocky AppStream `java-25-openjdk-headless`
  kullanıldı. Başka sağlayıcı seçilirse Java binary yolu ayrıca doğrulanır.
- AppStream `postgresql18-server`, `postgresql18` ve bağımlılık olarak
  `postgresql18-private-libs`.
- `nginx`, `logrotate`, `policycoreutils-python-utils`, `openssl`, `curl`, `tar`, `gzip`,
  `util-linux`, `findutils`, `coreutils` ve eksik geçişli bağımlılıkları.
- Paket imzalarını doğrulamak için onaylı depo imza anahtarları ve kaynak/sürüm
  listesi. İmza veya TLS doğrulaması kapatılmaz.

Windows'ta Java uygulamasını derlemek, Rocky RPM bağımlılıklarını hazırlamakla
aynı iş değildir. Çevrimdışı RPM seti aynı dağıtımlı bir hazırlık makinesinde
çözümlenir ve hedefe benzer temiz bir ortamda kurularak denenir. PostgreSQL 18'in
ilgili Rocky minor sürümü için paket bulunabilirliği hazırlık sırasında ayrıca
doğrulanır. Standart dağıtım deposundaki `postgresql-server` paketinin sürümünün
18 olduğu varsayılmaz.

## 4. Uygulamayı yerel Windows makinesinde derleme ve paketleme

**Yer: kaynak kodun bulunduğu Windows makinesi, PowerShell, repo kökü.**

Java 25 JDK ve frontend bağımlılıklarını destekleyen Node.js gerekir. Bu proje
Vite 8 kullanır; Node.js 24 ile derleme yapılabilir. `JAVA_HOME` ve `PATH` aynı
Java 25 kurulumunu göstermelidir. Mevcut makinede `java -version` 21 gösterirse
önce Java 25 seçilir. İnternet/depo erişimi bağımlılıkları hazırlayan bu makinede
gerekebilir; çalışma sunucusu internete çıkmak zorunda değildir.

```powershell
java -version
node --version
.\mvnw.cmd -version
git status --short
```

Kurulacak kaynak sürümünü sabitleyin. Çalışma kopyası değişiklik içeriyorsa bunu
teslim kaydında belirtin; yalnız commit hash'i yazıp temiz sürümmüş gibi teslim
etmeyin. Tercih, seçilmiş commit'ten temiz derlemedir. Buradaki komutlar otomatik
commit veya push yapmaz.

```powershell
$ErrorActionPreference = 'Stop'
.\mvnw.cmd -pl backend -am clean package
if ($LASTEXITCODE -ne 0) { throw 'Backend derlemesi/testleri başarısız.' }

Push-Location frontend
try {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw 'npm ci başarısız.' }
    npm run lint
    if ($LASTEXITCODE -ne 0) { throw 'Frontend lint başarısız.' }
    npm test
    if ($LASTEXITCODE -ne 0) { throw 'Frontend testleri başarısız.' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend derlemesi başarısız.' }
}
finally { Pop-Location }
```

`package` backend birim testlerini çalıştırır. Gerçek PostgreSQL/Oracle ve tarayıcı
kabul testlerini tamamlamış sayılmaz. `auth-contract` profili normal teslimat
derlemesinde kullanılmaz. Sonuçları sürüm teslim kaydına ekleyin.

Örnek paket üretimi; `$release` her teslimatta benzersiz olmalıdır. POM sürümü
değiştiyse JAR kaynak adını da güncelleyin:

```powershell
$release = '2026.10.21-01'
$packageName = "akis-linux-$release"
$stageRoot = Join-Path $env:TEMP 'akis-deliveries'
$packageRoot = Join-Path $stageRoot $packageName
if (Test-Path -LiteralPath $packageRoot) { throw 'Bu teslimat dizini zaten var.' }
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'backend') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'frontend') -Force | Out-Null
Copy-Item -LiteralPath 'backend\target\akis-backend-0.1.0-SNAPSHOT.jar' -Destination (Join-Path $packageRoot 'backend\akis.jar')
Copy-Item -Path 'frontend\dist\*' -Destination (Join-Path $packageRoot 'frontend') -Recurse
Copy-Item -LiteralPath 'docs\ROCKY_LINUX_KURULUM.md' -Destination (Join-Path $packageRoot 'KURULUM.md')
$commit = git rev-parse HEAD
$dirty = [bool](git status --porcelain)
$versionText = "release=$release`ncommit=$commit`ndirty=$dirty`nbuilt_at=$([DateTimeOffset]::Now.ToString('o'))`n"
[IO.File]::WriteAllText((Join-Path $packageRoot 'VERSION'), $versionText, [Text.UTF8Encoding]::new($false))
$manifest = Get-ChildItem -LiteralPath $packageRoot -Recurse -File | Sort-Object FullName | ForEach-Object {
    $relative = [IO.Path]::GetRelativePath($packageRoot, $_.FullName).Replace('\', '/')
    '{0}  {1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $relative
}
[IO.File]::WriteAllText((Join-Path $packageRoot 'SHA256SUMS'), (($manifest -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
$archive = Join-Path $stageRoot "$packageName.tar.gz"
if (Test-Path -LiteralPath $archive) { throw 'Bu teslimat arşivi zaten var.' }
tar.exe -czf $archive -C $stageRoot $packageName
if ($LASTEXITCODE -ne 0) { throw 'Arşiv oluşturulamadı.' }
Get-FileHash -LiteralPath $archive -Algorithm SHA256
Write-Output $archive
```

Paket işletim sistemi bağımsız JAR ve statik arayüz içerir. Windows'a ait Java
kurulumu veya `node_modules` dizini Linux'a kopyalanmaz.

## 5. Sunucuda temel yazılımlar, kullanıcı ve dizinler

**Yer: hedef Linux sunucu, root.** Paketleri kurum içi onaylı depodan kurun.
Aşağıdaki komut Rocky BaseOS/AppStream düzenini kullanır. Mevcut depoların
erişilebilir ve onaylı olması gerekir; komut metadata/RPM indirmek için ağ
erişimi kullanabilir. Kurumun iç ayna düzeni varsa depo adlarını ona uyarlayın.

**İlk sunucuda doğrulanan kurulum:** OpenJDK 25.0.4.1, PostgreSQL 18.6 ve Nginx
1.26.3 AppStream paketleriyle kuruldu. PostgreSQL binary yolları `/usr/bin`,
servis `postgresql.service` olarak RPM listesiyle doğrulandı. Rehber bu yerleşime
güncellendi. PGDG gibi başka bir sağlayıcı seçilirse binary/servis yolları ayrıca
eşleştirilmelidir. Güncel adım ve sunucuda henüz uygulanmamış bölümler
[sunucu kaydında](kurulum/ATTBPSRV02L.md) tutulur.

```bash
dnf --disablerepo='*' --enablerepo=baseos,appstream install -y java-25-openjdk-headless postgresql18-server postgresql18 nginx logrotate \
  policycoreutils-python-utils openssl curl tar gzip util-linux findutils
```

Çevrimdışı teslimatta **alternatif olarak**, imzaları/anahtarları ve bağımlılıkları
hazırlanmış RPM setini kurun. `/root/akis-offline/rpms` buraya aktarılmış setin
örnek yoludur; eksik bağımlılıkta işlem durdurulur ve set tamamlanır:

```bash
dnf --disablerepo='*' install -y /root/akis-offline/rpms/*.rpm
```

Bu iki kurulum yolu birlikte uygulanması gereken iki adım değildir. Kurulumdan
sonra:

```bash
java -version
readlink -f /usr/bin/java
/usr/bin/postgres --version
nginx -v
command -v semanage
systemctl cat postgresql
```

Java çıktısı 25, PostgreSQL çıktısı 18 olmalıdır. Farklı paket sağlayıcısı farklı
servis/binary yolları kurduysa devam etmeden belgeyi o düzene uyarlayın.

```bash
id akis >/dev/null 2>&1 || useradd --system --user-group --home-dir /u01/akis/work --no-create-home --shell /sbin/nologin akis
install -d -o root -g root -m 0755 /u01 /u01/akis
install -d -o root -g root -m 0755 /u01/akis/app /u01/akis/frontend /u01/akis/conf /u01/akis/data
install -d -o root -g root -m 0750 /u01/akis/conf/tls /u01/akis/releases
install -d -o akis -g akis -m 0750 /u01/akis/work
install -d -o postgres -g postgres -m 0700 /u01/akis/data/postgresql /u01/akis/backups
install -d -o root -g root -m 0755 /u01/akis/logs
install -d -o root -g nginx -m 0750 /u01/akis/logs/nginx
```

Tüm ağaca `chown -R akis` veya `chmod -R 777` uygulanmaz. Özellikle PostgreSQL,
TLS anahtarı, uygulama ayarları ve statik dosyalar ayrı sahiplik taşır.

## 6. SELinux izinlerini kalıcı tanımlama

**Yer: hedef Linux sunucu, root.** SELinux Enforcing olarak kalır. Aşağıdaki
kurallar ilk kurulumda eklenir. Aynı kural zaten varsa önce
`semanage fcontext -l -C` ile inceleyin; yalnız karşılık gelen kuralı değiştirmek
gerekiyorsa `-a` yerine `-m` kullanın.

```bash
semanage fcontext -a -t var_t '/u01'
semanage fcontext -a -t var_t '/u01/akis'
semanage fcontext -a -t var_t '/u01/akis/data'
semanage fcontext -a -t postgresql_db_t '/u01/akis/data/postgresql(/.*)?'
semanage fcontext -a -t httpd_sys_content_t '/u01/akis/frontend(/.*)?'
semanage fcontext -a -t httpd_config_t '/u01/akis/conf'
semanage fcontext -a -t httpd_config_t '/u01/akis/conf/nginx\.conf'
semanage fcontext -a -t cert_t '/u01/akis/conf/tls(/.*)?'
semanage fcontext -a -t var_log_t '/u01/akis/logs'
semanage fcontext -a -t httpd_log_t '/u01/akis/logs/nginx(/.*)?'
restorecon -v /u01 /u01/akis /u01/akis/data /u01/akis/conf /u01/akis/logs
restorecon -Rv /u01/akis/data/postgresql /u01/akis/frontend /u01/akis/conf/tls /u01/akis/logs/nginx
setsebool -P httpd_can_network_connect 1
```

Son komut Nginx'in backend'e ağ bağlantısı kurabilmesi içindir; sistemdeki web
servisi SELinux alanına ağ bağlantısı izni verir. Etkisi sistem ekibiyle kayıt
altına alınır. Özel kısıtlı politika istenirse kurum politikası uygulanır.
Nginx'in standart dışı kök/log/sertifika yolları, ilgili dosyalar oluşturulunca
tekrar `restorecon` ile etiketlenir. Kurumun ek SELinux politikaları varsa AVC
kaydı incelenir; `setenforce 0` ile sorun gizlenmez.

## 7. PostgreSQL 18 kümesini `/u01` altında oluşturma

**Yer: hedef Linux sunucu, root. Bu bölüm yalnız boş ve yeni kurulum içindir.**
Mevcut veritabanı taşınacaksa veri dizini üzerinde `initdb` çalıştırılmaz;
önce 15. bölümdeki taşıma planı yapılır.

```bash
systemctl is-active postgresql
find /u01/akis/data/postgresql -mindepth 1 -maxdepth 1 -print
```

Servis zaten çalışıyorsa veya dizinde dosya varsa burada durup mevcut kurulumu
inceleyin. Boş dizinde ilk başlatma:

```bash
runuser -u postgres -- /usr/bin/initdb \
  -D /u01/akis/data/postgresql \
  --encoding=UTF8 --locale=C --auth-local=peer --auth-host=scram-sha-256
```

`C` sıralama düzeni bu tarifin bilinçli seçimi; UTF-8 kodlama ayrıca verilir.
Başka collation gereksinimi varsa küme oluşturulmadan önce kararlaştırılır.

Yeni kümede `/u01/akis/data/postgresql/pg_hba.conf` dosyasını aşağıdaki içerikle
düzenleyin. Yönetim, `postgres` Linux kullanıcısından yerel socket üzerinden
yapılır; uygulama yalnız loopback TCP üzerinden parola ile bağlanır:

```text
local   all             postgres                                peer
host    akis_db   akis_app        127.0.0.1/32              scram-sha-256
```

`/u01/akis/data/postgresql/postgresql.conf` dosyasının sonuna ekleyin:

```conf
listen_addresses = '127.0.0.1'
port = 5432
password_encryption = 'scram-sha-256'
unix_socket_directories = '/var/run/postgresql'
logging_collector = off
```

Servis için PGDATA override oluşturun:

İlk hedefte `systemctl cat postgresql.service` çıktısıyla varsayılanın
`PGDATA=/var/lib/pgsql/data`, başlangıç komutunun
`/usr/bin/postgres -D ${PGDATA}` ve ön kontrolün
`/usr/libexec/postgresql-check-db-dir %N` olduğu doğrulandı. Aşağıdaki drop-in
bu paketin PGDATA değerini değiştirir; `/usr/lib/systemd/system` altındaki
paket birimi yerinde düzenlenmez. Başka ortamda servis tanımı yeniden incelenir.

```bash
install -d -m 0755 /etc/systemd/system/postgresql.service.d
cat > /etc/systemd/system/postgresql.service.d/override.conf <<'UNIT'
[Unit]
RequiresMountsFor=/u01/akis/data/postgresql

[Service]
Environment=PGDATA=/u01/akis/data/postgresql
UNIT
restorecon -Rv /u01/akis/data/postgresql /etc/systemd/system/postgresql.service.d
systemctl daemon-reload
systemctl enable --now postgresql
systemctl status postgresql --no-pager
```

PGDATA ve bağlantıyı doğrulayın:

```bash
runuser -u postgres -- /usr/bin/psql -h /var/run/postgresql -d postgres -c 'SHOW data_directory;'
ss -lnt
```

Veri yolu `/u01/akis/data/postgresql`, dinlenen adres `127.0.0.1:5432` olmalıdır.
Bu, yalnız yerel DB erişimi seçeneğidir; uzaktan bağlantı seçildiyse 7.2'de
belirlenen sunucu IP'sinde de dinleme doğrulanır.

### 7.1. Uygulama rolü ve veritabanı

```bash
runuser -u postgres -- /usr/bin/psql -X -v ON_ERROR_STOP=1 -h /var/run/postgresql -d postgres
```

Açılan **psql oturumunda**, yalnız ilk kurulumda:

```sql
CREATE ROLE akis_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
\password akis_app
CREATE DATABASE akis_db OWNER akis_app ENCODING 'UTF8' TEMPLATE template0;
\connect akis_db
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
\quit
```

`\password` parolayı etkileşimli ister; parola shell komutuna yazılmaz. Aynı
parola 9. bölümde uygulamanın ayarına girilecektir.

Bu başlangıç modeli uygulama rolünü yalnız kendi metadata veritabanının sahibi
yapar; PostgreSQL superuser yapmaz. Flyway açılışta şema oluşturduğu için DDL
yetkisi gerekir. Kurum migration ve runtime rollerini ayırmak isterse ayrı
Flyway datasource kullanıcı/parolası ve migration sonrası tablo/sequence
yetkileri ayrıca hazırlanır; DDL yetkisi olmayan role geçip migration açıkken
başlatılmaz. `scripts/provision-local-postgres-runtime-role.ps1` Windows/Docker
yardımcısıdır; bu Linux sunucuda doğrudan çalıştırılmaz.

```bash
/usr/bin/psql -X -h 127.0.0.1 -U akis_app -d akis_db -W -c 'SELECT current_user, current_database();'
```

### 7.2. Sunucu IP'si üzerinden uzaktan PostgreSQL bağlantısı

ATTBPSRV02L için kullanıcı bu seçeneği istedi. Sunucu `172.16.11.19/24`,
kullanıcının Windows Wi-Fi adresi `10.10.3.111/16`, sunucunun gördüğü SSH kaynak
adresi `10.60.55.49` olarak doğrulandı. Kullanıcı ikinci cihaz olarak ayrıca
`10.10.1.8` adresini bildirdi. Hedef `172.16.11.19:5432`, istenen dar izin
listesi `10.10.3.111/32` ve `10.10.1.8/32` olacaktır. SSH kaynağı yerel cihaz
adresi sayılmaz ve bu listeye otomatik eklenmez. Gerçek PostgreSQL bağlantısında
kaynak NAT/proxy nedeniyle farklı görünürse ağ yolu doğrulanıp kural ayrıca
değerlendirilir. İlk DBeaver denemesi ve Windows'tan doğrudan TCP connect testi
timeout ile sonuçlandı. Kullanıcı dış DB/ağ incelemesini ana uygulama ve UI
dağıtımı sonrasına erteledi; bu konu ana kurulumun önkoşulu olarak tutulmuyor.
Güncel sıra ve bekleyen kontroller sunucu kaydında takip edilir.
Adreslerin bulunması bağlantı/firewall kurallarının uygulanmış olduğu anlamına
gelmez; güncel adımlar [sunucu kaydında](kurulum/ATTBPSRV02L.md) tutulur.
Bu adresler başka ortama kopyalanmaz, ortamın kendi adresleri belirlenir.

Backend'in loopback bağlantısı korunur. Önce sunucunun global IPv4 adresi,
sonra bağlanacak bilgisayarın kaynak IP'si veya onaylı yönetim ağı belirlenir.
`listen_addresses` loopback ve bu sunucu adresini kapsayacak şekilde ayarlanır.
`pg_hba.conf` içinde gerçek veritabanı/rol ve istemci IP/CIDR eşleştirilir;
kimlik doğrulama SCRAM ile yapılır. TLS gerekiyorsa sunucu sertifikası ve
istemci doğrulaması hazırlanıp `hostssl` kuralı kullanılır.

firewalld ve varsa kurum ağ güvenlik duvarında 5432/TCP yalnız belirlenen
kaynak için açılır. Genel `--add-port=5432/tcp` kuralıyla bütün zone'a açılmaz.
Önce sunucu üzerinde dinleme/kimlik doğrulama, sonra gerçek uzak istemciden
bağlantı test edilir. İlgisiz istemciden erişimin reddedilmesi de kontrol edilir.
İstemci kaynak adresi NAT nedeniyle farklıysa ağ ekibinin doğruladığı kaynak
kullanılır. İnternet yetkisi bu DB erişim kuralının yerine geçmez.

## 8. Uygulama paketini sunucuya taşıma

**Yer: Windows ve hedef Linux.** Kurumun onaylı dosya aktarım yöntemiyle
`akis-linux-<surum>.tar.gz` dosyasını `/u01/akis/releases` altına taşıyın.
Varsa RPM paketi ayrı aktarılır. Parolaları uygulama arşivine eklemeyin.

**Yer: Linux, root.** Örnekteki sürümü gerçek teslimatla değiştirin:

```bash
cd /u01/akis/releases
sha256sum akis-linux-2026.10.21-01.tar.gz
tar -tzf akis-linux-2026.10.21-01.tar.gz
```

Arşiv özetini teslim kaydıyla karşılaştırın. İçerik yalnız beklenen tek paket
dizini altında olmalı; mutlak yol veya `..` içeren beklenmedik üyeler varsa
açmayın. Aynı sürüm dizini zaten varsa üzerine açmayın. Güvenilir teslimatı açın:

```bash
test ! -e /u01/akis/releases/akis-linux-2026.10.21-01 && \
  tar --no-same-owner -xzf /u01/akis/releases/akis-linux-2026.10.21-01.tar.gz -C /u01/akis/releases
cd /u01/akis/releases/akis-linux-2026.10.21-01
sha256sum -c SHA256SUMS
cat VERSION
```

Tüm dosyalar `OK` olmalıdır. İlk kurulumda etkin dizinlere kopyalayın:

```bash
install -o root -g root -m 0644 backend/akis.jar /u01/akis/app/akis.jar
cp -r frontend/. /u01/akis/frontend/
chown -R root:root /u01/akis/frontend
find /u01/akis/frontend -type d -exec chmod 0755 {} +
find /u01/akis/frontend -type f -exec chmod 0644 {} +
restorecon -Rv /u01/akis/frontend
```

Bu kopyalama ilk kurulum içindir. Güncelleme ve geri dönüşte 15. bölüm uygulanır.

## 9. Harici YAML ayarlarını ve şifreleme anahtarını oluşturma

YAML ve `.properties` Spring Boot tarafından desteklenen güncel biçimlerdir;
biri diğerinin yeni sürümü değildir. Bu kurulumda hiyerarşik ayarları tek yerde
okumak için YAML seçilmiştir. Sunucuda `.env` dosyası kullanılmaz; uygulama
ayarları `/u01/akis/conf/application.yaml` içindedir. Repodaki geliştirme
başlatıcılarının `.env` kullanımı bu tercihle değiştirilmez.

Servis ve bootstrap komutu `spring.config.additional-location` ile aynı harici
dosyayı yükler. Böylece JAR içindeki varsayılanlar korunur, harici dosyada verilen
ayarlar bunların üzerine uygulanır. Konumda `optional:` kullanılmaz; dosya
eksikse uygulama hatayla durmalıdır. Ayrıntı:
[Spring Boot harici yapılandırma](https://docs.spring.io/spring-boot/4.1/reference/features/external-config.html).

**Yer: Linux, root.** Dosya varsa üzerine yazmayın. İlk kurulumda:

```bash
test ! -e /u01/akis/conf/application.yaml && \
  install -o root -g akis -m 0640 /dev/null /u01/akis/conf/application.yaml
vi /u01/akis/conf/application.yaml
```

Şablon; `REPLACE_` değerlerini gerçek değerlerle değiştirin:

```yaml
spring:
  datasource:
    url: 'jdbc:postgresql://127.0.0.1:5432/akis_db'
    username: 'akis_app'
    password: 'REPLACE_WITH_DATABASE_PASSWORD'
    hikari:
      maximum-pool-size: 5

server:
  address: '127.0.0.1'
  port: 8080
  forward-headers-strategy: native
  tomcat:
    remoteip:
      internal-proxies: '127[.]0[.]0[.]1'
  servlet:
    session:
      cookie:
        secure: true

akis:
  security:
    mode: local
    data-encryption-key: 'REPLACE_WITH_PERSISTENT_RANDOM_KEY'
  execution:
    accept-manual-requests: false
    worker-enabled: false
    procedure-runtime-enabled: false
    staged-runtime-enabled: false
    recovery-runtime-enabled: false
    transfer-recovery-enabled: false
    scheduler-enabled: false
```

Parola ve anahtar için ayrı ayrı `openssl rand -hex 32` kullanılabilir. Anahtar
kalıcıdır; her yeniden başlatmada veya güncellemede yeniden üretilmez. Güvenli
kurumsal sır deposunda ayrıca tutulur. Anahtar kaybolursa şifreli değerler
çözülemeyebilir. Ortamlar arasında gerçek sırlar kopyalanmaz.

Dosyayı Spring Boot okur; shell ile `source` edilmez ve systemd `EnvironmentFile`
olarak verilmez. Girintide TAB kullanmayın. Parola ve anahtar gibi metinleri tek
tırnakla yazın; değerde tek tırnak varsa YAML kuralına göre iki tek tırnakla
kaçırın. Spring `${...}` ifadelerini placeholder olarak yorumlayabilir; örnek
gibi hex sır üretmek bu belirsizliği önler. LF satır sonu ve BOM'suz UTF-8
kullanın. İşletim sistemi ortamında kalmış Spring/JVM ayarları dosyanın üzerine
geçebilir; servise eski kurulumdan kalan override'lar taşınmaz.

```bash
chown root:akis /u01/akis/conf/application.yaml
chmod 0640 /u01/akis/conf/application.yaml
grep -q 'REPLACE_' /u01/akis/conf/application.yaml && printf 'Yer tutucular hâlâ mevcut; devam etmeyin.\n'
```

`local`, mevcut kodda uygulamanın kendi kullanıcı/parola ve sunucu oturumunu
seçer. Eski README/`.env.example` içindeki HTTP Basic, `AKIS_DEV_PASSWORD` veya
OIDC notlarına göre kurulum yapılmaz. Bu rehberde HTTPS sonlandırması Nginx'tedir;
forwarded header'lar yalnız loopback Nginx'ten kabul edilir. Öne bir yük
dengeleyici eklenirse proxy güven zinciri ayrıca düzenlenir.

## 10. İlk sistem yöneticisini oluşturma

**Yer: Linux, etkileşimli SSH terminali, root.** Backend servisi henüz
başlatılmadan çalıştırın. Komutu pipe, heredoc veya arka plan üzerinden
çalıştırmayın; Java gerçek terminalden parola okuyacaktır.

```bash
cd /u01/akis/work && runuser -u akis -- /usr/bin/java -Dspring.config.additional-location=file:/u01/akis/conf/application.yaml -Dloader.main=tr.com.innova.akis.security.BootstrapAdminCommand -cp /u01/akis/app/akis.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Komut tek satır olarak kopyalanır; terminalin ekranda satırı kaydırması normaldir.
Bu başlangıç sınıfı web sunucusu açmadan Spring bağlamını ve Flyway'i başlatır;
boş veritabanında `akis` şemasını/migration'ları hazırlar. Kullanıcı kodu, ad ve
isteğe bağlı alanları sorar; parolayı gizli olarak iki kez okur. Mevcut politika
15–128 karakterdir. Parola Argon2id özeti olarak saklanır. Etkin sistem yöneticisi
varsa yeniden bootstrap yapılmaz. Hazır bir admin parolası yoktur.

Migration hatasında rastgele SQL dosyaları elle çalıştırılmaz, history silinmez
ve `flyway repair` otomatik uygulanmaz. Hata ve sürüm uyumluluğu incelenir.
Kullanılan migration kaynağı `classpath:db/akis` olup eski
`database/migrations` zinciri ve `database/pending` normal kuruluma eklenmez.

## 11. Backend systemd servisi

**Yer: Linux, root.** `/etc/systemd/system/akis.service` dosyasını oluşturun:

```ini
[Unit]
Description=SKY-DIT Backend
Wants=network-online.target
After=network-online.target postgresql.service
Requires=postgresql.service
RequiresMountsFor=/u01/akis

[Service]
Type=simple
User=akis
Group=akis
WorkingDirectory=/u01/akis/work
ExecStart=/usr/bin/java -Dspring.config.additional-location=file:/u01/akis/conf/application.yaml -Xms256m -Xmx2g -jar /u01/akis/app/akis.jar
Restart=on-failure
RestartSec=10
TimeoutStopSec=60
UMask=0027
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

2 GiB heap başlangıç ayarıdır; yük testine göre ayarlanır. Açılış sırası
veritabanının bağlantıya hazır olduğunu garanti etmez; başarısız başlangıçta
servis yeniden dener. YAML veya JAR değişince `systemctl restart akis` gerekir;
birim dosyası değişince önce `systemctl daemon-reload` yapılır. İlk başlatma:

```bash
chmod 0644 /etc/systemd/system/akis.service
restorecon -v /etc/systemd/system/akis.service
systemd-analyze verify /etc/systemd/system/akis.service
systemctl daemon-reload
systemctl enable --now akis
systemctl status akis --no-pager
journalctl -u akis -n 100 --no-pager
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
```

Sağlık yanıtında `"status":"UP"` beklenir. Bu yalnız backend sağlığını gösterir;
giriş, yetkilendirme veya Oracle veri taşıma kabulü değildir.

### 11.1. Servis yönetim scriptleri

Hazır Bash scriptleri [Linux servis yönetimi](../scripts/linux/README.md)
belgesinde yer alır. `start.sh`, `stop.sh`, `status.sh` ve `akis-service.sh`
aynı `/u01/akis/bin` dizinine root tarafından kurulur. İlk üç dosya root:root
0755, ortak yardımcı root:root 0644 tutulur. Dosyalar LF satır sonu kullanır.

`start.sh` mevcut `akis.service` birimini başlatır ve backend health UP bekler;
aktif servisi yeniden başlatmaz. `stop.sh` yalnız backend için kontrollü stop
ister ve durduğunu doğrular. PostgreSQL/Nginx ayrıca işletilir; mevcut systemd
bağımlılıkları korunur. `status.sh` backend, PostgreSQL, Nginx ve yerel sağlık
durumunu salt okunur gösterir. Scriptleri kurmak servis restart gerektirmez.
Yerel testin geçmesi hedefte kurulum veya start/stop kabulü sayılmaz.

## 12. Nginx, TLS ve güvenlik duvarı

**Yer: Linux, root.** Kalıcı kurulumda DNS kaydı ve kurumca sağlanan,
kullanıcıların güvendiği TLS sertifikası kullanılır. İlk hedefte kullanıcı
alan adı/kurum sertifikası hazır olmadığından IP + geçici sertifika ile pilot
kurulumu seçti; bu alternatif aşağıda anlatılır. Kurum sertifikası hazırsa
aşağıdaki gerçek dosyaları güvenli biçimde yerleştirin:

```text
/u01/akis/conf/tls/fullchain.pem
/u01/akis/conf/tls/privkey.pem
```

IP üzerinden pilot için, **yalnız sertifika dosyaları henüz yoksa**, aşağıdaki
komutla 90 günlük kendinden imzalı sunucu sertifikası oluşturulabilir. Örnekteki
172.16.11.19 hedef sunucunun gerçek IP'siyle eşleştirilir. Tarayıcı güven uyarısı
beklenir; kurum sertifikası hazır olduğunda geçici sertifika değiştirilir.
Özel anahtar ekrana veya kurulum kaydına yazılmaz:

```bash
(
set -e
umask 077
test ! -e /u01/akis/conf/tls/fullchain.pem
test ! -e /u01/akis/conf/tls/privkey.pem
openssl req -x509 -newkey rsa:3072 -sha256 -noenc -days 90 -subj '/CN=172.16.11.19' -addext 'subjectAltName=IP:172.16.11.19' -addext 'basicConstraints=critical,CA:FALSE' -addext 'keyUsage=critical,digitalSignature,keyEncipherment' -addext 'extendedKeyUsage=serverAuth' -keyout /u01/akis/conf/tls/privkey.pem -out /u01/akis/conf/tls/fullchain.pem
chown root:root /u01/akis/conf/tls/fullchain.pem /u01/akis/conf/tls/privkey.pem
chmod 0644 /u01/akis/conf/tls/fullchain.pem
chmod 0600 /u01/akis/conf/tls/privkey.pem
restorecon -Rv /u01/akis/conf/tls
openssl x509 -in /u01/akis/conf/tls/fullchain.pem -noout -subject -dates -ext subjectAltName
)
```

Her iki sertifika sağlama yolunda da dosya izinleri:

```bash
chown root:root /u01/akis/conf/tls/fullchain.pem /u01/akis/conf/tls/privkey.pem
chmod 0644 /u01/akis/conf/tls/fullchain.pem
chmod 0600 /u01/akis/conf/tls/privkey.pem
restorecon -Rv /u01/akis/conf/tls
```

Özel anahtar otomatik servis açılışıyla uyumlu olmalıdır; parolalı anahtar için
kurumun güvenli parola sağlama mekanizması ayrıca gerekir. Sertifika yenileme
sorumlusu ve yenileme sonrası `nginx -t` / reload süreci kaydedilir.

`/u01/akis/conf/nginx.conf` dosyasında gerçek DNS adını kullanın. Kullanıcının
seçtiği IP pilotunda aşağıdaki server_name değeri 172.16.11.19 olarak yazılır;
sertifikanın IP SAN değeri de aynı adres olmalıdır:

```nginx
server {
    listen 443 ssl;
    server_name akis.example.internal;

    ssl_certificate /u01/akis/conf/tls/fullchain.pem;
    ssl_certificate_key /u01/akis/conf/tls/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    root /u01/akis/frontend;
    index index.html;
    client_max_body_size 10m;
    access_log /u01/akis/logs/nginx/access.log;
    error_log /u01/akis/logs/nginx/error.log;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
        proxy_set_header Forwarded "";
        proxy_read_timeout 120s;
    }

    location /actuator {
        return 404;
    }

    location = /index.html {
        add_header Cache-Control "no-cache";
    }

    location /assets/ {
        try_files $uri =404;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

API yolunu korumak için `proxy_pass` sonunda `/` yoktur. Arayüz/API aynı origin
üzerindedir; Vite geliştirme proxy'si kullanılmaz. Nginx istemcinin gönderdiği
forwarded bilgilerini kendisi yeniden yazar. Backend internetten doğrudan
erişilebilir olmamalıdır. Bundle isteği için mevcut uygulama sınırı 10 MiB'dır.
`/actuator` dışarıya proxy edilmez. Bu tarif yalnız 443 kullanır; HTTP yönlendirmesi
istenirse 80 için ayrı bir server bloğu ve ağ kuralı hazırlanır.

Nginx'in `/etc/nginx/nginx.conf` dosyasında `http` bloğundan
`/etc/nginx/conf.d/*.conf` dosyalarının dahil edildiğini kontrol edin. Sonra:

```bash
printf '%s\n' 'include /u01/akis/conf/nginx.conf;' > /etc/nginx/conf.d/akis.conf
chmod 0644 /etc/nginx/conf.d/akis.conf /u01/akis/conf/nginx.conf
restorecon -v /etc/nginx/conf.d/akis.conf /u01/akis/conf/nginx.conf
nginx -t
systemctl enable --now nginx
systemctl reload nginx
firewall-cmd --get-active-zones
```

Gerçek arayüzün bağlı olduğu zone'u kullanın. İlk sunucuda zone `public` idi:

```bash
firewall-cmd --zone=public --add-service=https
firewall-cmd --permanent --zone=public --add-service=https
firewall-cmd --zone=public --list-all
```

Bu komutlar runtime ve kalıcı kurala HTTPS ekler; mevcut SSH kurallarını silmez.
Kurum istemci ağı bazında kısıtlı erişim istiyorsa sistem ekibi buna uygun kural
uygular. Java portunu dışarı açmayın. PostgreSQL uzaktan erişimi seçildiyse
5432 izni bu genel HTTPS adımından ayrı olarak 7.2'ye göre sınırlandırılır.

Özel log yolu standart Nginx logrotate kuralına kendiliğinden girmez.
`/etc/logrotate.d/akis-nginx` dosyası oluşturun:

```conf
/u01/akis/logs/nginx/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    create 0640 nginx nginx
    sharedscripts
    postrotate
        /bin/systemctl kill -s USR1 --kill-who=main nginx.service >/dev/null 2>&1 || true
    endscript
}
```

`logrotate` kurulu ve sistem zamanlayıcısına bağlı olmalıdır; değilse paket ve
zamanlama sistem ekibi tarafından eklenir. `logrotate -d
/etc/logrotate.d/akis-nginx` ile dosyaları değiştirmeden kuralı kontrol edin.
Journal disk sınırı/saklama politikası da sistem ekibince belirlenir.

## 13. Uçtan uca ilk kabul

**Yer: sunucu ve kullanıcı bilgisayarı.** Kullanıcı bilgisayarında DNS ve kurum
CA güveni hazır olmalıdır. TLS hatalarını `curl -k` ile geçip kabul vermeyin.

```bash
systemctl is-active postgresql akis nginx
systemctl is-enabled postgresql akis nginx
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health
curl --fail --head https://akis.example.internal/
curl --fail --silent --show-error https://akis.example.internal/api/v1/auth/csrf
```

Sunucuda kurum CA'sı henüz yüklenmemişse son iki test güvenilir istemci
bilgisayarından yapılır veya onaylı CA dosyası `--cacert` ile belirtilir.

Tarayıcıdan HTTPS adresinde giriş yapın. Giriş/çıkış, proje oluşturma, tanım
kaydetme ve sayfa yenilemeyle kaydın korunmasını deneyin. Girişsiz korumalı API
erişiminin reddedildiğini ve oturum çerezinin Secure olduğunu doğrulayın.
Planlı servis yeniden başlatmasından sonra kayıtlar ve giriş korunmalıdır
(oturum geçerlilik kuralları dahilinde). Planlı sunucu yeniden başlatmasında
diskin ve üç servisin kendiliğinden açıldığı ayrıca kontrol edilir.

Migration kontrolü:

```bash
runuser -u postgres -- /usr/bin/psql -X -h /var/run/postgresql -d akis_db \
  -c 'SELECT installed_rank, version, description, success FROM akis.flyway_schema_history ORDER BY installed_rank;'
```

Tablo/migration sayısı sürüme göre değişir; eski runbook'lardaki sabit adetler
kabul ölçütü olarak kullanılmaz. Başarılı migration geçmişi kurulacak JAR'ın
teslimat sürümüyle karşılaştırılır.

## 14. Oracle ve veri taşıma kabulü

Arayüzün açılması veri taşıma yeteneğinin devreye alındığı anlamına gelmez.
Sıra: kaynak/hedef ağ erişimi → veritabanı hesap/yetkileri → proje/bağlantı →
başarılı bağlantı testi → etkin bağlantı ve şema/ortam eşlemesi → metadata keşfi
→ tanım/yayın → kontrollü küçük veriyle kabul.

Mevcut credential resolver `TABLO` ve `ENV` sağlayıcılarını destekler. `TABLO`
seçilirse bağlantı credential'ı uygulamanın ilgili kayıt akışından verilir ve
uygulama tarafından şifrelenmiş referans çözülür. Bu, YAML içine keyfi Oracle
username/password alanları eklemek değildir; bağlantı akışı ayrıca kabul edilir.

`ENV` seçilirse resolver doğrudan `System.getenv` okur. YAML'a aynı isimde anahtar
yazmak işletim sistemi ortam değişkeni oluşturmaz ve bu sağlayıcıyla çalışmaz.
Bu yol gerekiyorsa kurumun sır enjeksiyonu/systemd ortam mekanizması ayrıca
tasarlanır; ana uygulama yapılandırması yine YAML kalır. Mevcut kurulumda genel
bir `.env` oluşturulmaz ve ENV için kendiliğinden bir enjeksiyon varsayılmaz.
Windows `run-backend.ps1` dönüşümü Linux servisinde otomatik yapılmaz.

Hedef Oracle ledger/çalışma nesneleri kontrollü DBA kurulumudur. Ayrıntılar:
[Oracle kurulum sözleşmesi](../database/oracle/README.md),
[çalıştırma durumu](IMPLEMENTATION_STATUS.md),
[recovery runbook](EXECUTION_RECOVERY_RUNBOOK.md).

İlgili runtime, worker, scheduler ve recovery bayrakları ilk kurulumda kapalı
kalır. Kullanılacak yola ait worker profili, hedef nesneler, yetkiler ve kabul
kanıtı hazır olmadan hepsi birden `true` yapılmaz. Operasyon sorumlusu, hangi
özelliğin hangi kabul sonucuyla açıldığını kaydeder. Kurulum rehberi Oracle
üzerinde kendiliğinden DDL/DML çalıştırmaz.

## 15. Yedekleme, mevcut veri taşıma, güncelleme ve geri dönüş

### 15.1. Metadata yedeği ve geri yükleme provası

**Yer: Linux, root.** Örnek dosya adını yedek alınan zamanla değiştirin:

```bash
runuser -u postgres -- /usr/bin/pg_dump -h /var/run/postgresql \
  -d akis_db -Fc -f /u01/akis/backups/akis-20261021-120000.dump
runuser -u postgres -- /usr/bin/pg_restore \
  --list /u01/akis/backups/akis-20261021-120000.dump
```

`--list` yalnız arşiv kataloğunu okur; geri yüklenebilirlik kanıtı değildir.
Prova için adı daha önce kullanılmamış ayrı veritabanı oluşturun. Prova sırasında
bu veritabanına uygulama başlatılmaz:

```bash
runuser -u postgres -- /usr/bin/createdb -h /var/run/postgresql \
  -O akis_app akis_restore_check_20261021
runuser -u postgres -- /usr/bin/pg_restore -h /var/run/postgresql \
  --exit-on-error -d akis_restore_check_20261021 /u01/akis/backups/akis-20261021-120000.dump
runuser -u postgres -- /usr/bin/psql -X -h /var/run/postgresql \
  -d akis_restore_check_20261021 -c 'SELECT count(*) FROM akis.flyway_schema_history;'
```

Prova sonucunda gerekli tablo/veri ve migration kontrollerini yapın; yalnız
kontrol edilmiş geçici veritabanı kurumun temizlik prosedürüyle kaldırılır.
Canlı `akis_db` üzerine deneme restore'u yapılmaz.

Tam geri dönüş için veritabanına ek olarak `application.yaml`, veri şifreleme anahtarı,
credential değişkenleri, TLS anahtarı/sertifikası, servis/Nginx ayarları ve ilgili
uygulama sürümü güvenli şekilde korunur. SQL dump'ı PostgreSQL rol parolalarını
ve dış Oracle verisini yedeklemez. Hedefte rol önceden oluşturulmalı; sahibi
farklı bir kaynak dump'ı için DBA sahiplik/yetki eşlemesini planlamalıdır.
Yedekler sunucu dışındaki onaylı alana da kopyalanır ve saklama süresi belirlenir.
Aynı diskteki `backups` tek başına disk arızasına karşı koruma değildir.

### 15.2. Mevcut Windows verisini taşıma

İki ayrı seçenek vardır:

- **Temiz başlangıç:** Bu rehberdeki boş DB ve yönetici bootstrap akışı.
- **Mevcut veriyi taşıma:** Kaynakta yazmaları durdurma, tutarlı PostgreSQL dump,
  hedef sürüm/rol uyumluluğu kontrolü, yeni boş DB'ye restore, aynı şifreli veri
  için gereken mevcut anahtarın güvenli taşınması ve uygulama kabulü.

Restore yapılacaksa boş veritabanında uygulama açıp migration/bootstrap
çalıştırmadan önce geri yükleme tamamlanır. Var olan etkin yönetici yeniden
bootstrap edilmez. Proje JSON export/import'u tam DB yedeği değildir; kullanıcı,
sır ve çalışma kanıtlarının tümünü taşımaz. Başka bağımsız ortama tasarım
taşınacaksa ortam sırları yeniden verilir. Kaynak çalışma ortamına dokunmadan
önce kesinti/geri dönüş planı kesinleştirilir.

### 15.3. Yeni uygulama sürümü ve geri dönüş

1. Yeni paketi ayrı `releases` dizinine açın, checksum ve VERSION doğrulayın.
2. Migration değişikliklerini ve eski JAR ile geri uyumluluğu inceleyin.
3. Planlı bakımda kullanıcı yazmalarını ve çalışan veri işlerini durdurun;
   tamamlanmamış işler için recovery prosedürünü uygulayın.
4. Servisi durdurun: `systemctl stop akis`.
5. DB yedeği, mevcut YAML/anahtar ve aktif backend/frontend sürümünü güvenli saklayın.
6. Backend'i yeni JAR ile değiştirin. Arayüzü temiz bir hazırlık dizininde tam
   paket olarak hazırlayıp eski dizini koruyarak değiştirin; yalnız yeni dosyaları
   üstüne eklemek eski asset'leri biriktirir. Sahiplik ve SELinux etiketlerini
   yeniden uygulayın. Migration'ları elle düzenlemeyin.
7. `systemctl start akis`, sağlık kontrolü ve 13. bölümdeki kabulü çalıştırın.
8. Hata varsa bakım durumunu sürdürün. Eski JAR'a dönüş yalnız şema uyumluysa
   yapılır. Şema uyumsuzsa servis durmuşken uygun DB yedeği + eski JAR/frontend
   + doğru anahtar/ayarlar birlikte geri yüklenir. Yedekten sonraki yazmaların
   kaybı ayrıca değerlendirilir; otomatik ve kör restore yapılmaz.

## 16. Sık sorunlar

| Belirti | İlk kontrol |
|---|---|
| Java class version / UnsupportedClassVersionError | `/usr/bin/java -version`; servis gerçekten Java 25 mi kullanıyor? |
| PostgreSQL başlamıyor | `journalctl -u postgresql`, PGDATA override, `/u01` mount, sahiplik ve SELinux |
| Backend DB bağlantısı reddediliyor | PostgreSQL durumu, loopback/5432, `pg_hba.conf`, rol/parola eşleşmesi |
| FATAL: database "akis_db" does not exist | Rol/parola oluşturma ile DB oluşturma ayrı adımlardır; kısmi ilk kurulumda DB eksik kalabilir. Mevcut rolü silmeden yalnız eksik CREATE DATABASE adımını tamamlayın, sonra login testini tekrarlayın |
| Flyway hatası | Kurulu JAR sürümü, migration geçmişi, DB sahibinin DDL yetkisi; history silmeyin |
| Bootstrap terminal hatası | Gerçek etkileşimli terminal kullanımı; pipe/heredoc/arka plan olmaması |
| Bootstrap mevcut yönetici hatası | Kurulum zaten bootstrap edilmiş olabilir; ikinci ilk kurulum yapmayın |
| Nginx 403 | `/u01` üst dizin geçiş izinleri, frontend sahipliği ve `httpd_sys_content_t` etiketi |
| Nginx 502 | Backend sağlığı, proxy adresi, SELinux `httpd_can_network_connect` ve AVC kayıtları |
| DBeaver Connection attempt timed out | Yerel login başarılıysa istemciden TCP erişimi, etkin firewall zone'u, dönüş rotası ve sunucuya gelen SYN paketleri incelenir. Hata tek başına parola sorunu veya belirli bir ağ cihazının engeli olarak yorumlanmaz |
| Giriş/CSRF/çerez sorunu | HTTPS, Secure cookie, `/api` proxy'si, host/forwarded header ve tarayıcı origin'i |
| Sayfa yenileyince 404 | Nginx `try_files ... /index.html` kuralı |
| IP başına giriş sınırı herkesi etkiliyor | Gerçek istemci IP'si Tomcat'e güvenli proxy zinciriyle ulaşıyor mu? |
| Şifreli değer çözülemiyor | YAML'daki `akis.security.data-encryption-key`, veri oluşturulurken kullanılan anahtar mı? |
| Oracle credential bulunamıyor | TABLO/ENV seçimi ve referansın çözülmesi; YAML anahtarı System.getenv değeri değildir |
| Çalıştır düğmesi/worker çalışmıyor | Runtime kabulü ve özellik bayrakları; yalnız UI kurulumu yeterli değil |

SELinux için gerekirse root olarak `ausearch -m AVC -ts recent` çıktısını
inceleyin. Destek için gerçek YAML dosyasını veya secret içeren süreç ortamını paylaşmayın;
logları da gerçek sır içerip içermediği açısından kontrol ederek iletin.

## 17. Ortam kurulum ve kabul kaydı

Her ortam için bu tablonun ayrı kopyası tutulur. Tamamlanmayan satır `bekliyor`
kalır; bu rehberin varlığı kurulum kabulü sayılmaz.

| Kontrol | Sonuç / tarih / sorumlu |
|---|---|
| Sunucu/IP, OS minor sürümü, mimari | Bekliyor |
| `/u01` disk/mount ve kapasite kararı | Bekliyor |
| Paket kaynağı ve tam RPM sürüm listesi | Bekliyor |
| Java 25 / PostgreSQL 18 / Nginx sürüm doğrulaması | Bekliyor |
| Uygulama VERSION, commit, dirty durumu, arşiv SHA-256 | Bekliyor |
| Yerel derleme/lint/birim testleri ve ayrı entegrasyon kabulü | Bekliyor |
| Kullanıcılar, dizin sahipliği, YAML/anahtar saklama | Bekliyor |
| SELinux Enforcing, kalıcı etiketler ve proxy izni | Bekliyor |
| PostgreSQL gerçek PGDATA, loopback ve rol yetkileri | Bekliyor |
| Başarılı migration geçmişi ve ilk yönetici | Bekliyor |
| DNS, TLS zinciri, sertifika yenileme sorumlusu | Bekliyor |
| Nginx/firewalld, dışarı kapalı 8080; 5432 kapalı veya yalnız onaylı DB istemcisine açık | Bekliyor |
| Giriş/çıkış, CSRF, Secure cookie, gerçek istemci IP'si | Bekliyor |
| Proje/tanım kaydı ve kalıcılık | Bekliyor |
| Servis ve planlı sunucu restart kabulü | Bekliyor |
| Log rotasyonu, journal sınırı ve disk izleme | Bekliyor |
| Yedek, ayrı DB'ye restore provası, sunucu dışı kopya | Bekliyor |
| Kaynak/hedef bağlantıları ve ayrı veri taşıma kabulü | Bekliyor |
| Açılan runtime bayrakları ve kabul referansı | Bekliyor |
| Güncelleme/geri dönüş ve operasyon sorumlusu | Bekliyor |

## 18. Kaynaklar ve proje ile eşleştirme

- [Kök POM](../pom.xml): Java 25 ve Spring Boot sürümü.
- [Backend POM](../backend/pom.xml): JDBC bağımlılıkları ve JAR'a alınan baseline.
- [Uygulama ayarları](../backend/src/main/resources/application.yml): gerçek env
  adları, Flyway, session, runtime bayrakları ve health.
- [Credential resolver](../backend/src/main/java/tr/com/innova/akis/oracle/EnvironmentCredentialResolver.java):
  TABLO/ENV çözümleme ve doğrudan işletim sistemi ortamı okuma.
- [Spring Boot harici yapılandırma](https://docs.spring.io/spring-boot/4.1/reference/features/external-config.html):
  YAML/properties desteği ve `spring.config.additional-location` davranışı.
- [BootstrapAdminCommand](../backend/src/main/java/tr/com/innova/akis/security/BootstrapAdminCommand.java)
  ve [BootstrapAdminService](../backend/src/main/java/tr/com/innova/akis/security/BootstrapAdminService.java):
  terminal üzerinden yönetici oluşturma.
- [Yerel DB runbook](LOCAL_DATABASE_RUNBOOK.md): geliştirme/Docker bağlamı;
  bu Linux rehberindeki yerleşimin yerine kullanılmaz.
- [PostgreSQL Red Hat ailesi paketleri](https://www.postgresql.org/download/linux/redhat/):
  paket kaynağı ve dağıtım/minor sürüm desteği.
- [PostgreSQL 18 initdb](https://www.postgresql.org/docs/18/app-initdb.html):
  veri dizini, encoding ve yerel/ağ kimlik doğrulama seçenekleri.
- [Temurin Linux paketleri](https://adoptium.net/installation/linux/): Java paketleri.
- [Vite 8 Node gereksinimi](https://vite.dev/blog/announcing-vite8): derleme makinesi.
- [Spring Boot PropertiesLauncher](https://docs.spring.io/spring-boot/specification/executable-jar/property-launcher.html):
  alternatif başlangıç sınıfı.
- [RHEL 10 Nginx ve SELinux](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/deploying_web_servers_and_reverse_proxies/setting-up-and-configuring-nginx):
  özel document root, TLS ve reverse proxy bağlantı izni.

Paket sağlayıcısı, işletim sistemi, ana dizin, proxy topolojisi veya kimlik
doğrulama modeli değişirse ilgili bölümler yeni ortamın kabulünde yeniden
doğrulanır. Bu rehberin uygulanabilirliği tüm Linux dağıtımları için varsayılmaz.
