import { useTranslation } from 'react-i18next'

const en = {
  runs: 'Runs', runsHelp: 'Execution requests created from active, immutable publications.', startRun: 'Start run', starting: 'Starting…',
  publication: 'Publication', choosePublication: 'Choose an active publication', noActivePublication: 'No active publication is available. Approve a publication before starting a run.',
  attempt: 'Attempt', startType: 'Start type', status: 'Status', createdAt: 'Created', startedAt: 'Started', finishedAt: 'Finished', actions: 'Actions',
  viewDetails: 'View details', emptyRuns: 'No runs have been requested for this project.', loading: 'Loading…', retry: 'Try again', requestFailed: 'The request could not be completed.',
  close: 'Close', runDetail: 'Run detail', runContext: 'Execution context', events: 'Events', emptyEvents: 'No events have been recorded for this run.',
  refresh: 'Refresh', backToRuns: 'Back to runs', cancelRun: 'Cancel run', cancelling: 'Cancelling…', confirmCancel: 'Cancel queued run?', confirmCancelHelp: 'Only a queued run can be cancelled safely. This action records a cancellation event.',
  keepRun: 'Keep run', confirm: 'Cancel run', jobRequestUuid: 'Job request UUID', runUuid: 'Run UUID', publicationUuid: 'Publication UUID', releaseHash: 'Release hash', planHash: 'Plan hash', cancellationRequestedAt: 'Cancellation requested',
  eventNumber: 'Event', eventData: 'Event data', noEventData: 'No additional event data.', executionDisabledTitle: 'Execution requests are disabled',
  executionDisabledBody: 'This deployment is not accepting manual start or cancel requests. Existing runs and recorded events remain available to inspect.',
  status_BEKLIYOR: 'Queued', status_HAZIRLANIYOR: 'Preparing', status_CALISIYOR: 'Running', status_YAYINLANIYOR: 'Publishing',
  status_IPTAL_ISTENDI: 'Cancellation requested', status_SONUC_BELIRSIZ: 'Outcome uncertain', status_MUTABAKAT: 'Reconciling',
  status_YENIDEN_DENENEBILIR: 'Retryable', status_MUDAHALE_GEREKLI: 'Needs intervention', status_BASARILI: 'Succeeded', status_BASARISIZ: 'Failed', status_IPTAL: 'Cancelled',
  idempotencyPrepared: 'A unique request key has been prepared for this start attempt and will be reused if the request must be retried.',
} as const

const tr: Record<keyof typeof en, string> = {
  runs: 'Çalıştırmalar', runsHelp: 'Aktif ve değişmez yayınlardan oluşturulan çalıştırma talepleri.', startRun: 'Çalıştırma başlat', starting: 'Başlatılıyor…',
  publication: 'Yayın', choosePublication: 'Aktif bir yayın seçin', noActivePublication: 'Aktif yayın bulunmuyor. Çalıştırma başlatmadan önce bir yayını onaylayın.',
  attempt: 'Deneme', startType: 'Başlatma türü', status: 'Durum', createdAt: 'Oluşturulma', startedAt: 'Başlangıç', finishedAt: 'Bitiş', actions: 'İşlemler',
  viewDetails: 'Detayları aç', emptyRuns: 'Bu proje için henüz çalıştırma talebi yok.', loading: 'Yükleniyor…', retry: 'Tekrar dene', requestFailed: 'İstek tamamlanamadı.',
  close: 'Kapat', runDetail: 'Çalıştırma detayı', runContext: 'Çalıştırma bağlamı', events: 'Olaylar', emptyEvents: 'Bu çalıştırma için henüz olay kaydedilmedi.',
  refresh: 'Yenile', backToRuns: 'Çalıştırmalara dön', cancelRun: 'Çalıştırmayı iptal et', cancelling: 'İptal ediliyor…', confirmCancel: 'Bekleyen çalıştırma iptal edilsin mi?', confirmCancelHelp: 'Yalnız bekleyen çalıştırma güvenle iptal edilebilir. Bu işlem bir iptal olayı kaydeder.',
  keepRun: 'Çalıştırmayı koru', confirm: 'Çalıştırmayı iptal et', jobRequestUuid: 'İş talebi UUID', runUuid: 'Çalıştırma UUID', publicationUuid: 'Yayın UUID', releaseHash: 'Sürüm özeti', planHash: 'Plan özeti', cancellationRequestedAt: 'İptal talebi',
  eventNumber: 'Olay', eventData: 'Olay verisi', noEventData: 'Ek olay verisi yok.', executionDisabledTitle: 'Çalıştırma talepleri devre dışı',
  executionDisabledBody: 'Bu dağıtım manuel başlatma veya iptal taleplerini kabul etmiyor. Mevcut çalıştırmalar ve kaydedilmiş olaylar incelenmeye devam edilebilir.',
  status_BEKLIYOR: 'Bekliyor', status_HAZIRLANIYOR: 'Hazırlanıyor', status_CALISIYOR: 'Çalışıyor', status_YAYINLANIYOR: 'Yayınlanıyor',
  status_IPTAL_ISTENDI: 'İptal istendi', status_SONUC_BELIRSIZ: 'Sonuç belirsiz', status_MUTABAKAT: 'Mutabakat yapılıyor',
  status_YENIDEN_DENENEBILIR: 'Yeniden denenebilir', status_MUDAHALE_GEREKLI: 'Müdahale gerekli', status_BASARILI: 'Başarılı', status_BASARISIZ: 'Başarısız', status_IPTAL: 'İptal',
  idempotencyPrepared: 'Bu başlatma denemesi için benzersiz bir istek anahtarı hazırlandı; isteğin yeniden denenmesi gerekirse aynı anahtar kullanılacak.',
}

export type ExecutionMessageKey = keyof typeof en

export function useExecutionI18n() {
  const { i18n } = useTranslation()
  const language = i18n.resolvedLanguage === 'tr' || i18n.language.startsWith('tr') ? 'tr' : 'en'
  const messages = language === 'tr' ? tr : en
  return {
    locale: language === 'tr' ? 'tr-TR' : 'en-US',
    t: (key: ExecutionMessageKey) => messages[key] ?? en[key],
  }
}
