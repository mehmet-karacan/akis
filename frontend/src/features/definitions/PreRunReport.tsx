import { useRef, useState } from 'react'
import { AlertTriangle, Code2, Database, DatabaseZap, Download, FileJson, FileText, Hash, Layers3, ListOrdered, ScanSearch, Settings2, ShieldCheck, Target, X } from 'lucide-react'
import { Button } from '../../core/ui/Button'
import { SummaryStrip, type SummaryMetric } from '../../core/ui/SummaryStrip'
import { RecordDetailDialog } from '../../core/ui/RecordDetailDialog'
import { DataGrid } from '../../core/ui/DataGrid'
import { operationsApi } from '../operations/api'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { topologyApi, type Connection, type PhysicalSchema } from '../topology/api'
import { ConnectionsSection, connectionsMarkdown, type ConnectionUse } from './ConnectionsSection'
import './pre-run-report.css'

/** Physical plan as compiled by the backend planner (`StagedMappingPlanner`); only the fields the report reads. */
export interface PreRunPlan {
  physicalPlanHash: string
  planVersion?: number
  language?: string
  environmentUuid?: string
  steps: Array<{ id: string; site: string; operation: string; slot: string }>
  columns?: Array<{ source?: { object: string; column: string }; expression?: unknown; target: { object: string; column: string } }>
  bindings?: Array<{ nodeCode: string; role: string; owner: string; objectName: string; connectionVersionUuid?: string; physicalSchemaUuid?: string; schemaSnapshotFingerprint?: string }>
  staging?: { owner?: string; prefixes?: { loading?: string; integration?: string; error?: string }; nonReversibleDdl?: boolean; workAreaPolicy?: { policy?: { enabled?: boolean; allowSameSchema?: boolean; maxRowsPerRun?: number; maxBytesPerRun?: number; retentionHours?: number }; version?: number } }
  options?: { batchRows?: number; fetchRows?: number; maxRows?: number; maxBytes?: number; allowEmptySource?: boolean }
  modules?: Record<string, { versionUuid?: string; contentHash?: string; kind?: string; options?: Record<string, unknown> }>
}
export interface PreRunSqlStatement { step: string; site: string; owner: string; sql: string }
export interface PreRunPreview { plan: PreRunPlan; executionVerified: boolean; message: string; sqlPreview?: PreRunSqlStatement[] }

/** Which connection each plan binding opens; resolved from the project topology so the report names hosts and users. */
export function connectionUses(plan: PreRunPlan, connections: Connection[], physical: PhysicalSchema[], tr: boolean): ConnectionUse[] {
  const find = (binding: { connectionVersionUuid?: string; physicalSchemaUuid?: string }) => ({ connection: connections.find((item) => item.uuid === binding.connectionVersionUuid), physical: physical.find((item) => item.uuid === binding.physicalSchemaUuid) })
  const uses: ConnectionUse[] = (plan.bindings ?? []).map((binding) => ({ role: binding.role === 'KAYNAK' ? 'SOURCE' : 'TARGET', label: binding.role === 'KAYNAK' ? `${tr ? 'Kaynak' : 'Source'} · ${binding.nodeCode}` : `${tr ? 'Hedef' : 'Target'} · ${binding.nodeCode}`, ...find(binding), owner: binding.owner, object: binding.objectName, readOnly: binding.role === 'KAYNAK' }))
  const staging = plan.staging
  if (staging?.owner) {
    const target = (plan.bindings ?? []).find((binding) => binding.role === 'HEDEF')
    uses.push({ role: 'STAGING', label: 'Staging', ...(target ? find(target) : {}), owner: staging.owner, object: `${staging.prefixes?.loading ?? 'C$_'}…`, readOnly: false })
  }
  return uses
}

const STEP_LABELS: Record<string, [string, string]> = {
  CREATE_WORK: ['Çalışma Tablosunu Hazırla', 'Prepare Work Table'], TRANSFER_JDBC: ['Kaynağı Çalışma Alanına Aktar', 'Load Source into Work Area'],
  SEAL_WORK: ['Çalışma Tablosunu Mühürle', 'Seal Work Table'], CHECK_NOT_NULL: ['Boş Değer Kontrolü', 'Not Null Check'], ATOMIC_REPLACE: ['Hedefe Yaz', 'Write to Target'], CLEANUP: ['Temizlik', 'Cleanup'],
}
const stepLabel = (operation: string, tr: boolean) => STEP_LABELS[operation]?.[tr ? 0 : 1] ?? operation
const bytes = (value?: number) => value == null ? '—' : value >= 1024 ** 3 ? `${(value / 1024 ** 3).toFixed(1)} GB` : value >= 1024 ** 2 ? `${(value / 1024 ** 2).toFixed(0)} MB` : `${value} B`
const number = (value?: number) => value == null ? '—' : new Intl.NumberFormat('tr-TR').format(value)
const qualified = (binding?: { owner: string; objectName: string }) => binding ? `${binding.owner}.${binding.objectName}` : '—'

