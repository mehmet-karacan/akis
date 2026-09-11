import { useTranslation } from 'react-i18next'

const en = {
  runs: 'Runs', runsHelp: 'Execution requests created from active, immutable publications.', startRun: 'Start Run', starting: 'Starting…',
  publication: 'Publication', choosePublication: 'Choose an executable active publication', noActivePublication: 'No executable active publication is available. Publish a supported mapping or procedure before starting a run.',
  attempt: 'Attempt', startType: 'Start type', startInitial: 'Initial', status: 'Status', createdAt: 'Created', startedAt: 'Started', finishedAt: 'Finished', actions: 'Actions',
  viewDetails: 'View Details', emptyRuns: 'No runs have been requested for this project.', loading: 'Loading…', retry: 'Try Again', requestFailed: 'The request could not be completed.',
  close: 'Close', runDetail: 'Run detail', runContext: 'Execution context', events: 'Events', emptyEvents: 'No events have been recorded for this run.',
  refresh: 'Refresh', backToRuns: 'Back to Runs', cancelRun: 'Cancel Run', cancelling: 'Cancelling…', confirmCancel: 'Cancel queued run?', confirmCancelHelp: 'Only a queued run can be cancelled safely. This action records a cancellation event.',
  keepRun: 'Keep Run', confirm: 'Cancel Run', jobRequestUuid: 'Job request UUID', runUuid: 'Run UUID', publicationUuid: 'Publication UUID', releaseHash: 'Release hash', planHash: 'Plan hash', cancellationRequestedAt: 'Cancellation requested',
  eventNumber: 'Event', eventData: 'Event data', noEventData: 'No additional event data.', executionDisabledTitle: 'Execution requests are disabled',
  executionDisabledBody: 'This deployment is not accepting manual start or cancel requests. Existing runs and recorded events remain available to inspect.',
  executionWorkerUnavailableTitle: 'Execution worker is unavailable', executionWorkerUnavailableBody: 'New runs stay locked until a compatible execution worker is online. Existing run evidence remains available to inspect.',
  status_BEKLIYOR: 'Queued', status_HAZIRLANIYOR: 'Preparing', status_CALISIYOR: 'Running', status_YAYINLANIYOR: 'Publishing',
  status_IPTAL_ISTENDI: 'Cancellation requested', status_SONUC_BELIRSIZ: 'Outcome uncertain', status_MUTABAKAT: 'Reconciling',
  status_YENIDEN_DENENEBILIR: 'Retryable', status_MUDAHALE_GEREKLI: 'Needs intervention', status_BASARILI: 'Succeeded', status_BASARISIZ: 'Failed', status_IPTAL: 'Cancelled',
  idempotencyPrepared: 'A unique request key has been prepared for this start attempt and will be reused if the request must be retried.',
  unnamedObject: 'Unavailable object', runTree: 'Runs by object', runCount: '{{count}} runs', searchRuns: 'Search runs', searchRunsPlaceholder: 'Search by object name or code', noMatchingRuns: 'No objects match this search.', loadMore: 'Load More',
  capabilityUnavailable: 'Run capabilities could not be loaded. Starting a run remains locked until the server confirms availability.',
  runAttempt: 'Attempt #{{number}}', technicalDetails: 'Technical identifiers', unavailableActions: 'Unavailable actions',
  retryUnsupported: 'Retry and resume are unavailable because this runtime does not yet provide a safe checkpoint protocol.',
  steps: 'Steps', emptySteps: 'No typed step evidence is available for this run.', stepDetail: 'Step detail', type: 'Type', connectionRole: 'Connection role', risk: 'Risk', rowCount: 'Rows', byteCount: 'Bytes', errorCode: 'Error code',
  status_HATA_DEVAM: 'Failed, continued', status_ATLANDI: 'Skipped', status_KAYDEDILMEDI: 'Not recorded',
} as const

