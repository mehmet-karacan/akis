import { Alert, Button, Dropdown, Spin } from 'antd'
import { Download } from 'lucide-react'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog } from './Dialog'
import { ExportJobStatus } from './ExportJobStatus'
import { exportApi } from './exportApi'
import { downloadBlob } from './exportApi'
import type { ExportFilter, ExportRequest, ExportScope } from './exportTypes'

export interface ExportMenuProps {
  projectUuid?: string
  globalScope?: boolean
  dataset: string
  resourceId: string
  filters?: ExportFilter[]
  disabled?: boolean
  label?: string
  includeDetailsDefault?: boolean
}

export function ExportMenu({
  projectUuid,
  globalScope = false,
  dataset,
  resourceId,
  filters = [],
  disabled = false,
  label,
  includeDetailsDefault = false,
}: ExportMenuProps) {
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [error, setError] = useState('')
  const [job, setJob] = useState<{ projectUuid: string | null; globalScope: boolean; uuid: string } | null>(null)
  const pending = useRef(false)
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const title = label ?? (tr ? 'Dışa Aktar' : 'Export')

  async function startExport(scope: ExportScope, includeDetails: boolean) {
    if (pending.current || disabled) return
    pending.current = true
    setBusy(true)
    setError('')
    setJob(null)
    setOpen(true)
    try {
      const request: ExportRequest = {
        providerId: dataset,
        resourceId,
        scope,
        selectedColumns: [],
        filters,
        includeDetails,
        locale: i18n.language,
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC',
      }
      const job = globalScope
        ? await exportApi.createGlobalJob(request)
        : await exportApi.createJob(projectUuid!, request)
      setJob({ projectUuid: globalScope ? null : projectUuid!, globalScope, uuid: job.uuid })
      window.dispatchEvent(new CustomEvent('akis:export-job-created', { detail: job }))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : (tr ? 'Dışa aktarma başlatılamadı.' : 'Could not start export.'))
    } finally {
      pending.current = false
      setBusy(false)
    }
  }

  const items = [
    // VISIBLE/SELECTED need row identities in the API contract. Do not offer
    // them until the calling grid can send that exact set of records.
    { key: 'filtered', label: tr ? 'Filtrelenmiş sonuç' : 'Filtered results', onClick: () => void startExport('FILTERED', includeDetailsDefault) },
    { key: 'all', label: tr ? 'Tüm kayıtlar' : 'All records', onClick: () => void startExport('ALL', includeDetailsDefault) },
  ]

  return (
    <>
    <Dropdown menu={{ items }} trigger={['click']} disabled={disabled || busy}>
      <Button icon={<Download size={16} />} loading={busy} disabled={disabled || busy}>
        {title}
      </Button>
    </Dropdown>
    {job && <Button type="link" onClick={() => setOpen(true)}>{tr ? 'Aktarma Durumu' : 'Export Status'}</Button>}
    <Dialog open={open} title={<span className="ui-inline-title"><Download size={18} />{title}</span>} closeLabel={tr ? 'Kapat' : 'Close'} busy={busy} onClose={() => setOpen(false)}>
      {busy && <Spin tip={tr ? 'Dışa aktarma hazırlanıyor…' : 'Preparing export…'}><div style={{ minHeight: 80 }} /></Spin>}
      {error && <Alert role="alert" title={error} type="error" showIcon />}
      {job && <ExportJobStatus key={`${job.globalScope ? 'global' : job.projectUuid}:${job.uuid}`} projectUuid={job.projectUuid} globalScope={job.globalScope} exportUuid={job.uuid} />}
    </Dialog>
    </>
  )
}

export { downloadBlob }
