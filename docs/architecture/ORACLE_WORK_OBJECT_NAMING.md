# Oracle Çalışma Nesnesi Adlandırma Sözleşmesi

## Karar

Oracle bağlantısı yalnız sağlayıcı, uç nokta ve kimlik bilgisini tanımlar. Geçici
ve staging nesnesi adlandırma alanları bağlantı tanımına eklenmez. Aynı bağlantı
birden fazla fiziksel şemaya hizmet edebildiği için çalışma nesnesi politikası,
onu kullanan fiziksel/çalışma şeması ve yürütme stratejisi bağlamında çözülür.

Mevcut pilot runtime hedef tabloya aynı transaction içinde `DELETE + INSERT`
uygular; henüz `C$`, `I$` veya `E$` çalışma tablosu oluşturmaz. Runtime tarafından
kullanılmayan bir ayar metadata modeline veya kullanıcı arayüzüne eklenemez.

## Zorunlu adlandırma

Staging çalışma motoru devreye alındığında platformun oluşturduğu bütün Oracle
nesneleri `AKIS_` sahiplik işaretini taşır:

- kaynak aktarım çalışma nesnesi: `AKIS_C$...`
- entegrasyon/staging nesnesi: `AKIS_I$...`
- hata/reject nesnesi: `AKIS_E$...`

Kullanıcı tam nesne adını serbest metinle belirleyemez. Runtime; nesne türü,
çalıştırma kimliği ve güvenli bir özet üzerinden çakışmasız adı üretir, Oracle
identifier sınırını uygular ve yalnız kendi ürettiği nesneleri temizler.

## Uygulama kapısı

Bu alanlar ancak aşağıdaki parçalar birlikte tamamlandığında ürüne eklenir:

1. Fiziksel şemadan ayrı veya onunla aynı olabilen çalışma şeması seçimi.
2. Staging stratejisi ve gerekli Oracle yetkileri için preflight.
3. Çalıştırma sahipliği, süre aşımı ve crash sonrası temizleme kaydı.
4. Aynı anda çalışan işlerde isim çakışmasını engelleyen deterministik üretici.
5. `AKIS_` dışındaki nesnelere DDL uygulanmasını engelleyen güvenlik kontrolü.

V024 ile registry workspace/lifecycle/retention/cleanup kanıtları genişletildi.
Ancak Oracle session-scoped DDL lock ve disposable-owner kabulü henüz tamamlanmış
değildir. Bu nedenle managed-DDL capability kapalı, bağlantı ekranındaki prefix
alanları ise yalnız çalışma şeması politikasına girdi olarak kalır; prefix sahiplik
kanıtı veya doğrudan kullanıcı DDL hedefi değildir.
