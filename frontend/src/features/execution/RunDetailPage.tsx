import { Alert, Descriptions, Table, Tag, Tree } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { ArrowLeft, CalendarClock, Database, ListTree, PlayCircle, RefreshCw, RotateCcw, StepForward, Timer, Unlock } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Button } from '../../core/ui/Button'
import { Dialog } from '../../core/ui/Dialog'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, PageHeader, SummaryStrip } from '../../core/ui'
import { connectionStatusTagStyles } from '../connections/presentation'
import { apiErrorMessage, formatDate, redactSensitiveValues } from '../operations/utils'
import { useRemoteData } from '../operations/useRemoteData'
import { executionApi } from './api'
import type { RunStatus, RunStep } from './types'
import { useExecutionI18n } from './i18n'
import { RunStatusBadge, runStatusPresentation } from './RunStatusBadge'
import { buildRunStepTree, firstFailedPath, groupRunStepUnits, type RunStepNode } from './runTree'
import { operationsApi } from '../operations/api'
import { definitionsApi } from '../definitions/api'
import type { ProcedureContent, ProcedureTask } from '../definitions/types'
import '../definitions/pre-run-report.css'
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
  // Package runs: the steps of each child run are shown inline under the package step (procedure steps, or the KM
  // journal of a mapping), so the package page reads like ODI's session tree without opening every child.
  const childKeys = (steps.data ?? []).filter(item => item.type === 'PAKET' && item.childRunUuid).map(item => `${item.uuid}:${item.childRunUuid}`).join(',')
  const childSteps = useRemoteData(async () => {
    const result = new Map<string, RunStep[]>()
    for (const parent of (steps.data ?? []).filter(item => item.type === 'PAKET' && item.childRunUuid)) {
      const child = parent.childRunUuid!
      try {
        const rows = await executionApi.listSteps(project, child)
        if (rows.length) { result.set(parent.uuid, rows.map(row => ({ ...row, parentUuid: parent.uuid }))); continue }
        const km = await executionApi.getKmDetails(project, child)
        const generation = Math.max(0, ...km.steps.map(item => item.generation))
        result.set(parent.uuid, km.steps.filter(item => item.generation === generation).map(item => ({
          uuid: `${child}:${item.ordinal}`, parentUuid: parent.uuid, code: item.stepCode, type: 'KM', ordinal: item.ordinal, name: item.operation,
          status: item.state === 'SUCCEEDED' ? 'BASARILI' : item.state === 'FAILED' ? 'BASARISIZ' : item.state === 'RUNNING' ? 'CALISIYOR' : item.state === 'SKIPPED' ? 'ATLANDI' : item.state === 'UNKNOWN' ? 'SONUC_BELIRSIZ' : 'BEKLIYOR',
          connectionRole: null, risk: null, startedAt: item.startedAt, finishedAt: item.completedAt, rowCount: item.affectedRows, byteCount: null, errorCode: item.errorCode,
        })))
      } catch { /* child detail stays reachable through its own page */ }
    }
    return result
  }, [project, childKeys])
  const allSteps = useMemo(() => [...(steps.data ?? []), ...[...(childSteps.data?.values() ?? [])].flat()], [steps.data, childSteps.data])
  const hierarchy = useMemo(() => buildRunStepTree(allSteps), [allSteps])
  // The published procedure version supplies the command text per step code; the run store keeps only outcomes.
  const publicationUuid = run.data?.publicationUuid
  const published = useRemoteData(async () => {
    if (!publicationUuid) return null
    const publication = await operationsApi.getPublication(project, publicationUuid)
    const version = (await definitionsApi.listVersions(project, publication.definitionUuid)).find(item => item.uuid === publication.definitionVersionUuid)
    const content = version?.content as Partial<ProcedureContent> | undefined
    return new Map<string, ProcedureTask>((content?.tasks ?? []).map(task => [task.id, task]))
  }, [project, publicationUuid])
  const tasks = published.data ?? null
  const units = useMemo(() => groupRunStepUnits(hierarchy, (source, target) => tasks?.get(target)?.input?.fromTask === source), [hierarchy, tasks])
  const unitOf = (stepUuid: string) => units.find(unit => unit.commands.some(item => item.uuid === stepUuid) || unit.children.some(item => item.uuid === stepUuid))
  useEffect(() => { if (!selected && steps.data?.length) setSelected(firstFailedPath(hierarchy).at(-1) ?? steps.data[0]!.uuid) }, [hierarchy, selected, steps.data])
  useEffect(() => { setChunkCursor('0'); setChunkHistory([]) }, [selected])
  const step = allSteps.find(item => item.uuid === selected)
  const selectedRows = totalRows(hierarchy, false)
  const insertedRows = totalRows(hierarchy, true)
  // A package step the resumed attempt adopted from the failed one is shown as adopted rather than skipped.
  const stepLabel = (item: { type: string; status: RunStatus }) => item.type === 'PAKET' && item.status === 'ATLANDI' ? t('adoptedStep') : undefined
  const treeData = (nodes: RunStepNode[]): DataNode[] => nodes.map(item => ({ key: item.uuid, title: <span className="run-step-title"><span>{item.ordinal}. {item.name}</span><RunStatusBadge status={item.status} label={stepLabel(item)} /></span>, children: treeData(item.children) }))
  const unitTreeData: DataNode[] = units.map(unit => ({ key: unit.key, title: <span className="run-step-title"><span>{unit.ordinal}. {unit.name}</span><RunStatusBadge status={unit.status} /></span>, children: treeData(unit.children) }))
  const selectedUnit = unitOf(selected)
  const rowsLabel = (item: RunStep) => item.connectionRole === 'SOURCE' ? t('selectedRows') : item.logCounter === 'INSERT' && ['COMMITTED', 'COMMIT_CONFIRMED'].includes(item.transactionState ?? '') ? t('insertedRows') : t('rowCount')
  const rowsValue = (item: RunStep) => (exactCount(item.rowCountExact) ?? (item.rowCount == null ? null : BigInt(item.rowCount)))?.toLocaleString(locale) ?? t('notRecorded')
  const failure = events.data?.items.filter(item => /FAIL|ERROR|HATA|BASARISIZ/i.test(item.type)).map(item => eventError(item.data)).find(Boolean)
  // Run history is read-only: it shows what happened; operations (cancel, recovery) live with the publication.
  const refresh = () => Promise.all([run.reload(), steps.reload(), events.reload(), km.reload(), chunks.reload()])
  // Poll while the worker still owns the run so a freshly started run settles without manual refresh.
  const inFlight = !!run.data && !/^(BASARILI|BASARISIZ|IPTAL|MUDAHALE_GEREKLI|YENIDEN_DENENEBILIR)$/.test(run.data.status)
  useEffect(() => { if (!inFlight) return; const timer = setInterval(() => { void refresh() }, 4000); return () => clearInterval(timer) }, [inFlight, uuid])
  const statusTag = (status: string) => { const presentation = runStatusPresentation(status, t); return <Tag className={`connection-status-tag connection-status-tag--${presentation.tone} run-status run-status--${status.toLowerCase().replaceAll('_', '-')}`} style={connectionStatusTagStyles[presentation.tone]} icon={presentation.icon}><span className="connection-status-tag-label">{presentation.label}</span></Tag> }
  // Resume / restart: no approval step. A run whose target outcome is unknown is reconciled first; NOT_PUBLISHED makes it resumable.
  const navigate = useNavigate()
  const [recovering, setRecovering] = useState<'RESUME' | 'RESTART' | null>(null)
  const [recoveryNotice, setRecoveryNotice] = useState<{ tone: 'success' | 'error' | 'info'; text: string } | null>(null)
  const recoverable = !!run.data && /^(BASARISIZ|YENIDEN_DENENEBILIR|IPTAL|SONUC_BELIRSIZ|MUDAHALE_GEREKLI)$/.test(run.data.status)
  const recover = async (action: 'RESUME' | 'RESTART') => {
    if (!run.data) return
    setRecovering(action); setRecoveryNotice(null)
    try {
      let plan = await executionApi.getRecoveryPlan(project, uuid)
      if (plan.reconciliationRequired) {
        const reconciled = await executionApi.reconcileKm(project, uuid)
        setRecoveryNotice({ tone: 'info', text: t('reconciledFirst', { message: reconciled.message }) })
        if (reconciled.outcome !== 'NOT_PUBLISHED') { await refresh(); return }
        await refresh(); plan = await executionApi.getRecoveryPlan(project, uuid)
      }
      if (!plan.allowedActions.includes(action)) { setRecoveryNotice({ tone: 'error', text: t('resumeUnavailable', { reason: plan.reasonCodes.join(', ') || action }) }); return }
      const started = await executionApi.recoverRun(project, uuid, { action, expectedStateVersion: plan.expectedStateVersion, planHash: plan.planHash }, crypto.randomUUID())
      setRecoveryNotice({ tone: 'success', text: t(action === 'RESUME' ? 'resumeStarted' : 'restartStarted', { number: started.attemptNumber }) })
      navigate(`/project/operations/runs/${started.runUuid}`)
    } catch (error) { setRecoveryNotice({ tone: 'error', text: apiErrorMessage(error, t('reconcileFailed')) }) }
    finally { setRecovering(null) }
  }
  const [releaseOpen, setReleaseOpen] = useState(false)
  const [releaseReason, setReleaseReason] = useState('')
  const releaseTarget = async () => {
    setRecovering('RESTART'); setRecoveryNotice(null)
    try { await executionApi.releaseTarget(project, uuid, releaseReason.trim()); setReleaseOpen(false); setReleaseReason(''); setRecoveryNotice({ tone: 'success', text: t('releaseTargetDone') }); await refresh() }
    catch (error) { setRecoveryNotice({ tone: 'error', text: apiErrorMessage(error, t('requestFailed')) }) }
    finally { setRecovering(null) }
  }
  const quarantined = run.data?.status === 'MUDAHALE_GEREKLI'
  const headerActions = <div className="connection-row-actions">
    {quarantined && <Button tone="danger" icon={<Unlock size={16} />} disabled={!!recovering} title={t('releaseTargetHelp')} onClick={() => setReleaseOpen(true)}>{t('releaseTarget')}</Button>}
    {recoverable && <Button tone="primary" icon={<StepForward size={16} />} disabled={!!recovering} title={t('resumeHelp')} onClick={() => void recover('RESUME')}>{t('resumeSafely')}</Button>}
    {recoverable && <Button icon={<RotateCcw size={16} />} disabled={!!recovering} onClick={() => void recover('RESTART')}>{t('restartFromBeginning')}</Button>}
    <Button icon={<RefreshCw size={16} />} disabled={run.loading} onClick={() => void refresh()}>{t('refresh')}</Button>
  </div>
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
      {recoveryNotice && <Alert type={recoveryNotice.tone} showIcon title={recoveryNotice.text} />}
      {run.data.status === 'BASARISIZ' && <Alert type="error" showIcon title={failure ?? t('errorMessageUnavailable')} />}
      <section className="connection-detail-section"><header><div><h2><ListTree size={18} />{t('steps')}</h2></div></header>
        {!!km.error && <AsyncState state="error" title={apiErrorMessage(km.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void km.reload()} />}
        {!!km.data?.steps.length ? <>
          <KmRunDetails data={km.data} />
        </> : <>
        {steps.loading && <AsyncState state="loading" title={t('loading')} />}{!!steps.error && <AsyncState state="error" title={apiErrorMessage(steps.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void steps.reload()} />}
        {!steps.loading && !steps.error && !steps.data?.length && <AsyncState state="empty" compact title={t('emptySteps')} />}
        {!!steps.data?.length && <div className="run-result-steps"><Tree blockNode defaultExpandAll key={uuid + steps.data.length + units.length} treeData={unitTreeData} selectedKeys={[selectedUnit?.key ?? selected]} onSelect={keys => { if (keys[0]) setSelected(String(keys[0])) }} aria-label={t('steps')} />
          {step && selectedUnit && <section aria-label={t('stepDetail')}><h3>{selectedUnit.name}</h3><Descriptions column={1} size="small" items={[
            { key: 'status', label: t('status'), children: statusTag(selectedUnit.status) },
            { key: 'start', label: t('startedAt'), children: formatDate(selectedUnit.commands[0]!.startedAt, locale) },
            { key: 'end', label: t('finishedAt'), children: formatDate(selectedUnit.commands.at(-1)!.finishedAt, locale) },
            ...(selectedUnit.commands.length === 1 ? [{ key: 'rows', label: rowsLabel(step), children: rowsValue(step) }] : []),
          ]} />{selectedUnit.commands.map(item => item.errorCode && <Alert key={item.uuid} type="error" showIcon title={item.errorCode} description={t('errorMessageUnavailable')} />)}
          {step.type === 'PAKET' && step.childRunUuid && <p className="run-step-child"><Link to={`/project/operations/runs/${step.childRunUuid}`}><PlayCircle size={14} aria-hidden="true" /> {t('openChildRun')}</Link></p>}
          {step.type !== 'PAKET' && <>
          <ul className="prerun-sql-list run-step-commands" aria-label={t('commandSql')}>{selectedUnit.commands.map(item => { const task = tasks?.get(item.code); const role = item.connectionRole === 'SOURCE' ? 'source' : 'target'; return <li key={item.uuid} className={`prerun-sql prerun-sql--${role}`}>
            <div className="prerun-sql-head"><strong>{item.connectionRole === 'SOURCE' ? t('sourceCommand') : t('targetCommand')}</strong><RunStatusBadge status={item.status} />{selectedUnit.commands.length > 1 && <span className="prerun-cell-hint">{rowsLabel(item)}: {rowsValue(item)}</span>}<code>{task?.type ?? item.type} · {item.code}</code></div>
            <pre><code>{task?.command ?? (published.loading ? '…' : t('sqlUnavailable'))}</code></pre></li> })}</ul></>}
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
  const releaseDialog = <Dialog open={releaseOpen} title={t('releaseTargetTitle')} closeLabel={t('close')} busy={recovering === 'RESTART'} onClose={() => setReleaseOpen(false)} className="akis-modal">
    <div className="sidebar-delete-dialog">
      <p>{t('releaseTargetHelp')}</p>
      <label className="run-release-reason"><span>{t('releaseReason')}</span><textarea id="release-reason" rows={3} maxLength={500} value={releaseReason} onChange={(event) => setReleaseReason(event.target.value)} /></label>
      <div className="sidebar-delete-actions">
        <Button tone="ghost" type="button" onClick={() => setReleaseOpen(false)}>{t('close')}</Button>
        <Button tone="danger" type="button" icon={<Unlock size={15} />} disabled={!releaseReason.trim() || recovering === 'RESTART'} onClick={() => void releaseTarget()}>{t('confirmRelease')}</Button>
      </div>
    </div>
  </Dialog>
  return panel ? <Dialog open title={t('runDetail')} closeLabel={t('close')} onClose={() => onClose?.()} className="execution-detail-dialog connection-catalog-dialog">{content}{releaseDialog}</Dialog> : <>{content}{releaseDialog}</>
}
