# Akış — Yönetici Sunumu İçin 30 Zorunlu Kabiliyet

Bu metin mevcut durum raporu değil, kurulacak platformun zorunlu ürün ve mimari
kapsamıdır. Her madde yaklaşık 30 saniyede anlatılabilecek uzunlukta tutulmuştur.

1. **Kurumsal veri entegrasyon merkezi**  
   Farklı sistemler arasındaki veri akışları tek platformdan tasarlanmalı, sürümlenmeli, çalıştırılmalı ve izlenmelidir.

2. **Görsel ve kod destekli geliştirme**  
   Kullanıcılar mapping, prosedür, paket ve yük planlarını görsel bileşenlerle kurabilmeli; ileri ihtiyaçlarda kontrollü SQL kullanabilmelidir.

3. **Kaynak–stage–hedef yaşam döngüsü**  
   Veri taşıma süreci kaynak okuma, çalışma alanına alma, doğrulama ve nihai hedefe yayınlama aşamalarıyla açıkça yönetilmelidir.

4. **Bilgi modülü tabanlı yürütme**  
   LKM, IKM ve CKM karşılıkları platformun kendi dilinde tanımlanmalı; taşıma, entegrasyon ve kalite yöntemleri tekrar kullanılabilir şablonlara dönüşmelidir.

5. **Ortamdan bağımsız mantıksal tasarım**  
   Geliştirilen akış değişmeden kalmalı; test ve üretimde kullanılacak fiziksel bağlantı ve şema ortam eşlemesiyle çözülmelidir.

6. **Sürümlü bağlantı ve şema yönetimi**  
   Oracle bağlantıları, fiziksel şemalar, çalışma şemaları ve LKM/IKM/CKM tablo önekleri değiştirilebilir fakat geçmiş çalışmalar için izlenebilir sürümler halinde tutulmalıdır.

7. **Değişmez çalıştırılabilir sürüm**  
   Çalıştırılan tanım sonradan değiştirilememeli; her çalışma tam olarak hangi nesne, değişken, bağlantı ve ortam sürümünü kullandığını kanıtlamalıdır.

8. **Çalıştırma öncesi güvenlik kapısı**  
   SQL söz dizimi, yetki, bağlantı, kaynak/hedef uyumu, değişkenler ve kapasite sınırları doğrulanmadan iş kuyruğa alınmamalıdır.

9. **Kalıcı çalıştırma kontrol düzlemi**  
   Koşu, deneme, adım, sahiplik, lease, heartbeat ve olay bilgileri PostgreSQL üzerinde kalıcı yönetilmeli; worker yeniden başlasa bile durum kaybolmamalıdır.

10. **Yarım kalan işten güvenli devam**  
    Sistem yalnız son okunan satırı değil, son doğrulanmış commit birimini esas almalı; kesinti sonrasında işi doğru transaction veya chunk sınırından sürdürebilmelidir.

11. **Transaction grubu farkındalığı**  
    Birlikte commit edilmesi gereken adımlar grup olarak ele alınmalı; yalnız SQL’in çalışması başarı sayılmamalı, commit veya rollback sonucu ayrıca kanıtlanmalıdır.

12. **Belirsiz commit mutabakatı**  
    Commit yanıtı kaybolduğunda sistem işi körlemesine tekrar etmemeli; hedef veritabanındaki kalıcı makbuzu bağımsız oturumdan doğrulayıp devam, durdurma veya manuel inceleme kararı vermelidir.

13. **Tekrarsız ve kayıpsız yeniden deneme**  
    Idempotency anahtarları, fencing token ve hedef ledger birlikte kullanılmalı; aynı işin ikinci kez yazılması veya eski worker’ın yeniden devreye girmesi engellenmelidir.

14. **Checkpoint ve chunk tabanlı büyük veri aktarımı**  
    Büyük veri setleri sınırlı satır ve byte bütçeli parçalara ayrılmalı; her parçanın sınırı, hash’i, sayacı, denemesi ve commit kanıtı saklanmalıdır.

15. **Sabit kaynak görünümü**  
    Oracle SCN benzeri snapshot mekanizmalarıyla retry sırasında aynı kaynak görünümü okunmalı; sonradan gelen insert, update veya delete eski çalışmanın kapsamını değiştirmemelidir.

16. **Sınırlı bellek ve backpressure**  
    Veri hacmi büyüdükçe JVM belleği doğrusal büyümemeli; kaynak okuma hedef yazma hızına göre durdurulabilmeli ve tüm veri hiçbir zaman belleğe alınmamalıdır.

