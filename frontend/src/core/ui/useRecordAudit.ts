import { useEffect, useState } from 'react'
import { apiRequest } from '../api/client'
import { useCurrentProjectUuid } from '../../features/projects/CurrentProjectContext'

export type AuditKind = 'connections' | 'logical-schemas' | 'environments' | 'physical-schemas' | 'models' | 'definitions' | 'folders'
export interface RecordAudit { uuid: string; createdBy: string | null; createdAt: string | null; updatedBy: string | null; updatedAt: string | null }
export function useRecordAudit(kind: AuditKind, revision = '') {
  const project = useCurrentProjectUuid()
  const [records, setRecords] = useState<Record<string, RecordAudit>>({})
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  useEffect(() => {
    let active = true
    setState('loading')
    void apiRequest<RecordAudit[]>(`/api/v1/projects/${project}/record-audit/${kind}`).then(items => {
      if (active) { setRecords(Object.fromEntries(items.map(item => [item.uuid, item]))); setState('ready') }
    }).catch(() => { if (active) { setRecords({}); setState('error') } })
    return () => { active = false }
  }, [project, kind, revision])
  return { records, state }
}
