import { useState } from 'react'
import { AlertTriangle, Code2, Database, DatabaseZap, Download, FileJson, FileText, GitBranch, Layers, ListOrdered, ScanSearch, ShieldCheck, Workflow, X } from 'lucide-react'
import { Button } from '../../core/ui/Button'
import { SummaryStrip, type SummaryMetric } from '../../core/ui/SummaryStrip'
import { RecordDetailDialog } from '../../core/ui/RecordDetailDialog'
import { DataGrid } from '../../core/ui/DataGrid'
import { notifyFeedback } from '../../core/api/networkFeedback'
import { topologyApi, type Connection, type Environment, type LogicalSchema, type PhysicalSchema, type SchemaBinding } from '../topology/api'
import { definitionsApi } from './api'
import { DefinitionTypeIcon } from './DefinitionTypeIcon'
import { useDefinitionsI18n } from './i18n'
import { isPackageContent, packageValidation, type PackageContent, type PackageStep, type TransitionOutcome } from './packageGraph'
import type { Definition, ProcedureContent, ProcedureTask } from './types'
import { ConnectionsSection, connectionsMarkdown, connectionEndpoint, type ConnectionUse } from './ConnectionsSection'
import './pre-run-report.css'

/* ---------- shared pieces ---------- */
export interface SimulationStatement { step: string; site: 'SOURCE' | 'TARGET' | 'STAGING'; owner: string; sql: string; note?: string }
interface Topology { environments: Environment[]; bindings: SchemaBinding[]; logical: LogicalSchema[]; physical: PhysicalSchema[]; connections: Connection[] }

