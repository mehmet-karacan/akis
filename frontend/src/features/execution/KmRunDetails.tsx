import { Alert, Popconfirm } from 'antd'
import { CalendarClock, CheckCircle2, Circle, CircleStop, Clock3, Code2, Database, MapPin, Rows3, ShieldAlert, Timer, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { apiRequest } from '../../core/api/client'
import { SqlEditor } from '../../core/ui'
import { Button } from '../../core/ui/Button'
import { formatOperationalDateTime, formatOperationalDuration } from '../../core/i18n/formatters'
import { knowledgeStepLabel } from '../definitions/knowledgeModuleSteps'

export interface SqlEvidence { step: string; site: string; owner: string; sql: string }
export interface KmRunData {
  reconciliation?: { outcome: string; rows: number | null } | null
  steps: { generation: number; ordinal: number; stepCode: string; operation: string; site: string; slot: string; state: string; affectedRows: number | null; errorCode: string | null; startedAt: string | null; completedAt: string | null; executedSql?: SqlEvidence[] | null }[]
  workObjects: { uuid: string; owner: string; name: string; state: string; rows: number | null; bytes: number | null }[]
}

const stateLabel: Record<string, [string, string]> = { PENDING: ['Bekliyor', 'Pending'], RUNNING: ['Çalışıyor', 'Running'], SUCCEEDED: ['Tamamlandı', 'Completed'], FAILED: ['Başarısız', 'Failed'], UNKNOWN: ['Sonuç Belirsiz', 'Outcome Unknown'], SKIPPED: ['Devralındı', 'Adopted'] }

export function KmRunDetails({ data, projectUuid, runUuid, onChanged }: { data: KmRunData; projectUuid?: string; runUuid?: string; onChanged?(): void | Promise<unknown> }) {
  const { i18n } = useTranslation(); const tr = i18n.language.startsWith('tr'); const lang = tr ? 0 : 1
  const generation = Math.max(0, ...data.steps.map(step => step.generation))
  const steps = useMemo(() => data.steps.filter(step => step.generation === generation), [data.steps, generation])
  const preferred = steps.find(step => ['FAILED', 'UNKNOWN', 'RUNNING'].includes(step.state)) ?? steps.find(step => step.operation === 'TRANSFER_JDBC') ?? steps[0]
  const [selectedKey, setSelectedKey] = useState(() => preferred ? `${preferred.generation}:${preferred.ordinal}` : '')
  const selected = steps.find(step => `${step.generation}:${step.ordinal}` === selectedKey) ?? preferred
  const [cleaning, setCleaning] = useState<string | null>(null)
  const cleanup = async (uuid: string) => { if (!projectUuid || !runUuid) return; setCleaning(uuid); try { await apiRequest(`/api/v1/projects/${encodeURIComponent(projectUuid)}/runs/${encodeURIComponent(runUuid)}/knowledge-modules/work-objects/${encodeURIComponent(uuid)}:cleanup-reviewed`, { method: 'POST' }); await onChanged?.() } finally { setCleaning(null) } }
  const statusIcon = (state: string) => state === 'SUCCEEDED' ? <CheckCircle2 /> : state === 'FAILED' || state === 'UNKNOWN' ? <ShieldAlert /> : state === 'RUNNING' ? <Clock3 /> : <Circle />
  return <div className="km-run-workbench">
    <nav className="km-run-flow" aria-label={tr ? 'Çalıştırma adımları' : 'Execution steps'}>
      <header><span>{tr ? 'İŞ AKIŞI' : 'FLOW'}</span><strong>{steps.length} {tr ? 'adım' : 'steps'}</strong></header>
      {steps.map(step => { const key = `${step.generation}:${step.ordinal}`; return <button type="button" key={key} className={key === `${selected?.generation}:${selected?.ordinal}` ? 'is-selected' : ''} data-state={step.state.toLowerCase()} onClick={() => setSelectedKey(key)}>
        <span className="km-step-index">{step.ordinal}</span><span className="km-step-copy"><strong>{knowledgeStepLabel(step.stepCode, tr ? 'tr-TR' : 'en-US')}</strong><small>{stateLabel[step.state]?.[lang] ?? step.state}{step.affectedRows != null ? ` · ${step.affectedRows.toLocaleString(i18n.language)} ${tr ? 'satır' : 'rows'}` : ''}</small></span><span className="km-step-state">{statusIcon(step.state)}</span>
      </button> })}
    </nav>
    {selected && <article className="km-step-inspector">
      {data.reconciliation ? <Alert type={data.reconciliation.outcome === 'PUBLISHED' ? 'success' : 'warning'} showIcon title={data.reconciliation.outcome === 'PUBLISHED' ? (tr ? 'Hedef yayını mutabakat ile doğrulandı.' : 'Target publication confirmed by reconciliation.') : (tr ? 'Hedef sonucu mutabakat ile doğrulandı.' : 'Target outcome confirmed by reconciliation.')} description={tr ? 'İlk çalıştırmanın adım kayıtları değiştirilmeden korunur.' : 'Original step records are preserved without modification.'} /> : steps.some(step => step.state === 'UNKNOWN') ? <Alert type="warning" showIcon title={tr ? 'Hedef sonucu doğrulanmalı. Aynı işlem otomatik tekrar edilmez.' : 'The target outcome must be reconciled. The operation is not automatically retried.'} /> : null}
      <header><div><span>{tr ? 'SEÇİLİ ADIM' : 'SELECTED STEP'}</span><h3>{knowledgeStepLabel(selected.stepCode, tr ? 'tr-TR' : 'en-US')}</h3><small>{selected.stepCode} · {selected.operation}</small></div><span className={`km-state-signal km-state-signal--${selected.state.toLowerCase()}`}>{statusIcon(selected.state)} {stateLabel[selected.state]?.[lang] ?? selected.state}</span></header>
      <div className="km-step-facts">
        <div data-fact="start"><CalendarClock /><span>{tr ? 'Başlangıç Zamanı' : 'Start Time'}</span><strong>{formatOperationalDateTime(selected.startedAt, tr ? 'Kaydedilmedi' : 'Not recorded')}</strong></div>
        <div data-fact="end"><CircleStop /><span>{tr ? 'Bitiş Zamanı' : 'End Time'}</span><strong>{formatOperationalDateTime(selected.completedAt, tr ? 'Kaydedilmedi' : 'Not recorded')}</strong></div>
        <div data-fact="duration"><Timer /><span>{tr ? 'Süre' : 'Duration'}</span><strong>{formatOperationalDuration(selected.startedAt, selected.completedAt, i18n.language, tr ? 'Kaydedilmedi' : 'Not recorded')}</strong></div>
        <div data-fact="rows"><Rows3 /><span>{tr ? 'Etkilenen Satır' : 'Affected Rows'}</span><strong>{selected.affectedRows?.toLocaleString(i18n.language) ?? (tr ? 'Uygulanmaz' : 'Not applicable')}</strong></div>
      </div>
      {selected.errorCode && <Alert type="error" showIcon title={selected.errorCode} />}
      <section className="km-sql-evidence"><header><div><Code2 /><span><strong>{tr ? 'Çalıştırılan SQL' : 'Executed SQL'}</strong><small>{tr ? 'Bu çalıştırmayla birlikte değişmez kanıt olarak saklanır.' : 'Stored as immutable evidence with this run.'}</small></span></div><em>{selected.executedSql?.length ?? 0} SQL</em></header>
        {selected.executedSql?.length ? <div className="km-sql-list">{selected.executedSql.map((entry, index) => <article key={`${entry.site}:${index}`}><div className="km-sql-site"><span><MapPin />{entry.site}</span><span><Database />{entry.owner || '—'}</span></div><SqlEditor value={entry.sql} onChange={() => undefined} label={`${tr ? 'Çalıştırılan SQL' : 'Executed SQL'} ${index + 1}`} readOnly wrapLines showToolbar={false} /></article>)}</div> : <div className="km-sql-empty"><Code2 /><strong>{tr ? 'Bu çalıştırmada SQL kaydedilmemiş.' : 'SQL was not recorded for this run.'}</strong><p>{tr ? 'Eski kayıtlar için SQL tahmin edilmez. Yeni çalıştırmalar SQL kanıtını adımla birlikte saklar.' : 'SQL is not guessed for historical rows. New runs store SQL evidence with each step.'}</p></div>}
      </section>
      {!!data.workObjects.length && <details className="km-technical-evidence"><summary>{tr ? 'Teknik çalışma nesneleri' : 'Technical work objects'} ({data.workObjects.length})</summary>{data.workObjects.map(item => <div key={item.uuid}><span><Database />{item.owner}.{item.name}</span><small>{item.state}{item.rows != null ? ` · ${item.rows.toLocaleString(i18n.language)} ${tr ? 'satır' : 'rows'}` : ''}</small>{item.state === 'REVIEW_REQUIRED' && projectUuid && runUuid ? <Popconfirm title={tr ? 'Kimliği ve yapısı doğrulanırsa çalışma tablosu silinsin mi?' : 'Delete after identity and structure verification?'} onConfirm={() => void cleanup(item.uuid)}><Button tone="danger" busy={cleaning === item.uuid} icon={<Trash2 />}>{tr ? 'Doğrula ve Temizle' : 'Verify and Clean'}</Button></Popconfirm> : null}</div>)}</details>}
    </article>}
  </div>
}
