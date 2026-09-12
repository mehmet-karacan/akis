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
- Bir nesne seçildiğinde doğrudan o nesnenin editörü açılır; ayrı bir
  `Geliştirme` menü adımı gerekmez.
- Seçili nesne URL'deki `definition` parametresiyle temsil edilir. Böylece geri
  gezinme, yenileme ve doğrudan bağlantı davranışı korunur.
- Kaydedilmemiş değişiklik varsa uygulama kabuğundaki mevcut güvenli gezinme
  doğrulaması kullanılmaya devam eder.
- Genel bakış yalnız proje giriş bilgisidir. Çalışma alanı kısayolları burada
  tekrarlanmaz.

Kalıcı ağaç tanım oluşturma, taşıma veya yenileme sonrasında
`akis:definitions-changed` uygulama içi olayıyla tekrar yüklenir.
