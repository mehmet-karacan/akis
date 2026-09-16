# Akış implementation status

## 2026-09-16 execution recovery çalışması

Güvenli retry/resume/restart ve bounded Oracle transfer çekirdeği uygulanmaktadır.
Mimari ve gerçek kabul sınırı [recovery mimarisinde](architecture/EXECUTION_RECOVERY.md),
[runbook'ta](EXECUTION_RECOVERY_RUNBOOK.md) ve
[kabul kaydında](EXECUTION_RECOVERY_ACCEPTANCE.md) ayrılmıştır. Yeni runtime
capability'leri gerçek Oracle ve kapasite kabulü tamamlanmadığı için kapalıdır.

## 2026-09-15 güvenilirlik çalışması

n8n karşılaştırma raporunun uygulaması başladı, tamamlanmadı. Güncel madde bazlı
durum, test sınırları ve ortam engelleri [uygulama takibinde](N8N_IMPLEMENTATION_PLAN.md).
Aşağıdaki 2026-09-12 kilometre taşı, genel amaçlı paket/mapping/değişken motorunun
veya tüm üretim kabul testlerinin tamamlandığı şeklinde yorumlanmamalıdır.

Status date: 2026-09-12

## Tamamlanan güncel kilometre taşı

Temiz `akis` metadata temeli ve yönetilen Oracle Prosedür dikey dilimi
tamamlandı. Uygulama repository'leri çalışma zamanında `entegrasyon` şemasını
kullanmaz; üretim Flyway konumu yalnız temiz V001-V012 zinciridir.

- Kimlik, kullanıcı, sistem/proje rolleri, yetkiler ve zamanlı proje üyeliği
- Zorunlu proje seçimi ve boş ilk proje ekranı
- Oracle/PostgreSQL/MySQL sağlayıcı kartları; kullanıcıya tek güncel bağlantı olarak sunulan JDBC/JNDI ayarları
- Fiziksel/mantıksal şemalar, ortamlar ve mantıksal şema oluştururken atomik ilk eşleme
- Klasör ağacı; Mapping, Paket, Prosedür, Değişken, Sekans, kullanıcı fonksiyonu,
  knowledge module ve load plan tanım/sürüm sözleşmeleri
- Model, alt model, veri nesnesi ve değişmez metadata snapshot'ları
- Sınırsız sıralı Prosedür tasarımı; kaynak/hedef SQL ayrımı ve bağlantıdan
  devralınan zaman aşımı politikası
- Doğrulama, Scenario derleme, çalıştırılabilir yayın ve onay sınırı
- Koşu/adım ağacı, eklemeli olay günlüğü, lease, heartbeat ve target fencing
- Mutating işlem niyeti, kesin retry kanıtı ve belirsiz commit mutabakat bariyeri
- Secretsız tam Proje Paketi V2 içe/dışa aktarımı
- İngilizce varsayılan, Türkçe destekli responsive UI
- Proje, Operasyonlar ve Bağlantılar çalışma sekmeleri; Proje altında kalıcı nesne ağacı

## Kalıcı yerel veritabanı durumu

- `akis`: 50 uygulama tablosu ve Flyway geçmiş tablosu
- Flyway: V001-V012 başarılı
- `entegrasyon`: kaldırıldı
- İlk yerel yönetici: idempotent bootstrap ile oluşturuldu
- Projeler ve tasarım nesneleri: bilinçli olarak boş
- Manuel istek, worker ve Prosedür runtime bayrakları: kapalı
- CI/CD ve GitHub workflow: kapalı/yok

Kesim öncesi yedek geri-yükleme provası izole veritabanında geçti. Kesim sonrası
temiz yedek de `pg_restore --list` ile doğrulandı. İşletim adımları
`docs/LOCAL_DATABASE_RUNBOOK.md` içindedir.

## Kabul kapıları

- Backend: tüm birim/entegrasyon testleri
- Frontend: birim testleri, lint ve production build
- PostgreSQL: baseline, authorization, connections, definitions, catalog,
  scenarios, releases, execution, bundle ve worker kabul betikleri
- HTTP: doğru yerel kimlikle boş proje listesi; hatalı kimlikle 401
- Tarayıcı: temiz başlangıçta zorunlu giriş ekranı

## Sonraki ürün yol haritası

Bu kilometre taşı ODI'nin tüm modüllerinin bitmiş olduğu anlamına gelmez.
Değişken/Sekans/Paket/Load Plan runtime semantiği, zamanlayıcı, bildirimler,
kurumsal OIDC/SSO, retention/observability, CDC, lineage, ek motorlar ve ölçek
testleri ayrı ürün dilimleridir. Güvenlik nedeniyle CI/CD, manuel çalıştırma ve
worker ancak açık kabul kararıyla etkinleştirilecektir.