/** Builds the human-readable Markdown export; the same facts the panel shows, so the download can be reviewed offline. */
export function preRunReportMarkdown(preview: PreRunPreview, context: { definitionName: string; environmentName: string; tr: boolean; generatedAt?: Date; connections?: ConnectionUse[] }) {
  const { plan } = preview
  const tr = context.tr
  const sources = (plan.bindings ?? []).filter((item) => item.role === 'KAYNAK')
  const target = (plan.bindings ?? []).find((item) => item.role === 'HEDEF')
  const integration = plan.modules?.integration?.options ?? {}
  const writeMode = String(integration.WRITE_MODE ?? 'ATOMIC_DELETE_INSERT')
  const lines = [
    `# ${tr ? 'Çalıştırma Öncesi Rapor' : 'Pre-Run Report'} — ${context.definitionName}`, '',
    `- ${tr ? 'Ortam' : 'Environment'}: ${context.environmentName}`,
    `- ${tr ? 'Üretildi' : 'Generated'}: ${(context.generatedAt ?? new Date()).toISOString()}`,
    `- ${tr ? 'Plan özeti' : 'Plan hash'}: \`${plan.physicalPlanHash}\``,
    `- ${tr ? 'Canlı doğrulama' : 'Live verification'}: ${preview.executionVerified ? (tr ? 'yapıldı' : 'done') : (tr ? 'YAPILMADI — DB/şema/yetki kontrolleri çalıştırmada yapılır' : 'NOT DONE — DB/schema/privilege checks happen at run time')}`, '',
    `## ${tr ? 'Veri Akışı' : 'Data Flow'}`, '',
    ...sources.map((item) => `- ${tr ? 'Kaynak' : 'Source'} (${item.nodeCode}): **${qualified(item)}** — ${tr ? 'yalnız SELECT' : 'SELECT only'}`),
    `- ${tr ? 'Staging' : 'Staging'}: **${plan.staging?.owner ?? '—'}** (${[plan.staging?.prefixes?.loading, plan.staging?.prefixes?.integration, plan.staging?.prefixes?.error].filter(Boolean).join(' · ')})`,
    `- ${tr ? 'Hedef' : 'Target'} (${target?.nodeCode ?? '—'}): **${qualified(target)}**`, '',
    ...(context.connections?.length ? connectionsMarkdown(context.connections, tr) : []),
    `## ${tr ? 'Yazma Davranışı' : 'Write Behavior'}`, '',
    `- WRITE_MODE: **${writeMode}**`,
    `- TRUNCATE_TARGET: **${integration.TRUNCATE_TARGET === true ? 'true' : 'false'}**`,
    `- ${tr ? 'Geri alınamaz DDL' : 'Non-reversible DDL'}: **${plan.staging?.nonReversibleDdl ? (tr ? 'EVET (TRUNCATE TABLE)' : 'YES (TRUNCATE TABLE)') : (tr ? 'hayır' : 'no')}**`, '',
    `## ${tr ? 'Sınırlar' : 'Limits'}`, '',
    `- maxRows: ${number(plan.options?.maxRows)} · maxBytes: ${bytes(plan.options?.maxBytes)} · batchRows: ${number(plan.options?.batchRows)} · fetchRows: ${number(plan.options?.fetchRows)} · allowEmptySource: ${plan.options?.allowEmptySource ? 'true' : 'false'}`,
    `- ${tr ? 'Çalışma alanı politikası' : 'Work area policy'}: enabled=${plan.staging?.workAreaPolicy?.policy?.enabled ? 'true' : 'false'}, allowSameSchema=${plan.staging?.workAreaPolicy?.policy?.allowSameSchema ? 'true' : 'false'}, maxRowsPerRun=${number(plan.staging?.workAreaPolicy?.policy?.maxRowsPerRun)}`, '',
    `## ${tr ? 'Yürütme Modülleri' : 'Execution Modules'}`, '',
    ...Object.entries(plan.modules ?? {}).map(([role, module]) => `- ${role}: ${module.kind ?? '—'} · ${module.versionUuid ?? '—'} · ${(module.contentHash ?? '').slice(0, 12)} · ${JSON.stringify(module.options ?? {})}`), '',
    `## ${tr ? 'Adımlar' : 'Steps'}`, '', `| # | ${tr ? 'Adım' : 'Step'} | ${tr ? 'Konum' : 'Site'} | ${tr ? 'İşlem' : 'Operation'} |`, '|---|---|---|---|',
    ...plan.steps.map((step, index) => `| ${index + 1} | ${step.id} | ${step.site} | ${step.operation} — ${stepLabel(step.operation, tr)} |`), '',
    ...(preview.sqlPreview?.length ? [`## ${tr ? 'Çalıştırılacak SQL' : 'SQL to Execute'}`, '', ...preview.sqlPreview.flatMap((statement) => [`### ${statement.step} · ${statement.site}${statement.owner ? ` · ${statement.owner}` : ''}`, '', '```sql', statement.sql, '```', ''])] : []),
  ]
  return lines.join('\n')
}

