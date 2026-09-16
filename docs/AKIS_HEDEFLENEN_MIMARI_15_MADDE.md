# Akış — Hedeflenen Mimari: 15 Temel Karar

Bu metin mevcut durum beyanı değil, geliştirilecek platformun hedeflenen teknik
mimarisidir. Her madde yönetici sunumunda yaklaşık 30 saniyede anlatılabilecek
uzunlukta hazırlanmıştır.

1. **Kurumsal entegrasyon ve metadata mimarisi**  
   Akış; mapping, prosedür, paket, yük planı, değişken ve bilgi modüllerini tek platformda tasarlayacaktır. Tanımlar, bağlantılar ve çalışma kanıtları ilişkisel metadata olarak PostgreSQL’de; gerçek veri hareketi kaynak ve hedef sistemlerde yönetilecektir.

2. **Java tabanlı modüler yürütme motoru**  
   Backend Java 21 ve Spring Boot üzerinde port–adapter yaklaşımıyla kurulacaktır. Tanım yönetimi, planlama, Oracle erişimi, worker, recovery ve denetim katmanları ayrılarak yeni veritabanı adaptörlerinin çekirdeği değiştirmeden eklenmesi sağlanacaktır.

3. **Mantıksal tasarım ve fiziksel ortam çözümleme**  
   Akış bir kez mantıksal kaynak ve hedeflerle tasarlanacaktır. Test ve üretim ortamlarında farklı bağlantı, fiziksel şema, çalışma şeması ve güvenlik politikaları kullanılacak; aynı tasarım doğrudan fiziksel sunucu adına bağımlı olmayacaktır.

4. **Bilgi modülleriyle standart veri işleme**  
   LKM kaynak ile stage arasındaki taşımayı, IKM stage ile hedef arasındaki yükleme stratejisini, CKM ise veri kalite kontrollerini tanımlayacaktır. Kullanıcılar bu modülleri platformun kendi diliyle seçip yapılandıracak; SQL ve çalışma tabloları kontrollü şablonlardan üretilecektir.

5. **Değişmez sürüm ve tekrarlanabilir çalışma**  
   Çalıştırılan tanım, bağlantı revizyonu, ortam eşlemesi, SQL, değişken değerleri ve bilgi modülü sürümü immutable bir çalışma girdisine dönüştürülecektir. Sonraki düzenlemeler devam eden veya geçmiş bir çalışmanın anlamını değiştiremeyecektir.

6. **Milyar kayıt için keyset tabanlı kaynak okuma**  
   Büyük tablolar `OFFSET` ile değil, indeksli ve değişmeyen anahtar üzerinden aralıklar halinde okunacaktır. Oracle kaynağında başlangıç SCN’si sabitlenecek; `WHERE ID > :lastCommittedId ORDER BY ID FETCH FIRST :chunkSize ROWS ONLY` yaklaşımıyla veri belleğe yığılmadan aktarılacaktır.

7. **Sınırlı bellek ve kontrollü veri akışı**  
   Her okuma hem satır hem byte bütçesine sahip olacaktır. JDBC `fetchSize` yalnız ağ optimizasyonu olarak görülecek; uygulama buffer’ı ayrıca sınırlandırılacak ve hedef yavaşladığında kaynak okuma backpressure ile durdurulacaktır.

8. **Batch insert ve kanıtlanabilir commit**  
   Kaynaktan alınan örneğin 10.000 satırlık chunk, hedefte tek transaction içinde JDBC `executeLargeBatch` veya uygun olduğunda set-based `INSERT … SELECT` ile yazılacaktır. Aynı transaction’da chunk kimliği, sınırı, satır sayısı ve payload hash’i hedef ledger’a kaydedildikten sonra commit yapılacaktır.

9. **Checkpoint ve yarıda kalma sonrası devam**  
   Sistem son okunan satırı değil, son doğrulanmış commit edilmiş chunk’ı checkpoint kabul edecektir. Worker veya bağlantı kesildiğinde çalışma baştan başlamayacak; aynı kaynak snapshot’ı ve son commit edilen anahtardan güvenli biçimde devam edecektir.

10. **Belirsiz commit ve tekrarsız recovery**  
    Commit çağrısı sırasında bağlantı kaybolursa işlem otomatik tekrar edilmeyecektir. Yeni ve bağımsız bir Oracle oturumuyla hedef ledger doğrulanacak; makbuz varsa chunk korunacak, yoksa güvenli retry değerlendirilecek, sonuç kanıtlanamıyorsa çalışma `OUTCOME_UNKNOWN` olarak manuel mutabakata alınacaktır.

11. **Transaction grupları ve gerçek başarı tanımı**  
    SQL’in hatasız çalışması tek başına başarı sayılmayacaktır. Birlikte commit edilen prosedür adımları transaction grubu olarak izlenecek; `EXECUTED_UNCOMMITTED`, `COMMIT_CONFIRMED`, `ROLLBACK_CONFIRMED` ve `OUTCOME_UNKNOWN` sonuçları ayrı tutulacaktır.

12. **Stage, doğrulama ve atomik hedef yayınlama**  
    Büyük yük önce sahipliği doğrulanmış çalışma tablosuna alınacaktır. Kolon, anahtar, duplicate, satır sayısı ve iş kuralları doğrulandıktan sonra hedef yayınlanacak; stage yükü başarısızsa nihai tabloya dokunulmayacak, publish ve cleanup durumları birbirinden ayrı izlenecektir.

13. **Worker sahipliği, fencing ve kontrollü iptal**  
    Her çalışma lease, heartbeat ve monotonik fencing token ile yalnız bir worker tarafından yürütülecektir. Eski worker’ın yeniden yazması engellenecek; iptal isteği commit’i geri alınmış gibi gösterilmeyecek ve her yeni SQL/commit sınırından önce sahiplik tekrar doğrulanacaktır.

14. **Operasyon, audit ve uçtan uca izlenebilirlik**  
    Operasyon ekranı nesne → çalışma → adım → transaction/chunk ağacını gösterecektir. Okunan, denenen, yazılan, commit edilen ve reddedilen kayıtlar karıştırılmayacak; kim, hangi sürümü, hangi ortamda, ne zaman çalıştırdı bilgisi değiştirilemez olay ve correlation kimliğiyle saklanacaktır.

15. **Hata enjeksiyonu ve artan kapasite kabulü**  
    Kaynak kopması, hedef batch hatası, worker ölümü, commit yanıtı kaybı, metadata kesintisi ve publish sonrası cleanup hatası ayrı ayrı test edilecektir. Kapasite kabulü küçük deterministic veriyle başlayacak; 1 milyon, 10 milyon ve izinli ortamda 1–3 milyar kayıt için doğruluk, süre, throughput, bellek, undo/redo ve recovery sonuçları ölçülecektir.

## Yönetici sunumunun ana mesajı

Akış yalnız SQL çalıştıran bir uygulama olarak değil; milyarlarca kaydı sınırlı
bellekle işleyen, her commit’i kanıtlayan ve kesinti sonrasında veri kaybı ya da
çift kayıt üretmeden devam edebilen kurumsal veri entegrasyon platformu olarak
tasarlanacaktır.
