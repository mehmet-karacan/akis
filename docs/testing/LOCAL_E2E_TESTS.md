# Yerel uçtan uca UI testleri

AKIŞ tarayıcı testleri Playwright ile gerçek frontend ve backend üzerinde çalışır. Testler CI/CD'ye bağlı değildir; GitHub workflow oluşturulmamıştır.

## Ön koşullar

- PostgreSQL, güncel backend ve frontend çalışıyor olmalıdır.
- Kök dizindeki git tarafından izlenmeyen `.env` dosyasında `AKIS_DEV_USERNAME` ve `AKIS_DEV_PASSWORD` bulunmalıdır.
- Microsoft Edge kurulu olmalıdır. Başka bir Playwright kanalı için `AKIS_E2E_BROWSER_CHANNEL` ayarlanabilir.

## Çalıştırma

Kök dizinden:

```powershell
pwsh -ExecutionPolicy Bypass -File .\scripts\run-e2e.ps1
```

Tarayıcıyı görünür çalıştırmak için `-Headed`, Playwright UI kipini açmak için `-Ui` kullanılır. Görünür kip adımları varsayılan olarak 300 ms yavaşlatır; `-SlowMoMs 750` gibi bir değerle izleme hızı değiştirilebilir.

Kurulu Chrome'u görünür kullanmak için:

```powershell
$env:AKIS_E2E_BROWSER_CHANNEL="chrome"
pwsh -ExecutionPolicy Bypass -File .\scripts\run-e2e.ps1 -Headed -SlowMoMs 500
```

Son HTML raporunu açmak için:

```powershell
cd frontend
npm run test:e2e:report
```

Paket komutları doğrudan da çalıştırılabilir; bu durumda `AKIS_E2E_USERNAME` ve `AKIS_E2E_PASSWORD` süreç ortamında verilmelidir:

```powershell
cd frontend
npm run test:e2e
```

## Kapsam

- varsayılan İngilizce, Türkçe ve tema kalıcılığı;
- geçersiz kimlik bilgisi;
- ana proje ekranlarının gerçek API ile açılması;
- zorlanmış bağlantı kataloğu 500 hatası ve **Tekrar Dene** ile kurtarma;
- operasyon rolüne göre gezinme yetkileri;
- 320, 768 ve 1920 pikselde yatay taşma;
- eski topoloji ve çalıştırma URL'lerinin standart adreslere yönlenmesi.

Başarısız testlerde `frontend/test-results` altında ekran görüntüsü ve Playwright trace kaydı; `frontend/playwright-report` altında HTML raporu oluşur. Video ayrıca istenirse testten önce `AKIS_E2E_VIDEO=1` ayarlanır ve `npx playwright install ffmpeg` bir kez çalıştırılır. Bu dizinler git'e alınmaz.