function download(name: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = name; anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
const stem = (definitionName: string, environmentName: string) => `${definitionName.replace(/[^\p{L}\p{N}_-]+/gu, '_')}-${environmentName.replace(/[^\p{L}\p{N}_-]+/gu, '_')}-prerun`
const DDL = /^\s*(TRUNCATE|DROP|ALTER|CREATE|GRANT|REVOKE)\b/i
const WRITE = /^\s*(INSERT|UPDATE|DELETE|MERGE|BEGIN|CALL|EXEC)/i
/** Light SQL layout for the report: keywords on their own line, projections one per line; never changes the statement text. */
export function prettySql(sql: string) {
  let text = sql.trim()
  if (!/^\s*(SELECT|INSERT|UPDATE|DELETE|MERGE)\b/i.test(text)) return text
  let depth = 0, literal = false, out = ''
  const keywords = [' FROM ', ' WHERE ', ' LEFT JOIN ', ' RIGHT JOIN ', ' FULL OUTER JOIN ', ' INNER JOIN ', ' JOIN ', ' GROUP BY ', ' ORDER BY ', ' HAVING ', ' UNION ALL ', ' UNION ', ' VALUES ', ' SET ', ' AND ', ' OR ', ' ON ']
  text = text.replace(/\s+/g, ' ')
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!
    if (c === "'") literal = !literal
    if (!literal) {
      if (c === '(') depth++; else if (c === ')') depth--
      if (c === ',' && depth <= 1) { out += ',\n\t'; continue }
      if (depth === 0) {
        const upper = text.slice(i).toUpperCase()
        const keyword = keywords.find((item) => upper.startsWith(item))
        if (keyword) { const word = keyword.trim(); out += (word === 'AND' || word === 'OR' || word === 'ON' ? '\n\t' : '\n') + word + ' '; i += keyword.length - 1; continue }
      }
    }
    out += c
  }
  return out.replace(/^(SELECT( DISTINCT)?|INSERT INTO [^(]+\(|UPDATE) /i, (match) => match.trimEnd() + '\n\t')
}

async function loadTopology(projectUuid: string): Promise<Topology> {
  const [environments, bindings, logical, physical, connections] = await Promise.all([
    topologyApi.listEnvironments(projectUuid), topologyApi.listBindings(projectUuid), topologyApi.listLogicalSchemas(projectUuid), topologyApi.listPhysicalSchemas(projectUuid), topologyApi.listConnections(projectUuid),
  ])
  return { environments, bindings, logical, physical, connections }
}
function resolveSchema(topology: Topology, environmentUuid: string, logicalSchemaUuid?: string) {
  const logical = topology.logical.find((item) => item.uuid === logicalSchemaUuid)
  const binding = topology.bindings.find((item) => item.logicalSchemaUuid === logicalSchemaUuid && item.environmentUuid === environmentUuid)
  const physical = topology.physical.find((item) => item.uuid === binding?.physicalSchemaUuid)
  const connection = topology.connections.find((item) => item.uuid === physical?.connectionUuid)
  return { logical, physical, connection, owner: physical?.schemaName ?? '' }
}

function SqlList({ statements, tr }: { statements: SimulationStatement[]; tr: boolean }) {
  return <section className="prerun-section prerun-section--sql">
    <h4><Code2 size={15} aria-hidden="true" />{tr ? 'Çalıştırılacak SQL' : 'SQL to Execute'} <span className="procedure-heading-count">{statements.length}</span><small className="prerun-sql-note">{tr ? 'Adım sırasına göre; bağlar ve değişkenler çalıştırmada değer alır.' : 'In step order; binds and variables are resolved at run time.'}</small></h4>
    <ol className="prerun-sql-list">{statements.map((statement, index) => <li key={index} className={`prerun-sql prerun-sql--${statement.site.toLowerCase()}`}>
      <div className="prerun-sql-head"><span className={`procedure-route-chip is-ready procedure-route-chip--${statement.site === 'SOURCE' ? 'source' : 'target'}`}>{statement.site === 'SOURCE' ? (tr ? 'Kaynak' : 'Source') : statement.site === 'STAGING' ? 'Staging' : (tr ? 'Hedef' : 'Target')}</span><strong>{statement.step}</strong>{statement.note && <em className="prerun-sql-flag"><AlertTriangle size={12} aria-hidden="true" />{statement.note}</em>}{statement.owner && <code>{statement.owner}</code>}</div>
      <pre><code>{statement.sql}</code></pre>
    </li>)}</ol>
  </section>
}
function ReportDialog({ open, onClose, title, subtitle, markdown, json, fileStem, children, tr }: { open: boolean; onClose(): void; title: string; subtitle: string; markdown: string; json: unknown; fileStem: string; children: React.ReactNode; tr: boolean }) {
  return <RecordDetailDialog open={open} onClose={onClose} className="prerun-dialog" title={<span className="prerun-title"><ScanSearch size={18} aria-hidden="true" />{title}<small>{subtitle}</small></span>}>
    <div className="prerun-toolbar">
      <Button type="button" tone="secondary" icon={<Download size={15} />} onClick={() => download(`${fileStem}.md`, markdown, 'text/markdown;charset=utf-8')}>{tr ? 'Raporu İndir (.md)' : 'Download Report (.md)'}</Button>
      <Button type="button" tone="ghost" icon={<FileJson size={15} />} onClick={() => download(`${fileStem}.json`, JSON.stringify(json, null, 2), 'application/json')}>{tr ? 'Planı İndir (.json)' : 'Download Plan (.json)'}</Button>
      <Button type="button" tone="ghost" icon={<X size={15} />} onClick={onClose}>{tr ? 'Kapat' : 'Close'}</Button>
    </div>
    {children}
  </RecordDetailDialog>
}
function SimulateActions({ busy, ready, warning, onSimulate, onOpen, tr }: { busy: boolean; ready: boolean; warning: boolean; onSimulate(): void; onOpen(): void; tr: boolean }) {
  return <div className="prerun-actions">
    <Button type="button" tone="secondary" icon={<ScanSearch size={16} />} busy={busy} onClick={onSimulate}>{tr ? 'Simüle Et' : 'Simulate'}</Button>
    {ready && <>
      <Button type="button" tone="ghost" icon={<FileText size={16} />} onClick={onOpen}>{tr ? 'Raporu Aç' : 'Open Report'}</Button>
      <span className={`prerun-status ${warning ? 'is-warning' : 'is-ready'}`}>{warning ? <AlertTriangle size={14} aria-hidden="true" /> : <ShieldCheck size={14} aria-hidden="true" />}{tr ? 'Plan hazır' : 'Plan ready'}</span>
    </>}
  </div>
}

/* ---------- procedure ---------- */
export interface ProcedureSimulation {
  environment: Environment
  technology: { source?: string; target?: string }
  steps: Array<{ index: number; task: ProcedureTask; enabled: boolean; target: ReturnType<typeof resolveSchema> | null; source: ReturnType<typeof resolveSchema> | null; ddl: boolean }>
  statements: SimulationStatement[]
  connections: ConnectionUse[]
  issues: string[]
}
export function simulateProcedure(content: ProcedureContent, environment: Environment, topology: Topology, tr: boolean): ProcedureSimulation {
  const units = new Map<string, { target?: ProcedureTask; source?: ProcedureTask; order: number }>()
  content.tasks.forEach((task, order) => {
    const key = task.input?.fromTask ?? task.id
    const unit = units.get(key) ?? { order }
    if (task.connectionRole === 'SOURCE') unit.source = task; else unit.target = task
    units.set(key, unit)
  })
  const issues: string[] = []
  const statements: SimulationStatement[] = []
  const steps = [...units.values()].sort((a, b) => a.order - b.order).map((unit, index) => {
    const primary = unit.target ?? unit.source!
    const enabled = primary.enabled !== false
    const target = unit.target ? resolveSchema(topology, environment.uuid, unit.target.logicalSchemaUuid) : null
    const source = unit.source ? resolveSchema(topology, environment.uuid, unit.source.logicalSchemaUuid) : null
    const name = primary.name || primary.id
    if (enabled && unit.target?.logicalSchemaUuid && !target?.physical) issues.push(tr ? `${name}: hedef mantıksal şema bu ortamda fiziksel şemaya bağlı değil.` : `${name}: target logical schema is not bound to a physical schema in this environment.`)
    if (enabled && unit.source?.logicalSchemaUuid && !source?.physical) issues.push(tr ? `${name}: kaynak mantıksal şema bu ortamda fiziksel şemaya bağlı değil.` : `${name}: source logical schema is not bound to a physical schema in this environment.`)
    const ddl = Boolean(unit.target && DDL.test(unit.target.command))
    if (enabled && unit.source?.command) statements.push({ step: `${index + 1} · ${name}`, site: 'SOURCE', owner: source?.connection ? `${source.connection.name} · ${source.owner}` : source?.owner ?? '', sql: prettySql(unit.source.command), note: WRITE.test(unit.source.command) || DDL.test(unit.source.command) ? (tr ? 'Kaynakta yazma/DDL — salt okunur oturumda reddedilir' : 'Write/DDL on source — rejected in the read-only session') : undefined })
    if (enabled && unit.target?.command) statements.push({ step: `${index + 1} · ${name}`, site: 'TARGET', owner: target?.connection ? `${target.connection.name} · ${target.owner}` : target?.owner ?? '', sql: prettySql(unit.target.command), note: ddl ? (tr ? 'Geri alınamaz DDL' : 'Non-reversible DDL') : undefined })
    return { index: index + 1, task: primary, enabled, target, source, ddl }
  })
  if (!steps.some((step) => step.enabled)) issues.push(tr ? 'Çalıştırılacak adım yok (tümü devre dışı).' : 'No step will execute (all disabled).')
  const connections: ConnectionUse[] = []
  const seenUse = new Set<string>()
  for (const step of steps) {
    if (!step.enabled) continue
    for (const [role, resolved] of [['TARGET', step.target], ['SOURCE', step.source]] as const) {
      if (!resolved || !resolved.logical) continue
      const id = `${role}:${resolved.physical?.uuid ?? resolved.logical.uuid}`
      if (seenUse.has(id)) continue
      seenUse.add(id)
      connections.push({ role, label: `${role === 'SOURCE' ? (tr ? 'Kaynak' : 'Source') : (tr ? 'Hedef' : 'Target')} · ${resolved.logical.name}`, connection: resolved.connection, physical: resolved.physical, owner: resolved.owner, readOnly: role === 'SOURCE' })
    }
  }
  return { environment, technology: content.technology ?? {}, steps, statements, connections, issues }
}
function procedureMarkdown(name: string, sim: ProcedureSimulation, tr: boolean) {
  const lines = [`# ${tr ? 'Çalıştırma Öncesi Rapor' : 'Pre-Run Report'} — ${name}`, '', `- ${tr ? 'Ortam' : 'Environment'}: ${sim.environment.name} · ${sim.environment.code}`, `- ${tr ? 'Teknoloji' : 'Technology'}: ${tr ? 'kaynak' : 'source'} ${sim.technology.source ?? '—'} · ${tr ? 'hedef' : 'target'} ${sim.technology.target ?? '—'}`, `- ${tr ? 'Üretildi' : 'Generated'}: ${new Date().toISOString()}`, '',
    `## ${tr ? 'Adımlar' : 'Steps'}`, '', `| # | ${tr ? 'Adım' : 'Step'} | ${tr ? 'Çalıştır' : 'Execute'} | ${tr ? 'Hata Yoksay' : 'Ignore Errors'} | ${tr ? 'Hedef' : 'Target'} | ${tr ? 'Kaynak' : 'Source'} | ${tr ? 'İşlem' : 'Transaction'} |`, '|---|---|---|---|---|---|---|',
    ...sim.steps.map((step) => `| ${step.index} | ${step.task.name || step.task.id} | ${step.enabled ? '✓' : '—'} | ${step.task.onError === 'CONTINUE' ? '✓' : '—'} | ${step.target?.owner || '—'}${step.target?.connection ? ` (${step.target.connection.name})` : ''} | ${step.source?.owner || '—'}${step.source?.connection ? ` (${step.source.connection.name})` : ''} | ${step.task.transactionMode ?? 'AUTOCOMMIT'}${step.task.commitMode ? ' · ' + step.task.commitMode : ''} |`), '',
    ...connectionsMarkdown(sim.connections, tr),
    ...(sim.issues.length ? [`## ${tr ? 'Uyarılar' : 'Warnings'}`, '', ...sim.issues.map((issue) => `- ${issue}`), ''] : []),
    `## ${tr ? 'Çalıştırılacak SQL' : 'SQL to Execute'} (${sim.statements.length})`, '',
    ...sim.statements.flatMap((statement) => [`### ${statement.step} · ${statement.site}${statement.owner ? ` · ${statement.owner}` : ''}${statement.note ? ` — ${statement.note}` : ''}`, '', '```sql', statement.sql, '```', '']),
  ]
  return lines.join('\n')
}
export function ProcedureSimulationReport({ projectUuid, content, environmentUuid, definitionName, onReady }: { projectUuid: string; content: ProcedureContent; environmentUuid: string; definitionName: string; onReady?(): void }) {
  const { language } = useDefinitionsI18n(); const tr = language === 'tr'
  const [sim, setSim] = useState<ProcedureSimulation | null>(null)
  const [busy, setBusy] = useState(false); const [open, setOpen] = useState(false)
  async function simulate() {
    setBusy(true)
    try {
      const topology = await loadTopology(projectUuid)
      const environment = topology.environments.find((item) => item.uuid === environmentUuid)
      if (!environment) throw new Error(tr ? 'Ortam bulunamadı.' : 'Environment not found.')
      setSim(simulateProcedure(content, environment, topology, tr)); setOpen(true); onReady?.()
    } catch (reason) { notifyFeedback(reason instanceof Error ? reason.message : String(reason), 'error') }
    finally { setBusy(false) }
  }
  const ddlCount = sim?.steps.filter((step) => step.enabled && step.ddl).length ?? 0
  return <>
    <SimulateActions busy={busy} ready={Boolean(sim)} warning={Boolean(sim && (sim.issues.length || ddlCount))} onSimulate={() => void simulate()} onOpen={() => setOpen(true)} tr={tr} />
    {sim && <ReportDialog open={open} onClose={() => setOpen(false)} tr={tr} title={tr ? 'Çalıştırma Öncesi Rapor' : 'Pre-Run Report'} subtitle={`${definitionName} · ${sim.environment.name}`} markdown={procedureMarkdown(definitionName, sim, tr)} json={{ definition: definitionName, environment: sim.environment, steps: sim.steps.map((step) => ({ index: step.index, task: step.task, target: step.target?.owner, source: step.source?.owner })), statements: sim.statements, issues: sim.issues }} fileStem={stem(definitionName, sim.environment.name)}>
      <div className="prerun-report">
        <div className="definition-notice definition-notice--info" role="status"><ScanSearch size={16} aria-hidden="true" /><span>{tr ? 'Plan tanımdan ve ortam bağlarından üretildi. Canlı DB, şema ve yetki kontrolleri çalıştırmada ayrıca yapılır.' : 'Plan derived from the definition and environment bindings. Live DB, schema and privilege checks happen at run time.'}</span></div>
        <SummaryStrip ariaLabel={tr ? 'Rapor özeti' : 'Report summary'} items={[
          { label: tr ? 'Ortam' : 'Environment', value: sim.environment.name, hint: sim.environment.code, icon: <Layers size={18} />, tone: 'teal' },
          { label: tr ? 'Adımlar' : 'Steps', value: String(sim.steps.length), hint: `${sim.steps.filter((step) => step.enabled).length} ${tr ? 'çalışacak' : 'will run'}`, icon: <ListOrdered size={18} />, tone: 'info' },
          { label: tr ? 'Hedef' : 'Target', value: [...new Set(sim.connections.filter((use) => use.role === 'TARGET').map((use) => use.connection?.name ?? use.owner))].join(', ') || '—', hint: [...new Set(sim.steps.map((step) => step.target?.owner).filter(Boolean))].join(', ') || (sim.technology.target ?? '—'), icon: <Database size={18} />, tone: 'info' },
          { label: tr ? 'Kaynak' : 'Source', value: [...new Set(sim.connections.filter((use) => use.role === 'SOURCE').map((use) => use.connection?.name ?? use.owner))].join(', ') || '—', hint: [...new Set(sim.steps.map((step) => step.source?.owner).filter(Boolean))].join(', ') || (tr ? 'Kaynak komutu yok' : 'No source command'), icon: <DatabaseZap size={18} />, tone: 'teal' },
          { label: tr ? 'DDL' : 'DDL', value: String(ddlCount), hint: ddlCount ? (tr ? 'Geri alınamaz adım' : 'Non-reversible steps') : (tr ? 'DDL yok' : 'No DDL'), icon: ddlCount ? <AlertTriangle size={18} /> : <ShieldCheck size={18} />, tone: ddlCount ? 'danger' : 'success' } satisfies SummaryMetric,
          { label: tr ? 'Uyarı' : 'Warnings', value: String(sim.issues.length), hint: sim.issues.length ? (tr ? 'Aşağıda listelendi' : 'Listed below') : (tr ? 'Sorun yok' : 'None'), icon: sim.issues.length ? <AlertTriangle size={18} /> : <ShieldCheck size={18} />, tone: sim.issues.length ? 'warning' : 'success' },
        ]} />
        <ConnectionsSection uses={sim.connections} tr={tr} />
        {sim.issues.map((issue) => <div key={issue} className="definition-notice definition-notice--error" role="alert"><AlertTriangle size={16} aria-hidden="true" /><span>{issue}</span></div>)}
        <section className="prerun-section">
          <h4><ListOrdered size={15} aria-hidden="true" />{tr ? 'Adımlar' : 'Steps'}</h4>
          <DataGrid viewControls={false} className="prerun-grid">
            <thead><tr><th scope="col">#</th><th scope="col">{tr ? 'Adım' : 'Step'}</th><th scope="col">{tr ? 'Çalıştır' : 'Execute'}</th><th scope="col">{tr ? 'Hata Yoksay' : 'Ignore Errors'}</th><th scope="col">{tr ? 'Hedef Şema' : 'Target Schema'}</th><th scope="col">{tr ? 'Kaynak Şema' : 'Source Schema'}</th><th scope="col">{tr ? 'İşlem' : 'Transaction'}</th></tr></thead>
            <tbody>{sim.steps.map((step) => <tr key={step.task.id} className={step.enabled ? '' : 'is-disabled'}>
              <th scope="row"><span className="procedure-step-badge">{step.index}</span></th>
              <td><strong>{step.task.name || step.task.id}</strong></td>
              <td>{step.enabled ? '✓' : '—'}</td>
              <td>{step.task.onError === 'CONTINUE' ? '✓' : '—'}</td>
              <td>{step.target?.owner ? <><code>{step.target.owner}</code><small className="prerun-cell-hint">{step.target.connection?.name} · {connectionEndpoint(step.target.connection)}</small></> : '—'}</td>
              <td>{step.source?.owner ? <><code>{step.source.owner}</code><small className="prerun-cell-hint">{step.source.connection?.name} · {connectionEndpoint(step.source.connection)}</small></> : '—'}</td>
              <td>{step.task.transactionMode ?? 'AUTOCOMMIT'}{step.task.commitMode ? ` · ${step.task.commitMode}` : ''}</td>
            </tr>)}</tbody>
          </DataGrid>
        </section>
        <SqlList statements={sim.statements} tr={tr} />
      </div>
    </ReportDialog>}
  </>
}

/* ---------- package ---------- */
export interface PackageSimulation {
  environment: Environment
  path: Array<{ index: number; step: PackageStep; definition?: Definition; onFailure?: string; onSuccess?: string }>
  transitions: Array<{ from: string; to: string; outcome: TransitionOutcome }>
  issues: string[]
}
export function simulatePackage(content: PackageContent, environment: Environment, definitions: Definition[], tr: boolean) {
  const byId = new Map(content.steps.map((step) => [step.id, step]))
  const label = (id: string) => byId.get(id)?.name || id
  const next = (from: string, outcomes: TransitionOutcome[]) => content.transitions.find((edge) => edge.fromStepId === from && outcomes.includes(edge.outcome ?? 'ALWAYS'))
  const issues = packageValidation(content).map((code) => tr ? ({ START_REQUIRED: 'Başlangıç adımı tanımlı değil.', DUPLICATE_ID: 'Adım kimlikleri tekrar ediyor.', MISSING_REFERENCE: 'Bir geçiş var olmayan adıma işaret ediyor.', DUPLICATE_OUTCOME: 'Aynı sonuç için iki geçiş var.', INVALID_OUTCOME: 'Adım türüne uymayan geçiş sonucu.', CYCLE: 'Geçişler döngü oluşturuyor.', UNREACHABLE_STEP: 'Başlangıçtan ulaşılamayan adım var.' } as Record<string, string>)[code] ?? code : code)
  const path: PackageSimulation['path'] = []
  const seen = new Set<string>()
  let current: string | undefined = content.firstStepId
  while (current && byId.has(current) && !seen.has(current)) {
    seen.add(current)
    const step = byId.get(current)!
    const definition = definitions.find((item) => item.uuid === step.definitionUuid)
    if (!definition) issues.push(tr ? `${label(current)}: bağlı nesne bulunamadı.` : `${label(current)}: linked object not found.`)
    const success = next(current, step.type === 'VARIABLE_EVALUATE' ? ['TRUE', 'ALWAYS'] : ['SUCCESS', 'ALWAYS'])
    const failure = next(current, step.type === 'VARIABLE_EVALUATE' ? ['FALSE', 'FAILURE'] : ['FAILURE'])
    path.push({ index: path.length + 1, step, definition, onSuccess: success ? label(success.toStepId) : undefined, onFailure: failure ? label(failure.toStepId) : undefined })
    current = success?.toStepId
  }
  const transitions = content.transitions.map((edge) => ({ from: label(edge.fromStepId), to: label(edge.toStepId), outcome: edge.outcome ?? 'ALWAYS' as TransitionOutcome }))
  return { environment, path, transitions, issues: [...new Set(issues)] } satisfies PackageSimulation
}
function packageMarkdown(name: string, sim: PackageSimulation, tr: boolean) {
  return [`# ${tr ? 'Çalıştırma Öncesi Rapor' : 'Pre-Run Report'} — ${name}`, '', `- ${tr ? 'Ortam' : 'Environment'}: ${sim.environment.name} · ${sim.environment.code}`, `- ${tr ? 'Üretildi' : 'Generated'}: ${new Date().toISOString()}`, '',
    `## ${tr ? 'Ana Akış' : 'Main Path'}`, '', `| # | ${tr ? 'Adım' : 'Step'} | ${tr ? 'Tür' : 'Type'} | ${tr ? 'Nesne' : 'Object'} | ${tr ? 'Başarıda' : 'On Success'} | ${tr ? 'Hatada' : 'On Failure'} |`, '|---|---|---|---|---|---|',
    ...sim.path.map((item) => `| ${item.index} | ${item.step.name || item.step.id} | ${item.step.type} | ${item.definition ? `${item.definition.name} (${item.definition.code})` : '—'} | ${item.onSuccess ?? (tr ? 'bitiş' : 'end')} | ${item.onFailure ?? (tr ? 'paket durur' : 'package stops')} |`), '',
    `## ${tr ? 'Tüm Geçişler' : 'All Transitions'}`, '', ...sim.transitions.map((edge) => `- ${edge.from} → ${edge.to} (${edge.outcome})`), '',
    ...(sim.issues.length ? [`## ${tr ? 'Uyarılar' : 'Warnings'}`, '', ...sim.issues.map((issue) => `- ${issue}`), ''] : [])].join('\n')
}
export function PackageSimulationReport({ projectUuid, content, environmentUuid, definitionName, onReady }: { projectUuid: string; content: unknown; environmentUuid: string; definitionName: string; onReady?(): void }) {
  const { language, t } = useDefinitionsI18n(); const tr = language === 'tr'
  const [sim, setSim] = useState<PackageSimulation | null>(null)
  const [busy, setBusy] = useState(false); const [open, setOpen] = useState(false)
  async function simulate() {
    setBusy(true)
    try {
      if (!isPackageContent(content)) throw new Error(tr ? 'Paket içeriği okunamadı.' : 'Package content is not readable.')
      const [environments, definitions] = await Promise.all([topologyApi.listEnvironments(projectUuid), definitionsApi.listDefinitions(projectUuid)])
      const environment = environments.find((item) => item.uuid === environmentUuid)
      if (!environment) throw new Error(tr ? 'Ortam bulunamadı.' : 'Environment not found.')
      setSim(simulatePackage(content, environment, definitions, tr)); setOpen(true); onReady?.()
    } catch (reason) { notifyFeedback(reason instanceof Error ? reason.message : String(reason), 'error') }
    finally { setBusy(false) }
  }
  return <>
    <SimulateActions busy={busy} ready={Boolean(sim)} warning={Boolean(sim?.issues.length)} onSimulate={() => void simulate()} onOpen={() => setOpen(true)} tr={tr} />
    {sim && <ReportDialog open={open} onClose={() => setOpen(false)} tr={tr} title={tr ? 'Çalıştırma Öncesi Rapor' : 'Pre-Run Report'} subtitle={`${definitionName} · ${sim.environment.name}`} markdown={packageMarkdown(definitionName, sim, tr)} json={sim} fileStem={stem(definitionName, sim.environment.name)}>
      <div className="prerun-report">
        <SummaryStrip ariaLabel={tr ? 'Rapor özeti' : 'Report summary'} items={[
          { label: tr ? 'Ortam' : 'Environment', value: sim.environment.name, hint: sim.environment.code, icon: <Layers size={18} />, tone: 'teal' },
          { label: tr ? 'Ana Akış' : 'Main Path', value: String(sim.path.length), hint: tr ? 'adım sırayla' : 'steps in order', icon: <ListOrdered size={18} />, tone: 'info' },
          { label: tr ? 'Geçişler' : 'Transitions', value: String(sim.transitions.length), hint: `${sim.transitions.filter((edge) => edge.outcome === 'FAILURE' || edge.outcome === 'FALSE').length} ${tr ? 'hata dalı' : 'failure branches'}`, icon: <GitBranch size={18} />, tone: 'neutral' },
          { label: tr ? 'Uyarı' : 'Warnings', value: String(sim.issues.length), hint: sim.issues.length ? (tr ? 'Aşağıda listelendi' : 'Listed below') : (tr ? 'Sorun yok' : 'None'), icon: sim.issues.length ? <AlertTriangle size={18} /> : <ShieldCheck size={18} />, tone: sim.issues.length ? 'warning' : 'success' },
        ]} />
        {sim.issues.map((issue) => <div key={issue} className="definition-notice definition-notice--error" role="alert"><AlertTriangle size={16} aria-hidden="true" /><span>{issue}</span></div>)}
        <section className="prerun-section">
          <h4><Workflow size={15} aria-hidden="true" />{tr ? 'Ana Akış' : 'Main Path'}</h4>
          <DataGrid viewControls={false} className="prerun-grid">
            <thead><tr><th scope="col">#</th><th scope="col">{tr ? 'Adım' : 'Step'}</th><th scope="col">{tr ? 'Nesne' : 'Object'}</th><th scope="col">{tr ? 'Başarıda' : 'On Success'}</th><th scope="col">{tr ? 'Hatada' : 'On Failure'}</th></tr></thead>
            <tbody>{sim.path.map((item) => <tr key={item.step.id}>
              <th scope="row"><span className="procedure-step-badge">{item.index}</span></th>
              <td><strong>{item.step.name || item.step.id}</strong></td>
              <td>{item.definition ? <span className="definition-type-chip"><DefinitionTypeIcon type={item.definition.type} size={12} />{item.definition.name} <code>{item.definition.code}</code></span> : <em>{t('unlinkedStep')}</em>}</td>
              <td>{item.onSuccess ?? <span className="definition-muted">{tr ? 'bitiş' : 'end'}</span>}</td>
              <td>{item.onFailure ?? <span className="definition-muted">{tr ? 'paket durur' : 'package stops'}</span>}</td>
            </tr>)}</tbody>
          </DataGrid>
        </section>
        <section className="prerun-section">
          <h4><GitBranch size={15} aria-hidden="true" />{tr ? 'Tüm Geçişler' : 'All Transitions'} <span className="procedure-heading-count">{sim.transitions.length}</span></h4>
          <ul className="prerun-transitions">{sim.transitions.map((edge, index) => <li key={index}><span className={`procedure-route-chip is-ready procedure-route-chip--${edge.outcome === 'FAILURE' || edge.outcome === 'FALSE' ? 'source' : 'target'}`}>{t(({ SUCCESS: 'outcomeSUCCESS', FAILURE: 'outcomeFAILURE', TRUE: 'outcomeTRUE', FALSE: 'outcomeFALSE', ALWAYS: 'outcomeALWAYS' } as const)[edge.outcome])}</span><strong>{edge.from}</strong><span aria-hidden="true">→</span><strong>{edge.to}</strong></li>)}</ul>
        </section>
      </div>
    </ReportDialog>}
  </>
}
