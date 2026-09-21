import { useTranslation } from 'react-i18next'

const en = {
  transactionState: 'Transaction Evidence',
  runs: 'Run History', runsHelp: 'Execution requests created from active, immutable publications.', startRun: 'Start Run', starting: 'Starting…',
  publication: 'Publication', choosePublication: 'Choose an executable active publication', noActivePublication: 'No executable active publication is available. Publish a supported mapping or procedure before starting a run.',
  attempt: 'Attempt', startType: 'Start type', startInitial: 'Initial', status: 'Status', createdAt: 'Created', startedAt: 'Started', finishedAt: 'Finished', actions: 'Actions',
  viewDetails: 'View Details', emptyRuns: 'No runs have been requested for this project.', loading: 'Loading…', retry: 'Try Again', requestFailed: 'The request could not be completed.',
  close: 'Close', runDetail: 'Run detail', runContext: 'Execution context', events: 'Events', emptyEvents: 'No events have been recorded for this run.',
  refresh: 'Refresh', backToRuns: 'Back to Runs', cancelRun: 'Cancel Run', cancelling: 'Cancelling…', confirmCancel: 'Cancel queued run?', confirmCancelHelp: 'Only a queued run can be cancelled safely. This action records a cancellation event.',
  keepRun: 'Keep Run', confirm: 'Cancel Run', jobRequestUuid: 'Job request UUID', runUuid: 'Run UUID', publicationUuid: 'Publication UUID', releaseHash: 'Release hash', planHash: 'Plan hash', cancellationRequestedAt: 'Cancellation requested',
  eventNumber: 'Event', eventData: 'Event data', noEventData: 'No additional event data.', executionDisabledTitle: 'Execution requests are disabled',
  executionDisabledBody: 'This deployment is not accepting manual start or cancel requests. Existing runs and recorded events remain available to inspect.',
  executionWorkerUnavailableTitle: 'Execution worker is unavailable', executionWorkerUnavailableBody: 'New runs stay locked until a compatible execution worker is online. Existing run evidence remains available to inspect.',
  status_BEKLIYOR: 'Queued', status_SAHIPLENILDI: 'Claimed', status_HAZIRLANIYOR: 'Preparing', status_CALISIYOR: 'Running', status_YAYINLANIYOR: 'Publishing',
  status_IPTAL_ISTENDI: 'Cancellation requested', status_SONUC_BELIRSIZ: 'Outcome uncertain', status_MUTABAKAT: 'Reconciling',
  status_YENIDEN_DENENEBILIR: 'Retryable', status_MUDAHALE_GEREKLI: 'Needs intervention', status_BASARILI: 'Succeeded', status_BASARISIZ: 'Failed', status_IPTAL: 'Cancelled',
  idempotencyPrepared: 'A unique request key has been prepared for this start attempt and will be reused if the request must be retried.',
  unnamedObject: 'Unavailable object', runTree: 'Runs by object', runCount: '{{count}} runs', searchRuns: 'Search runs', searchRunsPlaceholder: 'Search by object name or code', noMatchingRuns: 'No objects match this search.', loadMore: 'Load More',
  capabilityUnavailable: 'Run capabilities could not be loaded. Starting a run remains locked until the server confirms availability.',
  runAttempt: 'Attempt #{{number}}', technicalDetails: 'Technical identifiers', unavailableActions: 'Unavailable actions',
  retryUnsupported: 'Retry and resume are unavailable because this runtime does not yet provide a safe checkpoint protocol.',
  steps: 'Steps', emptySteps: 'No steps were recorded for this run.', stepDetail: 'Step detail', type: 'Type', connectionRole: 'Connection role', risk: 'Risk', rowCount: 'Rows', byteCount: 'Bytes', errorCode: 'Error code',
  status_HATA_DEVAM: 'Failed, continued', status_ATLANDI: 'Skipped', status_KAYDEDILMEDI: 'Not recorded',
  runsOperationalHelp: 'Monitor runs, investigate failures, and manage only permitted interventions.', runViews: 'Run views',
  view_RECENT: 'Recent runs', view_ACTIVE: 'Active runs', view_FAILED: 'Failed runs', view_HISTORY: 'Run history',
  scope_RECENT: 'Runs created in the last 24 hours', scope_ACTIVE: 'All active runs without a date limit', scope_FAILED: 'Failed runs in the last 24 hours', scope_HISTORY: 'Run history; last 7 days by default',
  allStatuses: 'All statuses', environment: 'Environment', allEnvironments: 'All environments', objectType: 'Object type', allObjectTypes: 'All object types', procedure: 'Procedure', mapping: 'Data flow', package: 'Package',
  applyFilters: 'Search', clearFilters: 'Clear', from: 'From', to: 'To', apply: 'Apply', pauseLive: 'Pause live refresh', resumeLive: 'Resume live refresh', refreshFailed: 'The latest refresh failed; the previous results remain visible.',
  object: 'Object', duration: 'Duration', rows: 'Rows', initiator: 'Initiator', resultCount: '{{count}} results', pageSize: 'Page size', previousPage: 'Previous page', nextPage: 'Next page',
  runnableVersion: 'Runnable version', targetSummary: 'Pinned target summary', confirmProductionRun: 'I confirm this run will use the production environment and the pinned targets shown above.',
  pinnedContextUnavailable: 'Pinned environment and target context could not be loaded; run evidence remains available.', availableInterventions: 'Interventions', rerun: 'Run again', resumeRun: 'Resume from failed step', actionReason: 'Unavailable: {{reason}}',
  evidence: 'Run evidence', tab_SUMMARY: 'Summary', tab_LOGS: 'Logs', tab_EVENTS: 'Events', errorMessageUnavailable: 'A detailed error message was not recorded.', metricScope: 'Metrics below belong to the selected step; unknown values are not shown as zero.', operationalEventLog: 'Operational event log', eventLogScope: 'Structured run events; task stdout is not available in this runtime.', noStepLog: 'No structured event lines are available. The step may not have started.',
  expandStep: 'Expand step', collapseStep: 'Collapse step', executionRoot: 'Execution', selectedRows: 'Rows Selected', insertedRows: 'Rows Inserted', notRecorded: 'Not recorded',
  safeRecovery: 'Safe recovery', recoveryUnavailable: 'Automatic recovery is unavailable', reconciliationRequired: 'Target reconciliation required', reconciliationRequiredHelp: 'The transaction outcome must be proven from target-local evidence before another write can start.', retryFailedUnit: 'Retry failed unit', resumeSafely: 'Resume safely', restartFromBeginning: 'Restart from beginning', recoveryCreated: 'Recovery attempt #{{number}} was created.',
  chunkEvidence: 'Committed transfer chunks', range: 'Key range',
} as const

