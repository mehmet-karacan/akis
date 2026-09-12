# Proje nesnesi gezinme kararı

Proje seçildikten sonra klasör ve nesne ağacı uygulama kabuğunun kalıcı sol
panelinde gösterilir. Kullanıcı bağlantılar, çalıştırmalar veya proje giriş
ekranındayken de aynı proje bağlamını görmeye devam eder.

## Hiyerarşi

```text
Proje
├─ Klasör
│  ├─ Alt klasör
│  ├─ Paket
│  ├─ Prosedür
│  └─ Mapping / Interface
├─ Değişken
└─ Sequence
```

- Her nesne kendi tür ikonuyla gösterilir.
- Klasörler daraltılıp genişletilebilir.
- Klasörler nötr hiyerarşi düğümleridir; geliştirme, model veya yükleme planı
  gibi bir klasör türü taşımaz. Anlam, klasörün içindeki nesnelerden gelir.
- Kök klasör oluştururken proje, alt klasör oluştururken üst klasör mevcut
  bağlamdan alınır. Form yalnız ad, kod ve açıklama ister.
- Bir nesne seçildiğinde doğrudan o nesnenin editörü açılır; ayrı bir
  `Geliştirme` menü adımı gerekmez.
- Nesneye sağ tıklama ve satırdaki üç nokta düğmesi aynı bağlamsal işlem
  menüsünü açar. Menü nesne türüne göre `Aç`, `Senaryo Oluştur` ve `Çalıştır`
  eylemlerini gösterir.
- `Çalıştır` doğrudan çalıştırma başlatmaz; seçili nesnenin etkin yayınlarını
  süzerek operasyon ekranındaki onay penceresini açar. Böylece yanlışlıkla
  üretim çalıştırması başlatılmaz.
- `Senaryo Oluştur`, çalıştırılabilir nesnenin son değişmez sürümünü derler.
  Henüz sürüm yoksa kullanıcıya bağlamsal uyarı verilir.
- Seçili nesne URL'deki `definition` parametresiyle temsil edilir. Böylece geri
  gezinme, yenileme ve doğrudan bağlantı davranışı korunur.
- Kaydedilmemiş değişiklik varsa uygulama kabuğundaki mevcut güvenli gezinme
  doğrulaması kullanılmaya devam eder.
- Genel bakış yalnız proje giriş bilgisidir. Çalışma alanı kısayolları burada
  tekrarlanmaz.

Kalıcı ağaç tanım oluşturma, taşıma veya yenileme sonrasında
`akis:definitions-changed` uygulama içi olayıyla tekrar yüklenir.
