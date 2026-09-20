import { useEffect, useState } from 'react'
import { apiRequest } from '../api/client'
import { recordChangedEvent, type RecordChange, type RecordKind } from '../api/recordChanges'
import { useCurrentProjectUuid } from '../../features/projects/CurrentProjectContext'

export type AuditKind = RecordKind
export interface RecordAudit { uuid: string; createdBy: string | null; createdAt: string | null; updatedBy: string | null; updatedAt: string | null }
export function useRecordAudit(kind: AuditKind, revision = '') {
  const project = useCurrentProjectUuid()
  const [records, setRecords] = useState<Record<string, RecordAudit>>({})
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    const changed = (event: Event) => {
      const detail = (event as CustomEvent<RecordChange>).detail
      if (detail?.projectUuid === project && detail.kind === kind) setRefresh(value => value + 1)
    }
    window.addEventListener(recordChangedEvent, changed)
    return () => window.removeEventListener(recordChangedEvent, changed)
  }, [project, kind])
  useEffect(() => {
    let active = true
    setState('loading'); setRecords({})
    void apiRequest<RecordAudit[]>(`/api/v1/projects/${encodeURIComponent(project)}/record-audit/${kind}`).then(items => {
      if (active) { setRecords(Object.fromEntries(items.map(item => [item.uuid, item]))); setState('ready') }
    }).catch(() => { if (active) { setRecords({}); setState('error') } })
    return () => { active = false }
  }, [project, kind, revision, refresh])
  return { records, state }
}
