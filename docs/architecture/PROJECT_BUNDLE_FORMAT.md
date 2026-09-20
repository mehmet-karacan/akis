# Akış portable proje bundle formatı v2

## Kapsam

V2 proje bundle, bir projenin taşınabilir tasarım metadatasıdır. Backend
sözleşmesi `ProjectBundleModels.ProjectBundle`, normatif JSON şeması ise
[`project-bundle-v2.schema.json`](../schemas/project-bundle-v2.schema.json)
dosyasıdır.

V2 proje metadatasını, klasör hiyerarşisini, proje kapsamlı tanımları, taslak ve
değişmez tanım sürümlerini; ayrıca bağlantı/sürüm, fiziksel-mantıksal şema,
ortam/eşleme, model/alt model ve veri nesnesi tasarımını taşır. Parola veya secret
değeri, bağlantı test kanıtı, keşif görüntüsü, Scenario, yayın, zamanlama,
çalıştırma/audit, kullanıcı ve yetki kayıtları taşınmaz.

## JSON şekli

Üst seviye kayıt birebir şöyledir:

```json
{
  "format": "akis.project-bundle",
  "formatVersion": 2,
  "schemaVersion": 2,
  "checksum": "cc3bc34c27d254e8efedb77208a43eb4d161d49ced7190ecdf7b89176a16b335",
  "exportedAt": "2026-09-11T10:30:00Z",
  "project": {
    "code": "SALES_ETL",
    "status": "AKTIF",
    "name": "Sales ETL",
    "description": null
  },
  "folders": [
    {
      "path": "DESIGN",
      "parentPath": null,
      "code": "DESIGN",
      "status": "AKTIF",
      "name": "Design",
      "description": null
    }
  ],
  "definitions": [
    {
      "type": "SEQUENCE",
      "code": "ORDER_SEQUENCE",
      "folderPath": null,
      "status": "AKTIF",
      "name": "Order sequence",
      "description": null,
      "draft": {
        "schemaVersion": 1,
        "content": {
          "implementation": "REPOSITORY",
          "start": 1,
          "increment": 1,
          "cycle": false
        }
      },
      "versions": [
        {
          "versionNumber": 1,
          "schemaVersion": 1,
          "contentHash": "80fd93c5fb7c767005417f832354a2c3565980f1b53a2ca66b283cb9bb777cf0",
          "content": {
            "implementation": "REPOSITORY",
            "start": 1,
            "increment": 1,
            "cycle": false
          },
          "description": "Initial",
          "createdAt": "2026-09-11T10:00:00Z"
        }
      ]
    }
  ],
  "topology": {
    "sanitized": true,
    "definitions": {}
  }
}
```

Örnekteki hash değerleri şekli göstermek içindir; gerçek dosyada backend
tarafından hesaplanan değerler kullanılmalıdır.

`format`, `formatVersion` ve `schemaVersion` için kabul edilen tek v2 değerleri
sırasıyla `akis.project-bundle`, `2` ve `2`'dir. Daha yeni/eski değerler
fail-closed reddedilir. Backend hata kodları `UNSUPPORTED_FORMAT`,
`UNSUPPORTED_FORMAT_VERSION` ve `UNSUPPORTED_SCHEMA_VERSION` değerleridir.

JSON Schema, domain `content` nesnesi dışında bütün kayıt katmanlarında
`additionalProperties: false` kullanır. `content`, tanım türü ve kendi
`schemaVersion` değeri tarafından yönetilen serbest domain JSON nesnesidir.

## Kayıtlar

### ProjectEntry

`project` alanları `code`, `status`, `name`, `description` şeklindedir.
`status` yalnız `AKTIF` veya `ARSIV` olabilir. Database id, UUID, audit alanları
ve optimistic-lock version bundle'a girmez.

### FolderEntry

Her klasör `path`, nullable `parentPath`, `code`, `status`, `name` ve
nullable `description` taşır. Kararlı referans UUID değil `path` değeridir.

- Kök klasör: `path == code`, `parentPath == null`.
- Alt klasör: `path == parentPath + "/" + code`.
- `path` bundle içinde benzersizdir ve `parentPath` aynı bundle'daki bir klasörü
  göstermelidir.
- En fazla 10.000 klasör ve 100 path segmenti kabul edilir.
- `status`: `AKTIF` veya `ARSIV`.

Exporter klasörleri `path` artan sırasıyla yazar. Importer yazarken parent'ları
önce oluşturmak için path derinliği, ardından path sırasını kullanır.

### DefinitionEntry

Tanımın kararlı kimliği `(type, code)` çiftidir ve bundle içinde benzersizdir.
Alanları `type`, `code`, nullable `folderPath`, `status`, `name`, nullable
`description`, nullable `draft` ve `versions` şeklindedir.

Tanım türleri `MAPPING`, `REUSABLE_MAPPING`, `PACKAGE`, `PROCEDURE`, `VARIABLE`,
`SEQUENCE`, `KNOWLEDGE_MODULE` ve `LOAD_PLAN` değerleridir.

