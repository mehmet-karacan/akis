const copy = {
  en: {
    title: 'Data topology', subtitle: 'Configure database access, schema mappings, and the catalog for this project.',
    connections: 'Connections', schemas: 'Schemas', bindings: 'Bindings', catalog: 'Catalog',
    secretRefs: 'Secret references', versions: 'Connection versions', physical: 'Physical schemas', logical: 'Logical schemas', environments: 'Environments',
    models: 'Models', submodels: 'Submodels', dataObjects: 'Data objects', create: 'Create', creating: 'Creating…', refresh: 'Refresh',
    name: 'Name', code: 'Code', status: 'Status', description: 'Description', actions: 'Actions', select: 'Select', selected: 'Selected',
    provider: 'Provider', referencePath: 'Reference path', versionReference: 'Version reference', databaseType: 'Database type',
    addSecret: 'Add secret reference', addConnection: 'Add connection', addVersion: 'Add version', addPhysical: 'Add physical schema', addLogical: 'Add logical schema', addEnvironment: 'Add environment', addBinding: 'Add binding', addModel: 'Add model', addSubmodel: 'Add submodel', addDataObject: 'Add data object',
    driver: 'Driver reference', host: 'Host', port: 'Port', serviceName: 'Service name', sid: 'SID', tlsMode: 'TLS mode', secret: 'Secret reference', secretRole: 'Secret role',
    connection: 'Connection', version: 'Version', schemaReference: 'Schema reference', logicalSchema: 'Logical schema', physicalSchema: 'Physical schema', environment: 'Environment', risk: 'Risk',
    model: 'Model', parent: 'Parent submodel', submodel: 'Submodel', objectReference: 'Object reference', type: 'Type',
    test: 'Test Oracle connection', testing: 'Testing…', discover: 'Discover Oracle schema', discovering: 'Discovering…', tableFilter: 'Table name (optional)', limit: 'Table limit',
    noItems: 'No records yet.', loading: 'Loading…', loadFailed: 'Could not load data.', tryAgain: 'Try again', saveFailed: 'Could not save the record.',
    chooseConnection: 'Select a connection to manage versions.', chooseModel: 'Select a model to manage its catalog.', chooseVersion: 'Select a version first.',
    chooseDiscovery: 'Choose a connection version and a physical schema for that connection.', testSuccess: 'Connection successful', compatible: 'Oracle 19c compatible', incompatible: 'Not Oracle 19c compatible',
    discoveryResults: 'Discovery results', tables: 'tables', truncated: 'Result limited', emptyDiscovery: 'No tables found.', required: 'Required', optional: 'Optional',
    activeSelection: 'Active selection', details: 'Details', close: 'Close', policyVersion: 'Policy version', all: 'All', noSecretValues: 'Only secret references are stored here. Secret values are never requested or displayed.',
  },
  tr: {
    title: 'Veri topolojisi', subtitle: 'Bu proje için veritabanı erişimini, şema eşlemelerini ve kataloğu yapılandırın.',
    connections: 'Bağlantılar', schemas: 'Şemalar', bindings: 'Bağlamalar', catalog: 'Katalog',
    secretRefs: 'Gizli bilgi referansları', versions: 'Bağlantı sürümleri', physical: 'Fiziksel şemalar', logical: 'Mantıksal şemalar', environments: 'Ortamlar',
    models: 'Modeller', submodels: 'Alt modeller', dataObjects: 'Veri nesneleri', create: 'Oluştur', creating: 'Oluşturuluyor…', refresh: 'Yenile',
    name: 'Ad', code: 'Kod', status: 'Durum', description: 'Açıklama', actions: 'İşlemler', select: 'Seç', selected: 'Seçili',
    provider: 'Sağlayıcı', referencePath: 'Referans yolu', versionReference: 'Sürüm referansı', databaseType: 'Veritabanı türü',
    addSecret: 'Gizli bilgi referansı ekle', addConnection: 'Bağlantı ekle', addVersion: 'Sürüm ekle', addPhysical: 'Fiziksel şema ekle', addLogical: 'Mantıksal şema ekle', addEnvironment: 'Ortam ekle', addBinding: 'Bağlama ekle', addModel: 'Model ekle', addSubmodel: 'Alt model ekle', addDataObject: 'Veri nesnesi ekle',
    driver: 'Sürücü referansı', host: 'Sunucu', port: 'Port', serviceName: 'Servis adı', sid: 'SID', tlsMode: 'TLS modu', secret: 'Gizli bilgi referansı', secretRole: 'Gizli bilgi rolü',
    connection: 'Bağlantı', version: 'Sürüm', schemaReference: 'Şema referansı', logicalSchema: 'Mantıksal şema', physicalSchema: 'Fiziksel şema', environment: 'Ortam', risk: 'Risk',
    model: 'Model', parent: 'Üst alt model', submodel: 'Alt model', objectReference: 'Nesne referansı', type: 'Tür',
    test: 'Oracle bağlantısını test et', testing: 'Test ediliyor…', discover: 'Oracle şemasını keşfet', discovering: 'Keşfediliyor…', tableFilter: 'Tablo adı (isteğe bağlı)', limit: 'Tablo sınırı',
    noItems: 'Henüz kayıt yok.', loading: 'Yükleniyor…', loadFailed: 'Veriler yüklenemedi.', tryAgain: 'Yeniden dene', saveFailed: 'Kayıt oluşturulamadı.',
    chooseConnection: 'Sürümlerini yönetmek için bir bağlantı seçin.', chooseModel: 'Kataloğunu yönetmek için bir model seçin.', chooseVersion: 'Önce bir sürüm seçin.',
    chooseDiscovery: 'Bir bağlantı sürümü ve o bağlantıya ait fiziksel şema seçin.', testSuccess: 'Bağlantı başarılı', compatible: 'Oracle 19c uyumlu', incompatible: 'Oracle 19c uyumlu değil',
    discoveryResults: 'Keşif sonuçları', tables: 'tablo', truncated: 'Sonuç sınırlandı', emptyDiscovery: 'Tablo bulunamadı.', required: 'Zorunlu', optional: 'İsteğe bağlı',
    activeSelection: 'Etkin seçim', details: 'Ayrıntılar', close: 'Kapat', policyVersion: 'Politika sürümü', all: 'Tümü', noSecretValues: 'Burada yalnızca gizli bilgi referansları tutulur. Gizli değerler hiçbir zaman istenmez veya gösterilmez.',
  },
} as const

export type CopyKey = keyof typeof copy.en

export function getTopologyCopy(language: string) {
  return language.startsWith('tr') ? copy.tr : copy.en
}