const tr: Record<keyof typeof en, string> = {
  transactionState: 'İşlem Kanıtı',
  runs: 'Çalıştırma Geçmişi', runsHelp: 'Aktif ve değişmez yayınlardan oluşturulan çalıştırma talepleri.', startRun: 'Çalıştırma Başlat', starting: 'Başlatılıyor…',
  publication: 'Yayın', choosePublication: 'Çalıştırılabilir etkin bir yayın seçin', noActivePublication: 'Çalıştırılabilir etkin yayın bulunmuyor. Çalıştırmadan önce desteklenen bir eşleme veya prosedür yayınlayın.',
  attempt: 'Deneme', startType: 'Başlatma türü', startInitial: 'İlk çalıştırma', status: 'Durum', createdAt: 'Oluşturulma zamanı', startedAt: 'Başlangıç', finishedAt: 'Bitiş', actions: 'İşlemler',
  viewDetails: 'Detayları Aç', emptyRuns: 'Bu proje için henüz çalıştırma talebi yok.', loading: 'Yükleniyor…', retry: 'Tekrar Dene', requestFailed: 'İstek tamamlanamadı.',
  close: 'Kapat', runDetail: 'Çalıştırma detayı', runContext: 'Çalıştırma bağlamı', events: 'Olaylar', emptyEvents: 'Bu çalıştırma için henüz olay kaydedilmedi.',
  refresh: 'Yenile', backToRuns: 'Çalıştırmalara Dön', cancelRun: 'Çalıştırmayı İptal Et', cancelling: 'İptal ediliyor…', confirmCancel: 'Bekleyen çalıştırma iptal edilsin mi?', confirmCancelHelp: 'Yalnız bekleyen çalıştırma güvenle iptal edilebilir. Bu işlem bir iptal olayı kaydeder.',
  keepRun: 'Çalıştırmayı Koru', confirm: 'Çalıştırmayı İptal Et', jobRequestUuid: 'İş talebi UUID', runUuid: 'Çalıştırma UUID', publicationUuid: 'Yayın UUID', releaseHash: 'Sürüm özeti', planHash: 'Plan özeti', cancellationRequestedAt: 'İptal talebi',
  eventNumber: 'Olay', eventData: 'Olay verisi', noEventData: 'Ek olay verisi yok.', executionDisabledTitle: 'Çalıştırma talepleri devre dışı',
  executionDisabledBody: 'Bu dağıtım manuel başlatma veya iptal taleplerini kabul etmiyor. Mevcut çalıştırmalar ve kaydedilmiş olaylar incelenmeye devam edilebilir.',
  executionWorkerUnavailableTitle: 'Çalıştırma worker’ı kullanılamıyor', executionWorkerUnavailableBody: 'Uyumlu bir çalıştırma worker’ı çevrimiçi olana kadar yeni çalıştırmalar kilitli kalır. Mevcut çalışma kanıtları incelenmeye devam edilebilir.',
  status_BEKLIYOR: 'Bekliyor', status_SAHIPLENILDI: 'Sahiplenildi', status_HAZIRLANIYOR: 'Hazırlanıyor', status_CALISIYOR: 'Çalışıyor', status_YAYINLANIYOR: 'Yayınlanıyor',
  status_IPTAL_ISTENDI: 'İptal istendi', status_SONUC_BELIRSIZ: 'Sonuç belirsiz', status_MUTABAKAT: 'Mutabakat yapılıyor',
  status_YENIDEN_DENENEBILIR: 'Yeniden denenebilir', status_MUDAHALE_GEREKLI: 'Müdahale gerekli', status_BASARILI: 'Başarılı', status_BASARISIZ: 'Başarısız', status_IPTAL: 'İptal',
  idempotencyPrepared: 'Bu başlatma denemesi için benzersiz bir istek anahtarı hazırlandı; isteğin yeniden denenmesi gerekirse aynı anahtar kullanılacak.',
  unnamedObject: 'Kullanılamayan nesne', runTree: 'Nesneye göre çalıştırmalar', runCount: '{{count}} çalıştırma', searchRuns: 'Çalıştırmalarda ara', searchRunsPlaceholder: 'Nesne adı veya kodu ile ara', noMatchingRuns: 'Bu aramayla eşleşen nesne yok.', loadMore: 'Daha Fazla Yükle',
  capabilityUnavailable: 'Çalıştırma yetenekleri alınamadı. Sunucu uygunluğu doğrulanana kadar çalıştırma başlatma kilitli kalır.',
  runAttempt: 'Deneme #{{number}}', technicalDetails: 'Teknik tanımlayıcılar', unavailableActions: 'Kullanılamayan işlemler',
  retryUnsupported: 'Bu çalışma zamanı henüz güvenli bir kontrol noktası protokolü sunmadığı için yeniden deneme ve devam ettirme kullanılamıyor.',
  steps: 'Adımlar', emptySteps: 'Bu çalıştırma için adım kaydı bulunmuyor.', stepDetail: 'Adım detayı', type: 'Tür', connectionRole: 'Bağlantı rolü', risk: 'Risk', rowCount: 'Satır', byteCount: 'Bayt', errorCode: 'Hata kodu',
  status_HATA_DEVAM: 'Hata, devam edildi', status_ATLANDI: 'Atlandı', status_KAYDEDILMEDI: 'Kaydedilmedi',
  runsOperationalHelp: 'Çalıştırmaları izleyin, hataları inceleyin ve yalnız izin verilen müdahaleleri yönetin.', runViews: 'Çalıştırma görünümleri',
  view_RECENT: 'Son çalıştırmalar', view_ACTIVE: 'Aktif çalıştırmalar', view_FAILED: 'Başarısız çalıştırmalar', view_HISTORY: 'Çalıştırma geçmişi',
  scope_RECENT: 'Son 24 saatte oluşturulan çalıştırmalar', scope_ACTIVE: 'Tarih sınırı olmadan tüm aktif çalıştırmalar', scope_FAILED: 'Son 24 saatte başarısız olan çalıştırmalar', scope_HISTORY: 'Varsayılan olarak son 7 günlük çalıştırma geçmişi',
  allStatuses: 'Tüm durumlar', environment: 'Ortam', allEnvironments: 'Tüm ortamlar', objectType: 'Nesne türü', allObjectTypes: 'Tüm nesne türleri', procedure: 'Prosedür', mapping: 'Veri akışı', package: 'Paket',
  applyFilters: 'Sorgula', clearFilters: 'Temizle', from: 'Başlangıç', to: 'Bitiş', apply: 'Uygula', pauseLive: 'Canlı yenilemeyi duraklat', resumeLive: 'Canlı yenilemeyi sürdür', refreshFailed: 'Son yenileme başarısız oldu; önceki sonuçlar görünmeye devam ediyor.',
  object: 'Nesne', duration: 'Süre', rows: 'Satır', initiator: 'Başlatan', resultCount: '{{count}} sonuç', pageSize: 'Sayfa boyutu', previousPage: 'Önceki sayfa', nextPage: 'Sonraki sayfa',
  runnableVersion: 'Çalıştırılabilir sürüm', targetSummary: 'Sabitlenmiş hedef özeti', confirmProductionRun: 'Bu çalıştırmanın üretim ortamını ve yukarıda gösterilen sabitlenmiş hedefleri kullanacağını onaylıyorum.',
  pinnedContextUnavailable: 'Sabitlenmiş ortam ve hedef bağlamı yüklenemedi; çalıştırma kanıtları görüntülenmeye devam ediyor.', availableInterventions: 'Müdahaleler', rerun: 'Yeniden çalıştır', resumeRun: 'Başarısız adımdan devam et', actionReason: 'Kullanılamıyor: {{reason}}',
  evidence: 'Çalıştırma kanıtları', tab_SUMMARY: 'Özet', tab_LOGS: 'Loglar', tab_EVENTS: 'Olaylar', errorMessageUnavailable: 'Ayrıntılı hata mesajı kaydedilmemiş.', metricScope: 'Aşağıdaki metrikler seçili adıma aittir; bilinmeyen değerler sıfır gösterilmez.', operationalEventLog: 'Operasyonel olay günlüğü', eventLogScope: 'Yapılandırılmış çalıştırma olaylarıdır; bu runtime görev stdout’u sunmuyor.', noStepLog: 'Yapılandırılmış olay satırı yok. Adım henüz başlamamış olabilir.',
  expandStep: 'Adımı genişlet', collapseStep: 'Adımı daralt', executionRoot: 'Çalıştırma', selectedRows: 'Kaynaktan Seçilen Satır', insertedRows: 'Hedefe Eklenen Satır', notRecorded: 'Kaydedilmedi',
  safeRecovery: 'Güvenli yeniden çalıştırma', recoveryUnavailable: 'Otomatik yeniden çalıştırma kullanılamıyor', reconciliationRequired: 'Hedef mutabakatı gerekli', reconciliationRequiredHelp: 'Yeni bir yazma başlamadan önce işlem sonucu hedefteki yerel kanıttan doğrulanmalıdır.', retryFailedUnit: 'Başarısız birimi yeniden dene', resumeSafely: 'Güvenle devam et', restartFromBeginning: 'Baştan yeniden başlat', recoveryCreated: '{{number}} numaralı kurtarma denemesi oluşturuldu.',
  chunkEvidence: 'Commit edilmiş aktarım parçaları', range: 'Anahtar aralığı',
}