`MAPPING`, `REUSABLE_MAPPING`, `PACKAGE` ve `PROCEDURE` için `folderPath`
zorunludur. Verildiğinde path aynı bundle'daki bir klasörü göstermelidir. Tanım
status değeri `TASLAK`, `AKTIF` veya `ARSIV` olabilir. En fazla 20.000 tanım
kabul edilir. Exporter tanımları önce type adı, sonra code ile artan sıralar.

### DraftEntry

Taslak yoksa `draft: null` kullanılır. Varsa kayıt yalnız pozitif
`schemaVersion` ve JSON object `content` taşır. Kaynak optimistic-lock version
taşınmaz ve hedefte yeniden kullanılmaz. Draft içerik şekli, JSON derinlik/node
sınırları ve secret taramasından geçer; immutable sürümlere uygulanan semantic
`DefinitionContentValidator` kontrolü taslağa uygulanmaz.

### VersionEntry

Immutable sürüm alanları `versionNumber`, `schemaVersion`, `contentHash`,
`content`, nullable `description` ve `createdAt` şeklindedir.

- `versionNumber` ve `schemaVersion` pozitiftir.
- Version numaraları tanım içinde benzersiz ve 1'den başlayan kesintisiz bir
  dizidir.
- Tanım başına ve bundle toplamında en fazla 100.000 immutable sürüm kabul
  edilir.
- `content` JSON object olmalı ve tipin `DefinitionContentValidator`
  kurallarından geçmelidir.
- `contentHash`, canonical content'in SHA-256 hash'idir ve küçük harfli 64 hex
  karakterdir.
- `createdAt` zorunludur ve import sırasında değiştirilmez.
- Exporter sürümleri `versionNumber` artan sırasıyla yazar.
- Import mevcut sürümü update etmez; v2 her zaman yeni proje oluşturduğu için
  sürümleri verilen numara ve hash ile yeni proje içine ekler.

## Taşınabilir topology ve secret sınırı

V2 topology kaydı `sanitized: true` ve `definitions` nesnesini taşır. Bağlantı
sürümleri import sırasında daima `TASLAK` oluşturulur; başka sistemden gelen test
ve etkinleştirme kanıtı güvenilir sayılmaz. Kimlik bağı yalnız kullanıcı adı ile
`ENV`/`VAULT` referansını taşır, secret değeri hiçbir zaman pakete girmez.
`sanitized: false`, backend tarafından `UNSANITIZED_TOPOLOGY` olarak reddedilir.

Secret değerleri draft veya immutable version `content` içine gömülemez.
Backend `SecretValueSanitizer`, alan adını locale bağımsız küçük harfe çevirip
`_` ve `-` karakterlerini kaldırdıktan sonra password/passwd, secret/secretValue,
token/accessToken/refreshToken, apiKey, privateKey, clientSecret,
credential/credentials/credentialValue, `pwd` ve authorization adlarını ya da hassas
suffix'leri reddeder. Ayrıca scalar metinlerde private-key PEM başlangıcı,
`IDENTIFIED BY` credential clause'u, SQL*Plus `CONNECT user/password@db`, URI
`scheme://user:password@host` biçimleri ve `password=`, `pwd=`, `token=` gibi
güçlü credential kalıpları reddedilir. Bulgu `SECRET_VALUE_FORBIDDEN` üretir. Export, saklı
içerikte bulgu varsa bundle üretmez; validate/import aynı kontrolü tekrarlar.

## Canonical JSON ve hash

Backend canonicalization algoritması `ProjectBundleService.canonical` ile
aynıdır:

1. JSON object property adlarını Java `String` doğal sırasıyla artan sıraya koy.
2. Bu işlemi bütün nested object'lere recursive uygula.
3. Array sırasını değiştirme.
4. Scalar `JsonNode` değerini değiştirme.
5. Jackson `JsonNode.toString()` ile whitespace'siz JSON üret, UTF-8 encode et
   ve SHA-256 hesapla; sonucu küçük harfli hex yaz.

Her `VersionEntry.contentHash` yalnız `content` nesnesinin bu canonical
gösteriminden hesaplanır.

Bundle `checksum` hesabı şöyledir:

1. `ProjectBundle` kaydını Jackson ile JSON ağacına dönüştür.
2. Üst seviyeden `checksum` ve `exportedAt` alanlarını çıkar.
3. Kalan tüm bundle ağacını yukarıdaki algoritmayla canonical hale getir.
4. Canonical JSON'un UTF-8 byte'larının SHA-256 değerini hesapla.

Dolayısıyla export zamanı checksum'ı değiştirmez; project, folders,
definitions, topology, format veya version alanındaki değişiklik değiştirir.
Array sırası checksum'a dahildir. Exporter klasör, tanım ve sürüm listelerini
yukarıda belirtilen deterministik sırada üretir. El yazımı bir bundle farklı
array sırasıyla da validate olabilir; checksum o mevcut sıraya göre
hesaplanmalıdır.