const tr: Record<keyof typeof en, string> = {
  runs: 'Çalıştırmalar', runsHelp: 'Aktif ve değişmez yayınlardan oluşturulan çalıştırma talepleri.', startRun: 'Çalıştırma Başlat', starting: 'Başlatılıyor…',
  publication: 'Yayın', choosePublication: 'Çalıştırılabilir etkin bir yayın seçin', noActivePublication: 'Çalıştırılabilir etkin yayın bulunmuyor. Çalıştırmadan önce desteklenen bir eşleme veya prosedür yayınlayın.',
  attempt: 'Deneme', startType: 'Başlatma türü', startInitial: 'İlk çalıştırma', status: 'Durum', createdAt: 'Oluşturulma', startedAt: 'Başlangıç', finishedAt: 'Bitiş', actions: 'İşlemler',
  viewDetails: 'Detayları Aç', emptyRuns: 'Bu proje için henüz çalıştırma talebi yok.', loading: 'Yükleniyor…', retry: 'Tekrar Dene', requestFailed: 'İstek tamamlanamadı.',
  close: 'Kapat', runDetail: 'Çalıştırma detayı', runContext: 'Çalıştırma bağlamı', events: 'Olaylar', emptyEvents: 'Bu çalıştırma için henüz olay kaydedilmedi.',
  refresh: 'Yenile', backToRuns: 'Çalıştırmalara Dön', cancelRun: 'Çalıştırmayı İptal Et', cancelling: 'İptal ediliyor…', confirmCancel: 'Bekleyen çalıştırma iptal edilsin mi?', confirmCancelHelp: 'Yalnız bekleyen çalıştırma güvenle iptal edilebilir. Bu işlem bir iptal olayı kaydeder.',
  keepRun: 'Çalıştırmayı Koru', confirm: 'Çalıştırmayı İptal Et', jobRequestUuid: 'İş talebi UUID', runUuid: 'Çalıştırma UUID', publicationUuid: 'Yayın UUID', releaseHash: 'Sürüm özeti', planHash: 'Plan özeti', cancellationRequestedAt: 'İptal talebi',
  eventNumber: 'Olay', eventData: 'Olay verisi', noEventData: 'Ek olay verisi yok.', executionDisabledTitle: 'Çalıştırma talepleri devre dışı',
  executionDisabledBody: 'Bu dağıtım manuel başlatma veya iptal taleplerini kabul etmiyor. Mevcut çalıştırmalar ve kaydedilmiş olaylar incelenmeye devam edilebilir.',
  executionWorkerUnavailableTitle: 'Çalıştırma worker’ı kullanılamıyor', executionWorkerUnavailableBody: 'Uyumlu bir çalıştırma worker’ı çevrimiçi olana kadar yeni çalıştırmalar kilitli kalır. Mevcut çalışma kanıtları incelenmeye devam edilebilir.',
  status_BEKLIYOR: 'Bekliyor', status_HAZIRLANIYOR: 'Hazırlanıyor', status_CALISIYOR: 'Çalışıyor', status_YAYINLANIYOR: 'Yayınlanıyor',
  status_IPTAL_ISTENDI: 'İptal istendi', status_SONUC_BELIRSIZ: 'Sonuç belirsiz', status_MUTABAKAT: 'Mutabakat yapılıyor',
  status_YENIDEN_DENENEBILIR: 'Yeniden denenebilir', status_MUDAHALE_GEREKLI: 'Müdahale gerekli', status_BASARILI: 'Başarılı', status_BASARISIZ: 'Başarısız', status_IPTAL: 'İptal',
  idempotencyPrepared: 'Bu başlatma denemesi için benzersiz bir istek anahtarı hazırlandı; isteğin yeniden denenmesi gerekirse aynı anahtar kullanılacak.',
  unnamedObject: 'Kullanılamayan nesne', runTree: 'Nesneye göre çalıştırmalar', runCount: '{{count}} çalıştırma', searchRuns: 'Çalıştırmalarda ara', searchRunsPlaceholder: 'Nesne adı veya kodu ile ara', noMatchingRuns: 'Bu aramayla eşleşen nesne yok.', loadMore: 'Daha Fazla Yükle',
  capabilityUnavailable: 'Çalıştırma yetenekleri alınamadı. Sunucu uygunluğu doğrulanana kadar çalıştırma başlatma kilitli kalır.',
  runAttempt: 'Deneme #{{number}}', technicalDetails: 'Teknik tanımlayıcılar', unavailableActions: 'Kullanılamayan işlemler',
  retryUnsupported: 'Bu çalışma zamanı henüz güvenli bir kontrol noktası protokolü sunmadığı için yeniden deneme ve devam ettirme kullanılamıyor.',
  steps: 'Adımlar', emptySteps: 'Bu çalıştırma için türlendirilmiş adım kanıtı bulunmuyor.', stepDetail: 'Adım detayı', type: 'Tür', connectionRole: 'Bağlantı rolü', risk: 'Risk', rowCount: 'Satır', byteCount: 'Bayt', errorCode: 'Hata kodu',
  status_HATA_DEVAM: 'Hata, devam edildi', status_ATLANDI: 'Atlandı', status_KAYDEDILMEDI: 'Kaydedilmedi',
}

export type ExecutionMessageKey = keyof typeof en

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
