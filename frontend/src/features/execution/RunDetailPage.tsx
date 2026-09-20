import { Alert, Descriptions, Table, Tag, Tree } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { ArrowLeft, CalendarClock, Database, ListTree, PlayCircle, RefreshCw, Timer } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../core/ui/Button'
import { Dialog } from '../../core/ui/Dialog'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, PageHeader, SummaryStrip } from '../../core/ui'
import { connectionStatusTagStyles } from '../connections/presentation'
import { apiErrorMessage, formatDate, redactSensitiveValues } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { executionApi } from './api'
import { useExecutionI18n } from './i18n'
import { RunStatusBadge, runStatusPresentation } from './RunStatusBadge'
import { buildRunStepTree, firstFailedPath, type RunStepNode } from './runTree'
import './execution.css'
import '../connections/connections.css'
import '../connections/catalog-layout.css'
import { KmRunDetails } from './KmRunDetails'
import { exactCount } from './types'

interface RunDetailPageProps { runUuidOverride?: string; panel?: boolean; onClose?: () => void; objectName?: string }
function totalRows(nodes: RunStepNode[], insert: boolean): bigint | null {
  const values: bigint[] = []
  const visit = (items: RunStepNode[]) => items.forEach(step => {
    if (step.children.length) visit(step.children)
    else if (step.status === 'BASARILI' && (step.rowCountExact != null || step.rowCount != null) && (insert ? step.logCounter === 'INSERT' && ['COMMITTED', 'COMMIT_CONFIRMED'].includes(step.transactionState ?? '') : step.connectionRole === 'SOURCE')) values.push(exactCount(step.rowCountExact) ?? BigInt(step.rowCount!))
  })
  visit(nodes)
  return values.length ? values.reduce((sum, value) => sum + value, 0n) : null
}
function eventError(data: unknown): string | undefined {
  const safe = redactSensitiveValues(data)
  if (!safe || typeof safe !== 'object') return undefined
  const record = safe as Record<string, unknown>
  for (const key of ['errorMessage', 'message', 'reason']) if (typeof record[key] === 'string') return record[key] as string
  return undefined
}
export function RunDetailPage({ runUuidOverride, panel = false, onClose, objectName }: RunDetailPageProps = {}) {
  const { runUuid: routeUuid = '' } = useParams()
  const uuid = runUuidOverride ?? routeUuid
  const project = useCurrentProjectUuid()
  const { t, locale } = useExecutionI18n()
  const run = useRemoteData(() => executionApi.getRun(project, uuid), [project, uuid])
  const steps = useRemoteData(() => executionApi.listSteps(project, uuid), [project, uuid])
  const events = useRemoteData(() => executionApi.listEventPage(project, uuid), [project, uuid])
  const km = useRemoteData(() => executionApi.getKmDetails(project, uuid), [project, uuid])
  const [selected, setSelected] = useState('')
  const [chunkCursor, setChunkCursor] = useState('0')
  const [chunkHistory, setChunkHistory] = useState<string[]>([])
  const chunks = useRemoteData(() => selected
    ? executionApi.listChunks(project, uuid, selected, chunkCursor)
    : Promise.resolve({ items: [], nextCursor: null, hasMore: false }), [project, uuid, selected, chunkCursor])
  const hierarchy = useMemo(() => buildRunStepTree(steps.data ?? []), [steps.data])
  useEffect(() => { if (!selected && steps.data?.length) setSelected(firstFailedPath(hierarchy).at(-1) ?? steps.data[0]!.uuid) }, [hierarchy, selected, steps.data])
  useEffect(() => { setChunkCursor('0'); setChunkHistory([]) }, [selected])
  const step = steps.data?.find(item => item.uuid === selected)
  const selectedRows = totalRows(hierarchy, false)
  const insertedRows = totalRows(hierarchy, true)
  const treeData = (nodes: RunStepNode[]): DataNode[] => nodes.map(item => ({ key: item.uuid, title: <span className="run-step-title"><span>{item.ordinal}. {item.name}</span><RunStatusBadge status={item.status} /></span>, children: treeData(item.children) }))
  const failure = events.data?.items.filter(item => /FAIL|ERROR|HATA|BASARISIZ/i.test(item.type)).map(item => eventError(item.data)).find(Boolean)
  // Run history is read-only: it shows what happened; operations (cancel, recovery) live with the publication.
  const refresh = () => Promise.all([run.reload(), steps.reload(), events.reload(), km.reload(), chunks.reload()])
  const statusTag = (status: string) => { const presentation = runStatusPresentation(status, t); return <Tag className={`connection-status-tag connection-status-tag--${presentation.tone} run-status run-status--${status.toLowerCase().replaceAll('_', '-')}`} style={connectionStatusTagStyles[presentation.tone]} icon={presentation.icon}><span className="connection-status-tag-label">{presentation.label}</span></Tag> }
  const headerActions = <div className="connection-row-actions"><Button icon={<RefreshCw size={16} />} disabled={run.loading} onClick={() => void refresh()}>{t('refresh')}</Button></div>
  const content = <section className={`page-stack connections-page run-result-page${panel ? ' connection-detail-page' : ''}`}>
    {!panel && <Link className="connection-back-link" to="/project/operations"><ArrowLeft size={16} /> {t('backToRuns')}</Link>}
    {panel
      ? <header className="run-result-header"><h2>{objectName ?? t('runDetail')}</h2>{headerActions}</header>
      : <section className="connection-management-panel"><PageHeader icon={<PlayCircle />} eyebrow={t('runDetail')} title={objectName ?? t('runDetail')} description={run.data ? formatDate(run.data.startedAt, locale) : ''} actions={headerActions} /></section>}
    {run.loading && <AsyncState state="loading" title={t('loading')} />}{!!run.error && <AsyncState state="error" title={apiErrorMessage(run.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void run.reload()} />}
    {run.data && <>
      <SummaryStrip ariaLabel={t('runDetail')} items={[
        { label: t('status'), value: statusTag(run.data.status), icon: <PlayCircle />, tone: runStatusPresentation(run.data.status, t).tone },
        { label: t('startedAt'), value: formatDate(run.data.startedAt, locale), icon: <CalendarClock />, tone: 'neutral' },
        { label: t('finishedAt'), value: formatDate(run.data.finishedAt, locale), icon: <Timer />, tone: 'neutral' },
        ...(selectedRows === null ? [] : [{ label: t('selectedRows'), value: selectedRows.toLocaleString(locale), icon: <Database />, tone: 'info' as const }]),
        ...(insertedRows === null ? [] : [{ label: t('insertedRows'), value: insertedRows.toLocaleString(locale), icon: <Database />, tone: 'teal' as const }]),
      ]} />
      {run.data.status === 'BASARISIZ' && <Alert type="error" showIcon title={failure ?? t('errorMessageUnavailable')} />}
      <section className="connection-detail-section"><header><div><h2><ListTree size={18} />{t('steps')}</h2></div></header>
        {!!km.error && <AsyncState state="error" title={apiErrorMessage(km.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void km.reload()} />}
        {!!km.data?.steps.length ? <>
          <KmRunDetails data={km.data} />
        </> : <>
        {steps.loading && <AsyncState state="loading" title={t('loading')} />}{!!steps.error && <AsyncState state="error" title={apiErrorMessage(steps.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void steps.reload()} />}
        {!steps.loading && !steps.error && !steps.data?.length && <AsyncState state="empty" compact title={t('emptySteps')} />}
        {!!steps.data?.length && <div className="run-result-steps"><Tree blockNode defaultExpandAll key={uuid + steps.data.length} treeData={treeData(hierarchy)} selectedKeys={[selected]} onSelect={keys => { if (keys[0]) setSelected(String(keys[0])) }} aria-label={t('steps')} />
          {step && <section aria-label={t('stepDetail')}><h3>{step.name}</h3><Descriptions column={1} size="small" items={[
            { key: 'status', label: t('status'), children: statusTag(step.status) },
            { key: 'start', label: t('startedAt'), children: formatDate(step.startedAt, locale) },
            { key: 'end', label: t('finishedAt'), children: formatDate(step.finishedAt, locale) },
            { key: 'rows', label: step.connectionRole === 'SOURCE' ? t('selectedRows') : step.logCounter === 'INSERT' && ['COMMITTED', 'COMMIT_CONFIRMED'].includes(step.transactionState ?? '') ? t('insertedRows') : t('rowCount'), children: (exactCount(step.rowCountExact) ?? (step.rowCount == null ? null : BigInt(step.rowCount)))?.toLocaleString(locale) ?? t('notRecorded') },
          ]} />{step.errorCode && <Alert type="error" showIcon title={step.errorCode} description={t('errorMessageUnavailable')} />}
          {!!chunks.data?.items.length && <section className="run-chunk-evidence" aria-label={t('chunkEvidence')}><h4>{t('chunkEvidence')}</h4><Table size="small" pagination={false} rowKey="uuid" dataSource={chunks.data.items} columns={[
            { title: '#', dataIndex: 'sequence' },
            { title: t('status'), dataIndex: 'status' },
            { title: t('range'), render: (_, item) => `${item.lowerExclusive ?? '−∞'} → ${item.upperInclusive ?? '∞'}` },
            { title: t('rowCount'), render: (_, item) => exactCount(item.rowCountExact)?.toLocaleString(locale) ?? t('notRecorded') },
            { title: t('byteCount'), render: (_, item) => exactCount(item.byteCountExact)?.toLocaleString(locale) ?? t('notRecorded') },
          ]} /><div className="run-result-actions"><Button disabled={!chunkHistory.length} onClick={() => { const previous = chunkHistory.at(-1) ?? '0'; setChunkHistory(value => value.slice(0, -1)); setChunkCursor(previous) }}>{t('previousPage')}</Button><Button disabled={!chunks.data.hasMore || !chunks.data.nextCursor} onClick={() => { if (!chunks.data?.nextCursor) return; setChunkHistory(value => [...value, chunkCursor]); setChunkCursor(chunks.data.nextCursor) }}>{t('nextPage')}</Button></div></section>}
          </section>}
        </div>}
        </>}
      </section>
      {events.error && <AsyncState state="error" title={apiErrorMessage(events.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void events.reload()} />}
    </>}
  </section>
  return panel ? <Dialog open title={t('runDetail')} closeLabel={t('close')} onClose={() => onClose?.()} className="execution-detail-dialog connection-catalog-dialog">{content}</Dialog> : content
}