## HTTP kapısı

### Export

```http
GET /api/v1/projects/{projectUuid}/bundle/export
```

`PROJECT_READ` gerekir. Backend REPEATABLE READ, read-only transaction içinde
project/folder/definition/draft/version snapshot'ını alır, stable folder path'leri
kurar, secret taramasını ve document validation'ı çalıştırır, checksum'ı üretir.
Yanıt dosya adı `{PROJECT_CODE}-bundle-v2.json` olur.

### Validate

```http
POST /api/v1/project-bundles/validate
```

`PROJECT_CREATE` sistem yetkisi gerekir. Validation servis katmanında DB'ye
erişmez; proje kodunun hedefte var olup olmadığını kontrol etmez. Yanıt:

```json
{
  "valid": true,
  "counts": {
    "folders": 1,
    "definitions": 1,
    "drafts": 1,
    "versions": 1
  },
  "issues": []
}
```

Issue kaydı `path`, `code`, `message` alanlarını taşır. Validate; format/schema,
stable path/ref, limit, secret, checksum, content hash ve immutable version
semantic kontrollerini raporlar. Hedef proje conflict'i bu endpoint'in işi
değildir.

### Import ve dry-run

Desteklenen çağrılar:

```http
POST /api/v1/project-bundles/import?dryRun=true&conflict=FAIL
POST /api/v1/project-bundles/import?dryRun=true&conflict=RENAME
POST /api/v1/project-bundles/import?dryRun=false&conflict=FAIL
POST /api/v1/project-bundles/import?dryRun=false&conflict=RENAME
```

`PROJECT_CREATE` sistem yetkisi gerekir. Query parametrelerinin backend
varsayılanı `dryRun=false`, `conflict=FAIL` değeridir.

Dry-run bütün document validation'ı ve hedef proje kodu conflict çözümünü
çalıştırır, ancak write yapmaz. Sonuç kaydı `imported`, `dryRun`, `projectCode`,
nullable `projectUuid` ve `counts` alanlarını taşır. Dry-run sonucunda
`imported=false`, `dryRun=true`, `projectUuid=null` olur.

Gerçek import yeni proje, klasör, tanım, draft ve immutable version kayıtlarını
tek transaction içinde oluşturur. Proje kodu seçimi PostgreSQL transaction-level
advisory lock ile uygulama instance'ları ve normal proje oluşturma akışı arasında
serileştirilir. V1 var olan
projeye merge etmez.

## Conflict policy

| Değer | Gerçek v2 davranışı |
|---|---|
| `FAIL` | Hedefte aynı project code varsa HTTP 409 `BUNDLE_CONFLICT`; write yapılmaz. Kod boşsa kaynak project code ile yeni proje oluşturulur. |
| `RENAME` | Hedefte code varsa sırayla `{CODE}_IMPORT_1`, `{CODE}_IMPORT_2`, ... denenir. 100 karakter sınırı için kaynak prefix'i suffix'e yer açacak şekilde kırpılır. İlk boş code dry-run ve import sonucudur. |
| `SKIP` | Enum değeri ayrılmıştır ama v2'de uygulanmaz. HTTP 422 `UNSUPPORTED_CONFLICT_POLICY` ile fail-closed reddedilir. |
| `NEW_VERSION` | Enum değeri ayrılmıştır ama v2'de uygulanmaz. HTTP 422 `UNSUPPORTED_CONFLICT_POLICY` ile fail-closed reddedilir. |

`RENAME` yalnız yeni hedef projenin code alanını değiştirir. Folder path,
definition code veya immutable version numaralarını yeniden adlandırmaz. Çünkü
v2 import var olan proje içeriğiyle merge etmez.

## Uygulanan validation sınırları

- En fazla 10.000 klasör, 20.000 tanım, toplam 100.000 sürüm.
- Her Definition `content` için en fazla 100 JSON derinliği ve 100.000 node;
  bundle genelinde en fazla 1.000.000 content node.
- Validate/import HTTP gövdesi en fazla 10 MiB olabilir ve akış okunurken
  sınırlandırılır.
- Kod: `^[A-Z][A-Z0-9_]{0,99}$`.
- Ad: boşluk dışı en az bir karakter, en fazla 200 karakter.
- Folder path türetme, parent varlığı ve path benzersizliği.
- Definition `(type, code)` benzersizliği ve folder zorunluluğu.
- Version numarasının pozitif, benzersiz ve 1'den başlayan kesintisiz olması.
- Bundle checksum ve immutable content hash doğrulaması.
- Immutable version için domain semantic validation.
- Draft/version content için recursive secret ve JSON limit kontrolü.

JSON Schema cross-record benzersizlik, parent varlığı, kesintisiz sürüm dizisi,
checksum, content hash ve tipe özel domain semantiğini tek başına doğrulamaz;
bunlar `ProjectBundleService.validate` tarafından uygulanır.