function download(name: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = name; anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function PreRunReportBody({ preview, definitionName, environmentName, tr, connections }: { preview: PreRunPreview; definitionName: string; environmentName: string; tr: boolean; connections?: ConnectionUse[] }) {
  const { plan } = preview
  const sources = (plan.bindings ?? []).filter((item) => item.role === 'KAYNAK')
  const target = (plan.bindings ?? []).find((item) => item.role === 'HEDEF')
  const integration = plan.modules?.integration?.options ?? {}
  const writeMode = String(integration.WRITE_MODE ?? 'ATOMIC_DELETE_INSERT')
  const nonReversible = Boolean(plan.staging?.nonReversibleDdl)
  const metrics: SummaryMetric[] = [
    { label: tr ? 'Kaynak' : 'Source', value: sources.map(qualified).join(', ') || '—', hint: connections?.find((use) => use.role === 'SOURCE')?.connection?.name ?? (tr ? 'Yalnız SELECT' : 'SELECT only'), icon: <DatabaseZap size={18} />, tone: 'teal' },
    { label: 'Staging', value: plan.staging?.owner ?? '—', hint: [plan.staging?.prefixes?.loading, plan.staging?.prefixes?.integration, plan.staging?.prefixes?.error].filter(Boolean).join(' · '), icon: <Layers3 size={18} />, tone: 'neutral' },
    { label: tr ? 'Hedef' : 'Target', value: qualified(target), hint: connections?.find((use) => use.role === 'TARGET')?.connection?.name ?? (tr ? 'Yazılır' : 'Written'), icon: <Target size={18} />, tone: 'info' },
    { label: tr ? 'Yazma Modu' : 'Write Mode', value: writeMode, hint: nonReversible ? (tr ? 'Geri alınamaz DDL' : 'Non-reversible DDL') : (tr ? 'İşlem içinde' : 'Transactional'), icon: nonReversible ? <AlertTriangle size={18} /> : <ShieldCheck size={18} />, tone: nonReversible ? 'danger' : 'success' },
    { label: tr ? 'Satır Sınırı' : 'Row Limit', value: number(plan.options?.maxRows), hint: `${bytes(plan.options?.maxBytes)} · batch ${number(plan.options?.batchRows)}`, icon: <Settings2 size={18} />, tone: 'warning' },
    { label: tr ? 'Plan Özeti' : 'Plan Hash', value: <code>{plan.physicalPlanHash.slice(0, 12)}</code>, hint: `${plan.steps.length} ${tr ? 'adım' : 'steps'} · ${plan.columns?.length ?? 0} ${tr ? 'kolon' : 'columns'}`, icon: <Hash size={18} />, tone: 'pink' },
  ]
  return <div className="prerun-report">
    <div className={`definition-notice ${preview.executionVerified ? 'definition-notice--success' : 'definition-notice--info'}`} role="status"><ScanSearch size={16} aria-hidden="true" /><span>{preview.message}</span></div>
    <SummaryStrip ariaLabel={tr ? 'Rapor özeti' : 'Report summary'} items={metrics} />
    {connections && connections.length > 0 && <ConnectionsSection uses={connections} tr={tr} />}
    {nonReversible && <div className="definition-notice definition-notice--error" role="alert"><AlertTriangle size={16} aria-hidden="true" /><span>{tr ? `Hedef ${qualified(target)} önce TRUNCATE TABLE ile boşaltılır; bu adım örtük commit yapar ve geri alınamaz. Yükleme yarıda kalırsa hedef boş kalabilir.` : `Target ${qualified(target)} is emptied with TRUNCATE TABLE first; this commits implicitly and cannot be rolled back. If the load fails midway the target may stay empty.`}</span></div>}
    <section className="prerun-section">
      <h4><ListOrdered size={15} aria-hidden="true" />{tr ? 'Adımlar' : 'Steps'}</h4>
      <DataGrid viewControls={false} className="prerun-grid">
        <thead><tr><th scope="col">#</th><th scope="col">{tr ? 'Adım' : 'Step'}</th><th scope="col">{tr ? 'Konum' : 'Site'}</th><th scope="col">{tr ? 'İşlem' : 'Operation'}</th><th scope="col">{tr ? 'Açıklama' : 'Description'}</th></tr></thead>
        <tbody>{plan.steps.map((step, index) => <tr key={`${step.site}:${step.id}`}>
          <th scope="row"><span className="procedure-step-badge">{index + 1}</span></th>
          <td><strong>{step.id}</strong></td>
          <td><span className={`procedure-route-chip is-ready procedure-route-chip--${step.site === 'TARGET' ? 'target' : 'source'}`}>{step.site === 'STAGING' ? 'Staging' : step.site === 'TARGET' ? (tr ? 'Hedef' : 'Target') : step.site}</span></td>
          <td><code>{step.operation}</code></td>
          <td>{stepLabel(step.operation, tr)}</td>
        </tr>)}</tbody>
      </DataGrid>
    </section>
    <section className="prerun-section">
      <h4><Database size={15} aria-hidden="true" />{tr ? 'Nesneler ve Modüller' : 'Objects and Modules'}</h4>
      <dl className="prerun-facts">
        {(plan.bindings ?? []).map((binding) => <div key={binding.nodeCode}><dt>{binding.role === 'KAYNAK' ? (tr ? 'Kaynak' : 'Source') : (tr ? 'Hedef' : 'Target')} · {binding.nodeCode}</dt><dd>{qualified(binding)}<small>{tr ? 'snapshot' : 'snapshot'} {(binding.schemaSnapshotFingerprint ?? '').slice(0, 12)}</small></dd></div>)}
        {Object.entries(plan.modules ?? {}).map(([role, module]) => <div key={role}><dt>{module.kind ?? role}</dt><dd>{(module.versionUuid ?? '').slice(0, 8)} · {(module.contentHash ?? '').slice(0, 12)}<small>{Object.entries(module.options ?? {}).map(([key, value]) => `${key}=${String(value)}`).join(' · ') || '—'}</small></dd></div>)}
        <div><dt>{tr ? 'Çalışma Alanı Politikası' : 'Work Area Policy'}</dt><dd>{plan.staging?.workAreaPolicy?.policy?.enabled ? (tr ? 'Açık' : 'Enabled') : (tr ? 'Kapalı' : 'Disabled')}<small>allowSameSchema={String(Boolean(plan.staging?.workAreaPolicy?.policy?.allowSameSchema))} · maxRowsPerRun={number(plan.staging?.workAreaPolicy?.policy?.maxRowsPerRun)}</small></dd></div>
      </dl>
    </section>
    {preview.sqlPreview && preview.sqlPreview.length > 0 && <section className="prerun-section prerun-section--sql">
      <h4><Code2 size={15} aria-hidden="true" />{tr ? 'Çalıştırılacak SQL' : 'SQL to Execute'} <span className="procedure-heading-count">{preview.sqlPreview.length}</span><small className="prerun-sql-note">{tr ? 'Bağlar (?) çalıştırmada değer alır; çalışma tablosu adı koşu başına üretilir.' : 'Binds (?) are filled at run time; the work table name is generated per run.'}</small></h4>
      <ol className="prerun-sql-list">{preview.sqlPreview.map((statement, index) => <li key={index} className={`prerun-sql prerun-sql--${statement.site.toLowerCase()}`}>
        <div className="prerun-sql-head"><span className={`procedure-route-chip is-ready procedure-route-chip--${statement.site === 'SOURCE' ? 'source' : 'target'}`}>{statement.site === 'SOURCE' ? (tr ? 'Kaynak' : 'Source') : statement.site === 'STAGING' ? 'Staging' : (tr ? 'Hedef' : 'Target')}</span><strong>{stepLabel(statement.step, tr)}</strong>{statement.owner && <code>{statement.owner}</code>}</div>
        <pre><code>{statement.sql}</code></pre>
      </li>)}</ol>
    </section>}
    <p className="prerun-meta"><FileText size={14} aria-hidden="true" />{definitionName} · {environmentName} · {tr ? 'plan' : 'plan'} v{plan.planVersion ?? 1} · {plan.language}</p>
  </div>
}

/** ODI-style "simulate before run": compiles the physical plan without touching any database, shows it as a standard report and lets the user download it. */
export function PreRunReport({ projectUuid, scenarioUuid, environmentUuid, environmentName, definitionName, tr, onReady }: {
  projectUuid: string; scenarioUuid: string; environmentUuid: string; environmentName: string; definitionName: string; tr: boolean; onReady: (hash: string) => void
}) {
  const key = `${projectUuid}:${scenarioUuid}:${environmentUuid}`
  const latest = useRef(key); latest.current = key
  const [result, setResult] = useState<{ key: string; response: PreRunPreview; at: Date; connections: ConnectionUse[] } | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  async function preview() {
    setBusy(true); setResult(null)
    try {
      const [response, connections, physical] = await Promise.all([operationsApi.previewStagedPlan(projectUuid, scenarioUuid, environmentUuid) as Promise<PreRunPreview>, topologyApi.listConnections(projectUuid).catch(() => [] as Connection[]), topologyApi.listPhysicalSchemas(projectUuid).catch(() => [] as PhysicalSchema[])])
      if (latest.current === key) { setResult({ key, response, at: new Date(), connections: connectionUses(response.plan, connections, physical, tr) }); setOpen(true); onReady(response.plan.physicalPlanHash) }
    } catch (reason) { if (latest.current === key) notifyFeedback(reason instanceof Error ? reason.message : (tr ? 'Plan önizlemesi başarısız' : 'Plan preview failed'), 'error') }
    finally { setBusy(false) }
  }
  const visible = result?.key === key ? result : null
  const stem = `${definitionName.replace(/[^\p{L}\p{N}_-]+/gu, '_')}-${environmentName.replace(/[^\p{L}\p{N}_-]+/gu, '_')}-prerun`
  return <div className="prerun-actions">
    <Button type="button" tone="secondary" icon={<ScanSearch size={16} />} busy={busy} disabled={!environmentUuid} onClick={() => void preview()}>{tr ? 'Simüle Et' : 'Simulate'}</Button>
    {visible && <>
      <Button type="button" tone="ghost" icon={<FileText size={16} />} onClick={() => setOpen(true)}>{tr ? 'Raporu Aç' : 'Open Report'}</Button>
      <span className={`prerun-status ${visible.response.plan.staging?.nonReversibleDdl ? 'is-warning' : 'is-ready'}`}>{visible.response.plan.staging?.nonReversibleDdl ? <AlertTriangle size={14} aria-hidden="true" /> : <ShieldCheck size={14} aria-hidden="true" />}{tr ? 'Plan hazır' : 'Plan ready'} · <code>{visible.response.plan.physicalPlanHash.slice(0, 8)}</code></span>
    </>}
    <RecordDetailDialog open={open && Boolean(visible)} onClose={() => setOpen(false)} className="prerun-dialog" title={<span className="prerun-title"><ScanSearch size={18} aria-hidden="true" />{tr ? 'Çalıştırma Öncesi Rapor' : 'Pre-Run Report'}<small>{definitionName} · {environmentName}</small></span>}>
      {visible && <>
        <div className="prerun-toolbar">
          <Button type="button" tone="secondary" icon={<Download size={15} />} onClick={() => download(`${stem}.md`, preRunReportMarkdown(visible.response, { definitionName, environmentName, tr, generatedAt: visible.at, connections: visible.connections }), 'text/markdown;charset=utf-8')}>{tr ? 'Raporu İndir (.md)' : 'Download Report (.md)'}</Button>
          <Button type="button" tone="ghost" icon={<FileJson size={15} />} onClick={() => download(`${stem}.json`, JSON.stringify(visible.response, null, 2), 'application/json')}>{tr ? 'Planı İndir (.json)' : 'Download Plan (.json)'}</Button>
          <Button type="button" tone="ghost" icon={<X size={15} />} onClick={() => setOpen(false)}>{tr ? 'Kapat' : 'Close'}</Button>
        </div>
        <PreRunReportBody preview={visible.response} definitionName={definitionName} environmentName={environmentName} tr={tr} connections={visible.connections} />
      </>}
    </RecordDetailDialog>
  </div>
}
