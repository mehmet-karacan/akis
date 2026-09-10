# Oracle JDBC 19c bağlantı ve discovery spike'ı

## Durum

Uygulama ve yerel test harness'ı hazır. Gerçek Oracle 19c kaynak/hedef bağlantısı
sağlanmadığı için ortam doğrulaması bekliyor.

## Sabitlenen başlangıç profili

- Beklenen veritabanı ana sürümü: Oracle 19c
- JVM: Java 21
- JDBC Thin adayı: ojdbc17 23.26.3.0.0
- Kaynak hesabı: salt okunur
- Hedef hesabı: bu ilk probeda salt okunur sorgular
- Connect timeout: 10 saniye
- Read timeout: 30 saniye

Sürücü seçimi üretim desteği ilanı değildir. Kurumun gerçek Oracle RU sürümü,
JDK dağıtımı, TLS/wallet profili ve support koşullarıyla tekrar doğrulanacaktır.

## Bu probeda yapılanlar

- Kaynak ve hedef TCP erişim ön kontrolü
- JDBC Thin bağlantısı
- Database ve driver sürümünün raporlanması
- Database major version değerinin 19 olduğunun doğrulanması
- Current user için tablo ve view sayılarının okunması
- Secret ve bağlantı parolasının çıktıya yazılmaması

Bu adım DDL veya DML çalıştırmaz.

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

Bağlantı ve discovery geçtikten sonra ayrı, açık yazma izniyle type round-trip,
bounded batch, LOB streaming ve cancel senaryoları eklenecektir. Bu testler
geçici spike nesneleri yaratacağı için bağlantı probuyla otomatik birleştirilmez.