17. **Güvenli staging ve atomik yayın**  
    Yükleme başarısızken nihai tablo korunmalı; doğrulanmış stage verisi kontrollü transaction ile yayınlanmalı, publish sonrası cleanup ayrı bir yaşam döngüsü olarak izlenmelidir.

18. **DDL ve çalışma nesnesi güvenliği**  
    Geçici tablo oluşturma, yetkilendirme ve silme işlemleri Oracle session lock ile seri hale getirilmeli; ad benzerliği tek başına nesne sahipliği kanıtı sayılmamalıdır.

19. **Veri kalite kuralları ve red kayıtları**  
    Zorunlu alan, tip, tekillik, referans ve iş kuralı kontrolleri çalıştırılmalı; reddedilen kayıtlar nedenleriyle ayrıştırılmalı ve hedefe sessizce taşınmamalıdır.

20. **Değişkenler ve tarihsel değer çözümleme**  
    Proje değişkenleri SQL içinde kolay bir söz dizimiyle kullanılmalı; her çalışmada çözümlenen değer ile önceki değer geçmişi saklanarak yeniden çalıştırmada aynı girdi garanti edilmelidir.

21. **Uçtan uca lineage ve etki analizi**  
    Bir hedef kolonun hangi kaynak, dönüşüm, değişken, bilgi modülü ve sürümden üretildiği görülebilmeli; değişiklikten etkilenecek akışlar yayın öncesinde bulunmalıdır.

22. **Operasyon merkezi ve anlaşılır kanıt**  
    Operatör nesnenin çalışıp çalışmadığını, hangi adımda olduğunu, okunan/yazılan/commit edilen satırları ve ilk gerçek hata nedenini tek ekrandan görebilmelidir.

23. **Kontrollü iptal ve recovery seçenekleri**  
    İptal isteği commit’i geri almış gibi gösterilmemeli; güvenli durumlarda yeniden dene, doğrulanmış işi koru, bağımlılığı yeniden üret veya mutabakat iste seçenekleri sunulmalıdır.

24. **Rol, yetki ve görev ayrılığı**  
    Geliştirici, operatör, bağlantı yöneticisi, yayın onaylayıcısı ve görüntüleyici yetkileri ayrılmalı; secret okuma ile bağlantıyı kullanma hakkı aynı kabul edilmemelidir.

25. **Değiştirilemez denetim izi**  
    Kim, neyi, ne zaman oluşturdu, değiştirdi, yayınladı, çalıştırdı veya iptal etti bilgisi append-only olaylarla tutulmalı ve correlation kimliğiyle izlenebilmelidir.

26. **Kurumsal teknoloji mimarisi**  
    Backend Java 21 ve Spring Boot, arayüz React ve TypeScript, metadata/control plane PostgreSQL, veri yürütme katmanı Oracle JDBC üzerine kurulmalı; katmanlar port–adapter yaklaşımıyla ayrılmalıdır.

27. **Modern ve standart kullanıcı deneyimi**  
    Ant Design tabanlı ortak bileşenler, tek tip font/ölçek, açık–koyu tema, responsive yerleşim, erişilebilir klavye kullanımı ve tutarlı toast/dialog davranışları bütün ekranlarda zorunlu olmalıdır.

28. **Gözlemlenebilirlik ve operasyon metrikleri**  
    Süre, throughput, retry, lease, kaynak okuma, hedef commit, hata sınıfı ve darboğaz metrikleri toplanmalı; log, metric ve trace aynı çalışma kimliğiyle ilişkilendirilmelidir.

29. **Hata enjeksiyonu ve kapasite kabulü**  
    Bağlantı kopması, worker ölümü, commit yanıtı kaybı ve metadata kesintisi gerçekçi test edilmelidir; 1 milyon–3 milyar kayıt profilleri doğruluk, süre, bellek ve recovery ölçümleriyle onaylanmalıdır.

30. **Genişleyebilir ve yönetişimli platform**  
    Yeni veritabanı adaptörleri, bilgi modülleri ve nesne türleri çekirdeği bozmadan eklenebilmeli; her yeni yetenek versioning, güvenlik, test, capability ve geriye uyumluluk kapılarından geçmelidir.

## Sunumda verilecek ana mesaj

Akış yalnız SQL çalıştıran bir araç değildir; verinin doğru sürümle, doğru ortamda,
kanıtlanabilir transaction sınırlarıyla taşınmasını ve kesinti sonrasında kayıp ya da
tekrar üretmeden güvenli biçimde devam etmesini sağlayan kurumsal entegrasyon
platformudur.
