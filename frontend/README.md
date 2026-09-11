# Akış UI

React 19, TypeScript ve Vite tabanlı yönetim arayüzüdür. Varsayılan dil English,
ikinci dil Türkçe'dir. API istekleri geliştirmede Vite proxy üzerinden yerel
backend'e gider; credential kalıcı browser storage alanına yazılmaz.

    npm install
    npm run dev
    npm run lint
    npm test
    npm run build

Backend varsayılan olarak `http://127.0.0.1:8080` adresinde çalışmalıdır.
`VITE_API_PROXY_TARGET` ile geliştirme proxy hedefi değiştirilebilir.

İlk UI kapısı şu gerçek API akışlarını içerir:

- Proje seçimi ve oluşturma
- Connection/version, secret reference, schema, environment ve binding topolojisi
- Oracle 19c test/discovery işlemlerinin yalnız kullanıcı aksiyonuyla başlatılması
- Model, submodel ve data object kataloğu
- Paket, prosedür, değişken, sequence, mapping, reusable mapping ve load plan tanımları
- Grid-first mapping editörü, sürümleme, Scenario derleme ve data binding
- Değişmez yayın, onay, kullanıcı ve proje üyeliği yönetimi
- Checksum doğrulamalı JSON proje export'u; validate, dry-run ve güvenli import

UI kaynak veya hedef veritabanı parolası istemez; yalnız backend'de çözümlenen
secret reference tanımlarıyla çalışır. CI/CD bu aşamada kapalıdır.
