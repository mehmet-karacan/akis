import { Alert, Button, Progress, Space, Typography } from 'antd'
import { Download, RefreshCw, XCircle } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { exportApi } from './exportApi'
import type { ExportJob, ExportStatus } from './exportTypes'

const TERMINAL: ExportStatus[] = ['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED']

export interface ExportJobStatusProps {
  projectUuid?: string | null
  globalScope?: boolean
  exportUuid: string
  onDone?: () => void
}

export function ExportJobStatus({ projectUuid, globalScope = false, exportUuid, onDone }: ExportJobStatusProps) {
  const [job, setJob] = useState<ExportJob | null>(null)
  const [error, setError] = useState<string>('')
  const [downloading, setDownloading] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const onDoneRef = useRef(onDone)
  const doneJob = useRef('')
  const generation = useRef(0)
  const cancelPending = useRef(false)

  useEffect(() => {
    onDoneRef.current = onDone
  }, [onDone])

  useEffect(() => {
    let cancelled = false
    let timer: number | undefined
    const current = ++generation.current
    async function poll() {
      try {
        const latest = globalScope
          ? await exportApi.getGlobalStatus(exportUuid)
          : await exportApi.getStatus(projectUuid!, exportUuid)
        if (cancelled) return
        setJob(latest)
        setError('')
        if (TERMINAL.includes(latest.status)) {
          const key = `${globalScope ? 'global' : projectUuid}:${exportUuid}`
          if (doneJob.current !== key) {
            doneJob.current = key
            onDoneRef.current?.()
          }
          return
        }
        timer = window.setTimeout(poll, 2000)
      } catch (reason) {
        if (cancelled) return
        setError(reason instanceof Error ? reason.message : (tr ? 'Durum alınamadı.' : 'Status check failed.'))
      }
    }
    void poll()
    return () => { cancelled = true; window.clearTimeout(timer); if (generation.current === current) generation.current += 1 }
  }, [projectUuid, globalScope, exportUuid, attempt, tr])

  function download() {
    if (!job || job.status !== 'COMPLETED' || downloading) return
    setDownloading(true)
    try {
      // Let the browser stream the attachment directly. Fetch+blob would load
      // the complete export into renderer memory before saving it.
      const anchor = document.createElement('a')
      anchor.href = globalScope
        ? exportApi.globalDownloadUrl(exportUuid)
        : exportApi.downloadUrl(projectUuid!, exportUuid)
      anchor.download = ''
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : (tr ? 'İndirme başlatılamadı.' : 'Download failed.'))
    } finally {
      setDownloading(false)
    }
  }

  async function cancel() {
    if (cancelPending.current) return
    cancelPending.current = true
    setCancelling(true)
    const current = generation.current
    try {
      if (globalScope) await exportApi.cancelGlobal(exportUuid)
      else await exportApi.cancel(projectUuid!, exportUuid)
      if (generation.current === current) setAttempt(value => value + 1)
    } catch (reason) {
      if (generation.current === current) setError(reason instanceof Error ? reason.message : (tr ? 'İptal edilemedi.' : 'Cancel failed.'))
    } finally {
      cancelPending.current = false
      setCancelling(false)
    }
  }

  if (!job && !error) return <Progress percent={0} status="active" size="small" />

  const isTerminal = job ? TERMINAL.includes(job.status) : true
  const statusTone = job?.status === 'COMPLETED' ? 'success'
    : job?.status === 'FAILED' ? 'error'
      : job?.status === 'CANCELLED' || job?.status === 'EXPIRED' ? 'warning'
        : 'info'
  const labels: Record<ExportStatus, string> = tr
    ? { QUEUED: 'Bekliyor', RUNNING: 'Çalışıyor', COMPLETED: 'Tamamlandı', FAILED: 'Başarısız', CANCELLED: 'İptal Edildi', EXPIRED: 'Süresi Doldu' }
    : { QUEUED: 'Queued', RUNNING: 'Running', COMPLETED: 'Completed', FAILED: 'Failed', CANCELLED: 'Cancelled', EXPIRED: 'Expired' }

  return (
    <Space orientation="vertical" style={{ width: '100%', minWidth: 0 }}>
      {error && <Alert role="alert" title={error} type="error" showIcon action={<Button icon={<RefreshCw size={16} />} onClick={() => { setError(''); setAttempt(value => value + 1) }}>{tr ? 'Yeniden Dene' : 'Retry'}</Button>} />}
      {job && (
        <Alert
          title={`${tr ? 'Dışa Aktarma' : 'Export'} · ${labels[job.status]}`}
          description={
            <Space orientation="vertical" size={0}>
              <Typography.Text>{tr ? 'İşlenen Satır' : 'Processed rows'}: {job.processedRows.toLocaleString(i18n.language)}</Typography.Text>
              <Typography.Text>{tr ? 'Dışa Aktarılan Satır' : 'Exported rows'}: {job.resultRows.toLocaleString(i18n.language)}</Typography.Text>
              {job.byteSize > 0 && (
                <Typography.Text>{tr ? 'Boyut' : 'Size'}: {(job.byteSize / 1024 / 1024).toLocaleString(i18n.language, { maximumFractionDigits: 2 })} MB</Typography.Text>
              )}
              {job.errorMessage && <Typography.Text type="danger">{job.errorMessage}</Typography.Text>}
            </Space>
          }
          type={statusTone}
          showIcon
          action={
            <Space wrap>
              {job.status === 'COMPLETED' && (
                <Button icon={<Download size={16} />} loading={downloading} onClick={download}>
                  {tr ? 'JSON İndir' : 'Download JSON'}
                </Button>
              )}
              {!isTerminal && (
                <Button icon={<XCircle size={16} />} danger loading={cancelling} onClick={() => void cancel()}>
                  {tr ? 'İptal Et' : 'Cancel'}
                </Button>
              )}
            </Space>
          }
        />
      )}
      {!isTerminal && !error && <Progress percent={0} status="active" showInfo={false} size="small" />}
    </Space>
  )
}