export type ExecutionMessageKey = keyof typeof en

const codeLabels: Record<'en' | 'tr', Record<string, string>> = {
  en: { MANUAL: 'Manual', SCHEDULED: 'Scheduled', API: 'API', PROSEDUR: 'Procedure', MAPPING: 'Data flow', PAKET: 'Package', LOAD_PLAN: 'Load plan', URETIM: 'Production', TEST: 'Test', GELISTIRME: 'Development', SOURCE: 'Source', TARGET: 'Target', SQL: 'SQL', TRUNCATE: 'Truncate', INSERT: 'Insert', GATHER_STATS: 'Gather statistics' },
  tr: { MANUAL: 'Manuel', SCHEDULED: 'Zamanlanmış', API: 'API', PROSEDUR: 'Prosedür', MAPPING: 'Veri akışı', PAKET: 'Paket', LOAD_PLAN: 'Yükleme planı', URETIM: 'Üretim', TEST: 'Test', GELISTIRME: 'Geliştirme', SOURCE: 'Kaynak', TARGET: 'Hedef', SQL: 'SQL', TRUNCATE: 'Tabloyu boşalt', INSERT: 'Veri ekle', GATHER_STATS: 'İstatistikleri topla' },
}

export function executionCodeLabel(code: string | null | undefined, locale: string) {
  const language = locale.startsWith('tr') ? 'tr' : 'en'
  if (!code) return language === 'tr' ? 'Kaydedilmedi' : 'Not recorded'
  if (code === 'COMMITTED') return language === 'tr' ? 'Kalıcılaştırıldı' : 'Committed'
  if (code === 'UNCONFIRMED') return language === 'tr' ? 'İşlem Sonucu Teyit Edilmedi' : 'Transaction Outcome Unconfirmed'
  if (code === 'NOT_APPLICABLE') return language === 'tr' ? 'Salt Okuma' : 'Read Only'
  return codeLabels[language][code] ?? code.toLocaleLowerCase(language === 'tr' ? 'tr-TR' : 'en-US').replaceAll('_', ' ').replace(/(^|\s)\S/g, (value) => value.toLocaleUpperCase(language === 'tr' ? 'tr-TR' : 'en-US'))
}

export function useExecutionI18n() {
  const { i18n } = useTranslation()
  const language = i18n.resolvedLanguage === 'tr' || i18n.language.startsWith('tr') ? 'tr' : 'en'
  const messages = language === 'tr' ? tr : en
  return {
    locale: language === 'tr' ? 'tr-TR' : 'en-US',
    t: (key: ExecutionMessageKey, variables?: Record<string, string | number>) => {
      let value: string = messages[key] ?? en[key]
      for (const [name, replacement] of Object.entries(variables ?? {})) value = value.replace(`{{${name}}}`, String(replacement))
      return value
    },
  }
}
