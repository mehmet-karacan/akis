import { Alert, Descriptions, Table, Tree } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { ArrowLeft, Ban, RefreshCw, RotateCcw, ShieldAlert } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../core/ui/Button'
import { Dialog } from '../../core/ui/Dialog'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { EmptyState, ErrorState, LoadingState, Panel } from '../operations/OperationsUi'
import { apiErrorMessage, formatDate, redactSensitiveValues } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { executionApi, isExecutionDisabled } from './api'
import { ExecutionDisabledNotice } from './ExecutionDisabledNotice'
import { useExecutionI18n } from './i18n'
import { RunStatusBadge } from './RunStatusBadge'
import { buildRunStepTree, firstFailedPath, type RunStepNode } from './runTree'
import './execution.css'
import { KmRunDetails } from './KmRunDetails'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
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
  const { can } = useProjectAccess()
  const { t, locale } = useExecutionI18n()
  const run = useRemoteData(() => executionApi.getRun(project, uuid), [project, uuid])
  const steps = useRemoteData(() => executionApi.listSteps(project, uuid), [project, uuid])
  const events = useRemoteData(() => executionApi.listEventPage(project, uuid), [project, uuid])
  const km = useRemoteData(() => executionApi.getKmDetails(project, uuid), [project, uuid])
  const recovery = useRemoteData(() => executionApi.getRecoveryPlan(project, uuid), [project, uuid])
  const [selected, setSelected] = useState('')
  const [confirm, setConfirm] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [disabled, setDisabled] = useState(false)
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
  const refresh = () => Promise.all([run.reload(), steps.reload(), events.reload(), km.reload(), recovery.reload(), chunks.reload()])
  async function cancel() {
    setBusy(true); setError('')
    try { run.setData(await executionApi.cancelRun(project, uuid)); setConfirm(false); await events.reload() }
    catch (reason) { if (isExecutionDisabled(reason)) { setDisabled(true); setConfirm(false) } else setError(apiErrorMessage(reason, t('requestFailed'))) }
    finally { setBusy(false) }
  }
  async function reconcileKm() {
    setBusy(true)
    try {
      const result = await executionApi.reconcileKm(project, uuid)
      notifyFeedback(result.message, ['PUBLISHED', 'NOT_PUBLISHED'].includes(result.outcome) ? 'success' : 'error')
      await refresh()
    } catch (reason) { notifyFeedback(apiErrorMessage(reason, t('requestFailed')), 'error') }
    finally { setBusy(false) }
  }
  async function recover(action: string) {
    if (!recovery.data) return
    setBusy(true)
    try {
      const created = await executionApi.recoverRun(project, uuid, {
        action,
        expectedStateVersion: recovery.data.expectedStateVersion,
        planHash: recovery.data.planHash,
      }, crypto.randomUUID())
      notifyFeedback(t('recoveryCreated', { number: created.attemptNumber }), 'success')
      await refresh()
    } catch (reason) { notifyFeedback(apiErrorMessage(reason, t('requestFailed')), 'error') }
    finally { setBusy(false) }
  }
  const content = <section className="run-result-page">
    {!panel && <Link to="/project/operations"><ArrowLeft size={16} /> {t('backToRuns')}</Link>}
    <header className="run-result-header"><h2>{objectName ?? t('runDetail')}</h2><div><Button icon={<RefreshCw size={16} />} disabled={run.loading} onClick={() => void refresh()}>{t('refresh')}</Button>{run.data?.allowedActions?.some(action => action.action === 'CANCEL' && action.allowed) && <Button tone="danger" icon={<Ban size={16} />} disabled={disabled} onClick={() => setConfirm(true)}>{t('cancelRun')}</Button>}</div></header>
    {disabled && <ExecutionDisabledNotice />}{error && <Alert type="error" showIcon title={error} />}
    {run.loading && <LoadingState />}{!!run.error && <ErrorState message={apiErrorMessage(run.error, t('requestFailed'))} onRetry={() => void run.reload()} />}
    {run.data && <>
      <Descriptions bordered size="small" column={{ xs: 1, sm: 2, md: 3 }} items={[
        { key: 'status', label: t('status'), children: <RunStatusBadge status={run.data.status} /> },
        { key: 'start', label: t('startedAt'), children: formatDate(run.data.startedAt, locale) },
        { key: 'end', label: t('finishedAt'), children: formatDate(run.data.finishedAt, locale) },
        ...(selectedRows === null ? [] : [{ key: 'read', label: t('selectedRows'), children: selectedRows.toLocaleString(locale) }]),
        ...(insertedRows === null ? [] : [{ key: 'write', label: t('insertedRows'), children: insertedRows.toLocaleString(locale) }]),
      ]} />
      {run.data.status === 'BASARISIZ' && <Alert type="error" showIcon title={failure ?? t('errorMessageUnavailable')} />}
      {!recovery.loading && recovery.data && (recovery.data.allowedActions.length > 0 || recovery.data.reasonCodes.length > 0) && <Panel title={t('safeRecovery')}>
        {recovery.data.reconciliationRequired && <Alert type="warning" showIcon icon={<ShieldAlert size={16} />} title={t('reconciliationRequired')} description={t('reconciliationRequiredHelp')} />}
        {!recovery.data.allowedActions.length && !recovery.data.reconciliationRequired && <Alert type="info" showIcon title={t('recoveryUnavailable')} description={recovery.data.reasonCodes.join(', ')} />}
        {!!recovery.data.allowedActions.filter(action => ['RETRY_FAILED_UNIT', 'RESUME', 'RESTART'].includes(action)).length && <div className="run-result-actions">
          {recovery.data.allowedActions.includes('RETRY_FAILED_UNIT') && <Button busy={busy} icon={<RotateCcw size={16} />} onClick={() => void recover('RETRY_FAILED_UNIT')}>{t('retryFailedUnit')}</Button>}
          {recovery.data.allowedActions.includes('RESUME') && <Button busy={busy} icon={<RefreshCw size={16} />} onClick={() => void recover('RESUME')}>{t('resumeSafely')}</Button>}
          {recovery.data.allowedActions.includes('RESTART') && <Button busy={busy} icon={<RotateCcw size={16} />} onClick={() => void recover('RESTART')}>{t('restartFromBeginning')}</Button>}
        </div>}
      </Panel>}
      <Panel title={t('steps')}>
        {!!km.error && <ErrorState message={apiErrorMessage(km.error, t('requestFailed'))} onRetry={() => void km.reload()} />}
        {!!km.data?.steps.length ? <>
          <KmRunDetails data={km.data} />
          {run.data.status === 'SONUC_BELIRSIZ' && can('CALISTIRMA_BASLAT') && <Button busy={busy} onClick={() => void reconcileKm()} icon={<RefreshCw size={16} />}>{locale.startsWith('tr') ? 'Hedef Sonucunu Doğrula' : 'Reconcile Target Outcome'}</Button>}
        </> : <>
        {steps.loading && <LoadingState />}{!!steps.error && <ErrorState message={apiErrorMessage(steps.error, t('requestFailed'))} onRetry={() => void steps.reload()} />}
        {!steps.loading && !steps.error && !steps.data?.length && <EmptyState>{t('emptySteps')}</EmptyState>}
        {!!steps.data?.length && <div className="run-result-steps"><Tree blockNode defaultExpandAll key={uuid + steps.data.length} treeData={treeData(hierarchy)} selectedKeys={[selected]} onSelect={keys => { if (keys[0]) setSelected(String(keys[0])) }} aria-label={t('steps')} />
          {step && <section aria-label={t('stepDetail')}><h3>{step.name}</h3><Descriptions column={1} size="small" items={[
            { key: 'status', label: t('status'), children: <RunStatusBadge status={step.status} /> },
            { key: 'start', label: t('startedAt'), children: formatDate(step.startedAt, locale) },
            { key: 'end', label: t('finishedAt'), children: formatDate(step.finishedAt, locale) },
            { key: 'rows', label: step.connectionRole === 'SOURCE' ? t('selectedRows') : step.logCounter === 'INSERT' && ['COMMITTED', 'COMMIT_CONFIRMED'].includes(step.transactionState ?? '') ? t('insertedRows') : t('rowCount'), children: (exactCount(step.rowCountExact) ?? (step.rowCount == null ? null : BigInt(step.rowCount)))?.toLocaleString(locale) ?? '—' },
          ]} />{step.errorCode && <Alert type="error" showIcon title={step.errorCode} description={t('errorMessageUnavailable')} />}
          {!!chunks.data?.items.length && <section className="run-chunk-evidence" aria-label={t('chunkEvidence')}><h4>{t('chunkEvidence')}</h4><Table size="small" pagination={false} rowKey="uuid" dataSource={chunks.data.items} columns={[
            { title: '#', dataIndex: 'sequence' },
            { title: t('status'), dataIndex: 'status' },
            { title: t('range'), render: (_, item) => `${item.lowerExclusive ?? '−∞'} → ${item.upperInclusive ?? '∞'}` },
            { title: t('rowCount'), render: (_, item) => exactCount(item.rowCountExact)?.toLocaleString(locale) ?? '—' },
            { title: t('byteCount'), render: (_, item) => exactCount(item.byteCountExact)?.toLocaleString(locale) ?? '—' },
          ]} /><div className="run-result-actions"><Button disabled={!chunkHistory.length} onClick={() => { const previous = chunkHistory.at(-1) ?? '0'; setChunkHistory(value => value.slice(0, -1)); setChunkCursor(previous) }}>{t('previousPage')}</Button><Button disabled={!chunks.data.hasMore || !chunks.data.nextCursor} onClick={() => { if (!chunks.data?.nextCursor) return; setChunkHistory(value => [...value, chunkCursor]); setChunkCursor(chunks.data.nextCursor) }}>{t('nextPage')}</Button></div></section>}
          </section>}
        </div>}
        </>}
      </Panel>
      {events.error && <ErrorState message={apiErrorMessage(events.error, t('requestFailed'))} onRetry={() => void events.reload()} />}
    </>}
    <Dialog open={confirm} title={t('confirmCancel')} closeLabel={t('close')} onClose={() => { if (!busy) setConfirm(false) }}><p>{t('confirmCancelHelp')}</p><div className="run-result-actions"><Button disabled={busy} onClick={() => setConfirm(false)}>{t('keepRun')}</Button><Button tone="danger" busy={busy} onClick={() => void cancel()}>{t('confirm')}</Button></div></Dialog>
  </section>
  return panel ? <Dialog open title={t('runDetail')} closeLabel={t('close')} onClose={() => onClose?.()} className="execution-detail-dialog">{content}</Dialog> : content
}
