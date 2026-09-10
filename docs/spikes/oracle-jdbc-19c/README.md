# Oracle JDBC 19c bağlantı ve discovery spike'ı

## Durum

Gerçek Oracle 19c kaynak ve hedef bağlantıları doğrulandı. 10 Eylül 2026 tarihli
çalıştırmada `TTBP.HAKEDIS_TIPI` tablosunun 13 kolonlu şeması hedefte
`INNOVA_ODI.STG_HAKEDIS_TIPI` olarak oluşturuldu ve 33 satır aktarıldı. Commit
sonrasında açılan yeni bağlantıda hedef satır sayısı tekrar 33 olarak doğrulandı.

## Sabitlenen başlangıç profili

- Beklenen veritabanı ana sürümü: Oracle 19c
- JVM: Java 21
- JDBC Thin adayı: ojdbc17 23.26.3.0.0
- NLS companion: orai18n 23.26.3.0.0
- Kaynak bağlantısı: salt okunur
- Hedef bağlantısı: varsayılan olarak salt okunur; yazma yalnız açık anahtarla
- Connect timeout: 10 saniye
- Read timeout: 30 saniye

Sürücü seçimi üretim desteği ilanı değildir. Kurumun gerçek Oracle RU sürümü,
JDK dağıtımı, TLS/wallet profili ve support koşullarıyla tekrar doğrulanacaktır.

## Bu spike'ta yapılanlar

- Kaynak ve hedef TCP erişim ön kontrolü
- JDBC Thin bağlantısı
- Database ve driver sürümünün raporlanması
- Database major version değerinin 19 olduğunun doğrulanması
- Current user için tablo ve view sayılarının okunması
- Kaynak tablo kolonlarının ve satır sayısının okunması
- Kaynak tip/nullability bilgileriyle hedef staging tablosunun oluşturulması
- Explicit kolon listesi ve bounded batch ile veri aktarımı
- Commit öncesi ve sonrasında satır sayısı mutabakatı
- Secret ve bağlantı parolasının çıktıya yazılmaması

Normal çalıştırma DDL veya DML çalıştırmaz.

## İlk gerçek ortam bulgusu

Kaynak bağlantısının ilk denemesi ORA-17056 ile durdu. Kaynak veritabanı
WE8ISO8859P9 karakter setini kullandığı için ojdbc17 tek başına yeterli olmadı;
aynı sürümde orai18n companion jar'ı runtime'a eklendi. Bu bağımlılık çıkarılırsa
kaynak bağlantısı daha authentication aşamasında kurulamaz.

## Açık yazma kapısı

Normal probe salt okunurdur. Tablo oluşturma ve batch insert yalnız komut satırında
EnableCopy anahtarı açıkça verildiğinde etkinleşir:

    .\scripts\run-oracle-probe.ps1 -EnableCopy

Yazma modu hedef tablonun bulunmadığını tekrar doğrular; mevcut tabloyu drop,
truncate veya overwrite etmez. Hedef tablo oluşturulduktan sonra insert işlemleri
tek target transaction içinde yapılır. Commit öncesinde kaynak, kopyalanan ve hedef
satır sayıları eşleşmezse DML rollback edilir.

Oracle DDL işlemleri implicit commit yaptığı için tablo oluşturulduktan sonra insert
başarısız olursa boş hedef tablo kalabilir; otomatik drop yapılmaz. Sonraki yazma
denemeleri mevcut hedef tabloyu görür ve güvenli biçimde durur.

## Çalıştırma

Gerçek değerleri yalnız repository kökündeki Git-ignored .env dosyasına girin:

    AKIS_ORACLE_SOURCE_URL=jdbc:oracle:thin:@//host:1521/service
    AKIS_ORACLE_SOURCE_USERNAME=...
    AKIS_ORACLE_SOURCE_PASSWORD=...
    AKIS_ORACLE_TARGET_URL=jdbc:oracle:thin:@//host:1521/service
    AKIS_ORACLE_TARGET_USERNAME=...
    AKIS_ORACLE_TARGET_PASSWORD=...

Ardından:

    .\scripts\run-oracle-probe.ps1

TCP ön kontrolü başarısız olursa script kurumsal VPN'in açılmasını ister.

## Sonraki kapı

Bu kontrollü kopyalama, platformun ilk Oracle-to-Oracle yürütme dilimi olarak
kullanılabilir. Sonraki aşamada aynı güvenlik kapıları korunarak job tanımı,
çalıştırma geçmişi, hata kaydı ve yeniden başlatma davranışı platform servislerine
taşınacaktır. LOB streaming ve cancel senaryoları ayrı geçici nesnelerle test
edilmelidir.
