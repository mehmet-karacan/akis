import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import {
  Boxes, Cable, CheckCircle2, ChevronRight, CircleAlert, Database, Globe2,
  Layers3, Link2, LoaderCircle, Network, Plus, RefreshCw,
  Search, ServerCog, TableProperties, User, X,
} from 'lucide-react'
import { Dialog } from '../../core/ui/Dialog'
import {
  topologyApi,
  type Connection,
  type ConnectionVersion,
  type DataObject,
  type DiscoveryResult,
  type Environment,
  type LogicalSchema,
  type Model,
  type PhysicalSchema,
  type SchemaBinding,
  type Submodel,
} from './api'
import { getTopologyCopy, type CopyKey } from './copy'
import { DatabaseProviderIcon } from './DatabaseProviderIcon'
import { DiscoverySnapshotPanel } from './DiscoverySnapshotPanel'
import { OracleConnectionCreateForm } from './OracleConnectionCreateForm'
import './topology.css'

type Tab = 'connections' | 'schemas' | 'bindings' | 'catalog'
type FormName = 'connection' | 'physical' | 'logical' | 'environment' | 'binding' | 'model' | 'submodel' | 'object' | null

interface TopologyPageProps {
  projectUuid?: string
  initialTab?: Tab
}

interface Resources {
  connections: Connection[]
  physicalSchemas: PhysicalSchema[]
  logicalSchemas: LogicalSchema[]
  environments: Environment[]
  bindings: SchemaBinding[]
  models: Model[]
}

const emptyResources: Resources = {
  connections: [], physicalSchemas: [], logicalSchemas: [],
  environments: [], bindings: [], models: [],
}

function textValue(data: FormData, name: string) {
  return String(data.get(name) ?? '').trim()
}

function optionalValue(data: FormData, name: string) {
  const value = textValue(data, name)
  return value || undefined
}

function connectionVersionSummary(version: ConnectionVersion) {
  return version.mode === 'JNDI'
    ? version.jndiName ?? 'JNDI DataSource'
    : `${version.host ?? '—'}:${version.port ?? '—'}`
}

function Field({ label, name, children, optional = false }: { label: string; name: string; children?: ReactNode; optional?: boolean }) {
  return (
    <label className="topology-field">
      <span>{label} <small>{optional ? '(optional)' : '*'}</small></span>
      {children ?? <input name={name} required={!optional} />}
    </label>
  )
}

function Status({ value }: { value: string }) {
  const { i18n } = useTranslation()
  const normalized = value.toLowerCase()
  const translated = value === 'AKTIF' ? (i18n.language.startsWith('tr') ? 'Etkin' : 'Active') : value === 'TASLAK' ? (i18n.language.startsWith('tr') ? 'Taslak' : 'Draft') : value
  return <span className={`topology-status topology-status--${normalized}`}>{translated}</span>
}

function Empty({ children }: { children: ReactNode }) {
  return <div className="topology-empty"><Boxes aria-hidden="true" /><p>{children}</p></div>
}

function PanelHeading({ icon, title, count, action }: { icon: ReactNode; title: string; count?: number; action?: ReactNode }) {
  return (
    <div className="topology-panel-heading">
      <div><span className="topology-panel-icon">{icon}</span><h2>{title}</h2>{count !== undefined && <span className="topology-count">{count}</span>}</div>
      {action}
    </div>
  )
}

export function TopologyPage({ projectUuid: projectUuidProp, initialTab = 'connections' }: TopologyPageProps) {
  const params = useParams<{ projectUuid: string }>()
  const projectUuid = projectUuidProp ?? params.projectUuid ?? ''
  const { i18n } = useTranslation()
  const c = useMemo(() => getTopologyCopy(i18n.resolvedLanguage ?? i18n.language), [i18n.language, i18n.resolvedLanguage])
  const locale = (i18n.resolvedLanguage ?? i18n.language).startsWith('tr') ? 'tr-TR' : 'en-GB'
  const dateFormatter = useMemo(
    () => new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }),
    [locale],
  )
  const tr = useCallback((key: CopyKey) => c[key], [c])

  const [tab, setTab] = useState<Tab>(initialTab)
  const [form, setForm] = useState<FormName>(null)
  const [resources, setResources] = useState<Resources>(emptyResources)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [actionError, setActionError] = useState('')
  const [busy, setBusy] = useState('')
  const [selectedConnectionUuid, setSelectedConnectionUuid] = useState('')
  const [physicalConnectionUuid, setPhysicalConnectionUuid] = useState('')
  const [oracleSchemas, setOracleSchemas] = useState<string[]>([])
  const [schemasLoading, setSchemasLoading] = useState(false)
  const [schemaLoadError, setSchemaLoadError] = useState('')
  const [versions, setVersions] = useState<ConnectionVersion[]>([])
  const [connectionVersions, setConnectionVersions] = useState<Record<string, ConnectionVersion[]>>({})
  const [versionsLoading, setVersionsLoading] = useState(false)
  const [selectedVersionUuid, setSelectedVersionUuid] = useState('')
  const [selectedPhysicalUuid, setSelectedPhysicalUuid] = useState('')
  const [selectedModelUuid, setSelectedModelUuid] = useState('')
  const [submodels, setSubmodels] = useState<Submodel[]>([])
  const [dataObjects, setDataObjects] = useState<DataObject[]>([])
  const [catalogLoading, setCatalogLoading] = useState(false)
  const [discoveryResult, setDiscoveryResult] = useState<DiscoveryResult | null>(null)

  const loadAll = useCallback(async () => {
    if (!projectUuid) return
    setLoading(true)
    setLoadError('')
    try {
      const [connections, physicalSchemas, logicalSchemas, environments, bindings, models] = await Promise.all([
        topologyApi.listConnections(projectUuid),
        topologyApi.listPhysicalSchemas(projectUuid), topologyApi.listLogicalSchemas(projectUuid),
        topologyApi.listEnvironments(projectUuid), topologyApi.listBindings(projectUuid), topologyApi.listModels(projectUuid),
      ])
      setResources({ connections, physicalSchemas, logicalSchemas, environments, bindings, models })
      const versionEntries = await Promise.all(connections.map(async (connection) =>
        [connection.uuid, await topologyApi.listVersions(projectUuid, connection.uuid)] as const))
      setConnectionVersions(Object.fromEntries(versionEntries))
      setSelectedConnectionUuid((current) => connections.some((item) => item.uuid === current) ? current : connections[0]?.uuid ?? '')
      setSelectedPhysicalUuid((current) => physicalSchemas.some((item) => item.uuid === current) ? current : physicalSchemas[0]?.uuid ?? '')
      setSelectedModelUuid((current) => models.some((item) => item.uuid === current) ? current : models[0]?.uuid ?? '')
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : tr('loadFailed'))
    } finally {
      setLoading(false)
    }
  }, [projectUuid, tr])

  useEffect(() => { void loadAll() }, [loadAll])

  useEffect(() => {
    setDiscoveryResult(null)
    if (!projectUuid || !selectedConnectionUuid) {
      setVersions([])
      setSelectedVersionUuid('')
      return
    }
    let active = true
    setVersionsLoading(true)
    topologyApi.listVersions(projectUuid, selectedConnectionUuid)
      .then((items) => {
        if (!active) return
        setVersions(items)
        setConnectionVersions((current) => ({ ...current, [selectedConnectionUuid]: items }))
        setSelectedVersionUuid((current) => items.some((item) => item.uuid === current) ? current : items[0]?.uuid ?? '')
      })
      .catch((error: unknown) => active && setActionError(error instanceof Error ? error.message : tr('loadFailed')))
      .finally(() => active && setVersionsLoading(false))
    return () => { active = false }
  }, [projectUuid, selectedConnectionUuid, tr])

  useEffect(() => {
    setOracleSchemas([])
    setSchemaLoadError('')
    if (form !== 'physical' || !projectUuid || !physicalConnectionUuid) return
    const candidates = connectionVersions[physicalConnectionUuid] ?? []
    const version = candidates.find((item) => item.lifecycleStatus === 'ACTIVE')
      ?? candidates.find((item) => item.lifecycleStatus === 'TESTED')
    if (!version) return
    let active = true
    setSchemasLoading(true)
    topologyApi.listOracleSchemas(projectUuid, physicalConnectionUuid, version.uuid)
      .then((items) => active && setOracleSchemas(items))
      .catch(() => active && setSchemaLoadError(tr('schemaLoadFailed')))
      .finally(() => active && setSchemasLoading(false))
    return () => { active = false }
  }, [connectionVersions, form, physicalConnectionUuid, projectUuid, tr])

  useEffect(() => {
    if (!projectUuid || !selectedModelUuid) {
      setSubmodels([])
      setDataObjects([])
      return
    }
    let active = true
    setCatalogLoading(true)
    Promise.all([
      topologyApi.listSubmodels(projectUuid, selectedModelUuid),
      topologyApi.listDataObjects(projectUuid, selectedModelUuid),
    ]).then(([nextSubmodels, nextObjects]) => {
      if (!active) return
      setSubmodels(nextSubmodels)
      setDataObjects(nextObjects)
    }).catch((error: unknown) => active && setActionError(error instanceof Error ? error.message : tr('loadFailed')))
      .finally(() => active && setCatalogLoading(false))
    return () => { active = false }
  }, [projectUuid, selectedModelUuid, tr])

  const selectedConnection = resources.connections.find((item) => item.uuid === selectedConnectionUuid)
  const executableVersions = versions.filter((item) => item.mode === 'JDBC')
  const connectionPhysicalSchemas = resources.physicalSchemas.filter((item) => item.connectionUuid === selectedConnectionUuid)
  const discoveryVersion = discoveryResult
    ? versions.find((item) => item.uuid === discoveryResult.connectionVersionUuid)
    : undefined
  const discoveryPhysicalSchema = discoveryResult
    ? resources.physicalSchemas.find((item) => item.uuid === discoveryResult.physicalSchemaUuid)
    : undefined
  const selectedModel = resources.models.find((item) => item.uuid === selectedModelUuid)
  const testExistingConnection = async (connectionUuid: string, versionUuid: string) => {
    setBusy(`test-${connectionUuid}`); setActionError('')
    try {
      await topologyApi.testConnectionVersion(projectUuid, connectionUuid, versionUuid)
      const items = await topologyApi.listVersions(projectUuid, connectionUuid)
      setConnectionVersions((current) => ({ ...current, [connectionUuid]: items }))
      if (selectedConnectionUuid === connectionUuid) setVersions(items)
    } catch (error) { setActionError(error instanceof Error ? error.message : tr('connectionTestFailed')) }
    finally { setBusy('') }
  }
  const reloadSelectedCatalog = async () => {
    if (!selectedModelUuid) return
    const [nextSubmodels, nextObjects] = await Promise.all([
      topologyApi.listSubmodels(projectUuid, selectedModelUuid),
      topologyApi.listDataObjects(projectUuid, selectedModelUuid),
    ])
    setSubmodels(nextSubmodels)
    setDataObjects(nextObjects)
  }
  const formTitle: Record<Exclude<FormName, null>, string> = {
    connection: tr('addConnection'),
    physical: tr('addPhysical'), logical: tr('addLogical'), environment: tr('addEnvironment'),
    binding: tr('addBinding'), model: tr('addModel'), submodel: tr('addSubmodel'), object: tr('addDataObject'),
  }

  useEffect(() => {
    if (!connectionPhysicalSchemas.some((item) => item.uuid === selectedPhysicalUuid)) {
      setSelectedPhysicalUuid(connectionPhysicalSchemas[0]?.uuid ?? '')
    }
  }, [connectionPhysicalSchemas, selectedPhysicalUuid])

  const submit = async (name: Exclude<FormName, null>, event: FormEvent<HTMLFormElement>, request: (data: FormData) => Promise<unknown>, after?: () => Promise<void> | void) => {
    event.preventDefault()
    const formElement = event.currentTarget
    const formData = new FormData(formElement)
    setBusy(name)
    setActionError('')
    try {
      await request(formData)
      formElement.reset()
      setForm(null)
      await loadAll()
      await after?.()
    } catch (error) {
      setActionError(error instanceof Error ? error.message : tr('saveFailed'))
    } finally {
      setBusy('')
    }
  }

  const runDiscovery = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selectedConnectionUuid || !selectedVersionUuid || !selectedPhysicalUuid) return
    const data = new FormData(event.currentTarget)
    setBusy('discover')
    setActionError('')
    setDiscoveryResult(null)
    try {
      setDiscoveryResult(await topologyApi.discoverOracle(projectUuid, selectedConnectionUuid, selectedVersionUuid, selectedPhysicalUuid, {
        tableName: optionalValue(data, 'tableName'), limit: Number(textValue(data, 'limit') || 100),
      }))
    } catch (error) { setActionError(error instanceof Error ? error.message : tr('loadFailed')) }
    finally { setBusy('') }
  }

  if (!projectUuid) return <section className="topology-page"><div className="topology-alert" role="alert"><CircleAlert />{tr('missingProject')}</div></section>

  const tabs: Array<{ id: Tab; label: string; icon: ReactNode; count: number }> = [
    { id: 'connections', label: tr('connections'), icon: <Cable />, count: resources.connections.length },
    { id: 'schemas', label: tr('schemas'), icon: <Layers3 />, count: resources.physicalSchemas.length + resources.logicalSchemas.length },
    { id: 'bindings', label: tr('bindings'), icon: <Link2 />, count: resources.bindings.length },
    { id: 'catalog', label: tr('catalog'), icon: <TableProperties />, count: resources.models.length },
  ]

  return (
    <section className="topology-page">
      <header className="topology-hero">
        <div><span className="topology-eyebrow"><Network /> {tr('eyebrow')}</span><h1>{tr('title')}</h1><p>{tr('subtitle')}</p></div>
        <button className="topology-button topology-button--quiet" onClick={() => void loadAll()} disabled={loading}><RefreshCw className={loading ? 'is-spinning' : ''} />{tr('refresh')}</button>
      </header>

      <div className="topology-tabs" role="tablist" aria-label={tr('title')} onKeyDown={(event) => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
        const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
        const current = buttons.indexOf(document.activeElement as HTMLButtonElement)
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (current + (event.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length
        event.preventDefault(); buttons[next]?.focus(); buttons[next]?.click()
      }}>
        {tabs.map((item) => <button key={item.id} id={`topology-tab-${item.id}`} role="tab" tabIndex={tab === item.id ? 0 : -1} aria-selected={tab === item.id} aria-controls={`topology-panel-${item.id}`} onClick={() => setTab(item.id)}>{item.icon}<span>{item.label}</span><b>{item.count}</b></button>)}
      </div>

      {loadError && <div className="topology-alert" role="alert"><CircleAlert /><span>{loadError}</span><button onClick={() => void loadAll()}>{tr('tryAgain')}</button></div>}
      {actionError && <div className="topology-alert" role="alert"><CircleAlert /><span>{actionError}</span><button aria-label={tr('close')} onClick={() => setActionError('')}><X /></button></div>}
      {loading ? <div className="topology-loading" role="status"><LoaderCircle className="is-spinning" />{tr('loading')}</div> : (
        <>
          {tab === 'connections' && (
            <div id="topology-panel-connections" role="tabpanel" aria-labelledby="topology-tab-connections">
              <section className="topology-panel topology-panel--connections">
                <PanelHeading icon={<Cable />} title={tr('connections')} count={resources.connections.length} action={<button className="topology-button topology-button--small" onClick={() => setForm('connection')}><Plus />{tr('addConnection')}</button>} />
                {resources.connections.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-connection-grid">{resources.connections.map((item) => {
                  const latest = connectionVersions[item.uuid]?.[0]
                  return <article key={item.uuid} className="topology-connection-card">
                    <header><DatabaseProviderIcon databaseType={item.databaseType} /><div><h3>{item.name}</h3><small>{item.code}</small></div><Status value={item.status} /></header>
                    {latest ? <div className="topology-connection-facts">
                      <div><Globe2 /><span><small>{tr('host')}</small><strong>{latest.mode === 'JNDI' ? latest.jndiName : latest.host}</strong></span></div>
                      <div><ServerCog /><span><small>{tr('port')}</small><strong>{latest.mode === 'JNDI' ? 'JNDI' : latest.port}</strong></span></div>
                      <div><Database /><span><small>{latest.serviceName ? tr('serviceName') : tr('sid')}</small><strong>{latest.serviceName ?? latest.sid ?? '—'}</strong></span></div>
                      <div><User /><span><small>{tr('username')}</small><strong>{latest.username ?? '—'}</strong></span></div>
                    </div> : <Empty>{tr('noItems')}</Empty>}
                    {item.description && <p>{item.description}</p>}
                    <footer><span>{latest?.testedAt ? `${c.testedAt}: ${dateFormatter.format(new Date(latest.testedAt))}` : c.notTested}</span>
                      {latest && <button className="topology-button topology-button--small topology-button--test" onClick={() => void testExistingConnection(item.uuid, latest.uuid)} disabled={Boolean(busy)}>{busy === `test-${item.uuid}` ? <LoaderCircle className="is-spinning" /> : <CheckCircle2 />}{busy === `test-${item.uuid}` ? c.testing : c.testDraftConnection}</button>}
                    </footer>
                  </article>
                })}</div>}
              </section>
            </div>
          )}

          {tab === 'schemas' && (
            <div className="topology-columns topology-columns--three" id="topology-panel-schemas" role="tabpanel" aria-labelledby="topology-tab-schemas">
              <section className="topology-panel"><PanelHeading icon={<Database />} title={tr('physical')} count={resources.physicalSchemas.length} action={<button className="topology-button topology-button--icon" onClick={() => { setPhysicalConnectionUuid(selectedConnectionUuid || resources.connections[0]?.uuid || ''); setForm('physical') }} aria-label={tr('addPhysical')} disabled={resources.connections.length === 0}><Plus /></button>} />
                {resources.physicalSchemas.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-simple-list">{resources.physicalSchemas.map((item) => <article key={item.uuid}><div><strong>{item.name}</strong><small>{resources.connections.find((connection) => connection.uuid === item.connectionUuid)?.name ?? item.code}</small></div><Status value={item.status} /></article>)}</div>}
              </section>
              <section className="topology-panel"><PanelHeading icon={<Layers3 />} title={tr('logical')} count={resources.logicalSchemas.length} action={<button className="topology-button topology-button--icon" onClick={() => setForm('logical')} aria-label={tr('addLogical')}><Plus /></button>} />
                {resources.logicalSchemas.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-simple-list">{resources.logicalSchemas.map((item) => <article key={item.uuid}><div><strong>{item.name}</strong><small>{item.code}</small></div><Status value={item.status} /></article>)}</div>}
              </section>
              <section className="topology-panel"><PanelHeading icon={<ServerCog />} title={tr('environments')} count={resources.environments.length} action={<button className="topology-button topology-button--icon" onClick={() => setForm('environment')} aria-label={tr('addEnvironment')}><Plus /></button>} />
                {resources.environments.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-simple-list">{resources.environments.map((item) => <article key={item.uuid}><div><strong>{item.name}</strong><small>{item.code}</small></div><Status value={item.status} /></article>)}</div>}
              </section>
              <section className="topology-panel topology-panel--wide">
                <PanelHeading icon={<Search />} title={tr('discover')} />
                <form className="topology-discovery" onSubmit={(event) => void runDiscovery(event)}>
                  <label><span>{tr('connection')}</span><select value={selectedConnectionUuid} onChange={(event) => setSelectedConnectionUuid(event.target.value)} required><option value="">—</option>{resources.connections.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></label>
                  <label><span>{tr('version')}</span><select value={selectedVersionUuid} onChange={(event) => setSelectedVersionUuid(event.target.value)} required disabled={!selectedConnectionUuid}>{versions.map((item) => <option key={item.uuid} value={item.uuid}>v{item.versionNumber} · {connectionVersionSummary(item)}</option>)}</select></label>
                  <label><span>{tr('physicalSchema')}</span><select value={selectedPhysicalUuid} onChange={(event) => setSelectedPhysicalUuid(event.target.value)} required disabled={!selectedConnectionUuid}>{connectionPhysicalSchemas.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></label>
                  <label><span>{tr('tableFilter')}</span><input name="tableName" pattern="[A-Za-z][A-Za-z0-9_$#]{0,127}" /></label>
                  <label><span>{tr('limit')}</span><input name="limit" type="number" min="1" max="200" defaultValue="100" required /></label>
                  <button className="topology-button" disabled={!selectedVersionUuid || !selectedPhysicalUuid || busy === 'discover'}><Search />{busy === 'discover' ? tr('discovering') : tr('discover')}</button>
                </form>
                {(!selectedVersionUuid || !selectedPhysicalUuid) && <p className="topology-hint">{tr('chooseDiscovery')}</p>}
                {discoveryResult && <div className="topology-discovery-results">
                  <header><div><strong>{tr('discoveryResults')}</strong><span>{discoveryResult.owner} · {discoveryResult.tables.length} {tr('tables')}</span></div>{discoveryResult.truncated && <Status value={tr('truncated')} />}</header>
                  {discoveryResult.tables.length === 0 ? <Empty>{tr('emptyDiscovery')}</Empty> : discoveryResult.tables.map((table) => <details key={`${table.owner}.${table.name}`}>
                    <summary><TableProperties /><strong>{table.name}</strong><span>{table.type}</span><b>{table.columns.length}</b></summary>
                    <div className="topology-table-wrap"><table><thead><tr><th>{tr('name')}</th><th>{tr('type')}</th><th>{tr('nullable')}</th><th>{tr('position')}</th></tr></thead><tbody>{table.columns.map((column) => <tr key={column.name}><td>{column.name}</td><td><code>{column.producerType}</code></td><td>{column.nullable ? tr('yes') : tr('no')}</td><td>{column.ordinal}</td></tr>)}</tbody></table></div>
                    {table.type === 'TABLE' && selectedConnection && discoveryVersion && discoveryPhysicalSchema && <DiscoverySnapshotPanel
                      projectUuid={projectUuid}
                      connectionUuid={selectedConnection.uuid}
                      connectionLabel={selectedConnection.name}
                      connectionVersionUuid={discoveryVersion.uuid}
                      connectionVersionLabel={`v${discoveryVersion.versionNumber} · ${connectionVersionSummary(discoveryVersion)}`}
                      physicalSchemaUuid={discoveryPhysicalSchema.uuid}
                      physicalSchemaLabel={`${discoveryPhysicalSchema.name} · ${discoveryPhysicalSchema.schemaReference}`}
                      discovery={discoveryResult}
                      table={table}
                      models={resources.models}
                      bindings={resources.bindings}
                      preferredModelUuid={selectedModelUuid}
                      copy={c}
                      locale={locale}
                    />}
                  </details>)}
                </div>}
              </section>
            </div>
          )}

          {tab === 'bindings' && (
            <section className="topology-panel" id="topology-panel-bindings" role="tabpanel" aria-labelledby="topology-tab-bindings">
              <PanelHeading icon={<Link2 />} title={tr('bindings')} count={resources.bindings.length} action={<button className="topology-button topology-button--small" onClick={() => {
                const physical = resources.physicalSchemas.find((item) => item.uuid === selectedPhysicalUuid) ?? resources.physicalSchemas[0]
                if (physical) {
                  setSelectedPhysicalUuid(physical.uuid)
                  setSelectedConnectionUuid(physical.connectionUuid)
                }
                setForm('binding')
              }} disabled={!resources.logicalSchemas.length || !resources.environments.length || !resources.physicalSchemas.length}><Plus />{tr('addBinding')}</button>} />
              {resources.bindings.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-binding-grid">{resources.bindings.map((item) => {
                const logical = resources.logicalSchemas.find((entry) => entry.uuid === item.logicalSchemaUuid)
                const environment = resources.environments.find((entry) => entry.uuid === item.environmentUuid)
                const physical = resources.physicalSchemas.find((entry) => entry.uuid === item.physicalSchemaUuid)
                return <article key={item.uuid}><div className="topology-binding-node topology-binding-node--logical"><Layers3 /><span>{tr('logicalSchema')}</span><strong>{logical?.name ?? tr('unavailable')}</strong></div><ChevronRight /><div className="topology-binding-node"><ServerCog /><span>{environment?.name ?? tr('unavailable')}</span><strong>{physical?.name ?? tr('unavailable')}</strong></div><Status value={item.status} /></article>
              })}</div>}
            </section>
          )}

          {tab === 'catalog' && (
            <div className="topology-columns topology-columns--catalog" id="topology-panel-catalog" role="tabpanel" aria-labelledby="topology-tab-catalog">
              <section className="topology-panel"><PanelHeading icon={<TableProperties />} title={tr('models')} count={resources.models.length} action={<button className="topology-button topology-button--icon" onClick={() => setForm('model')} aria-label={tr('addModel')} disabled={!resources.logicalSchemas.length}><Plus /></button>} />
                {resources.models.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-cards">{resources.models.map((item) => <button key={item.uuid} className="topology-card" aria-pressed={selectedModelUuid === item.uuid} onClick={() => setSelectedModelUuid(item.uuid)}><span className="topology-card-icon"><TableProperties /></span><span><strong>{item.name}</strong><small>{item.code}</small></span><Status value={item.status} /><ChevronRight /></button>)}</div>}
              </section>
              <div className="topology-catalog-detail">
                {!selectedModel ? <section className="topology-panel"><Empty>{tr('chooseModel')}</Empty></section> : catalogLoading ? <section className="topology-panel"><div className="topology-loading"><LoaderCircle className="is-spinning" />{tr('loading')}</div></section> : <>
                  <section className="topology-panel"><PanelHeading icon={<Network />} title={tr('submodels')} count={submodels.length} action={<button className="topology-button topology-button--small" onClick={() => setForm('submodel')}><Plus />{tr('addSubmodel')}</button>} />{submodels.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-simple-list">{submodels.map((item) => <article key={item.uuid}><div><strong>{item.name}</strong><small>{item.code}</small></div>{item.parentUuid && <span className="topology-muted">↳ {submodels.find((parent) => parent.uuid === item.parentUuid)?.name ?? tr('unavailable')}</span>}</article>)}</div>}</section>
                  <section className="topology-panel"><PanelHeading icon={<Boxes />} title={tr('dataObjects')} count={dataObjects.length} action={<button className="topology-button topology-button--small" onClick={() => setForm('object')}><Plus />{tr('addDataObject')}</button>} />{dataObjects.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-table-wrap"><table><thead><tr><th>{tr('name')}</th><th>{tr('objectReference')}</th><th>{tr('type')}</th><th>{tr('submodel')}</th><th>{tr('status')}</th></tr></thead><tbody>{dataObjects.map((item) => <tr key={item.uuid}><td><strong>{item.name}</strong><small>{item.code}</small></td><td><code>{item.objectReference}</code></td><td>{item.type}</td><td>{submodels.find((entry) => entry.uuid === item.submodelUuid)?.name ?? '—'}</td><td><Status value={item.status} /></td></tr>)}</tbody></table></div>}</section>
                </>}
              </div>
            </div>
          )}
        </>
      )}

      <Dialog open={form !== null} className="topology-dialog" title={form ? formTitle[form] : ''} closeLabel={tr('close')} busy={Boolean(busy)} onClose={() => setForm(null)}>
        {form === 'connection' && <OracleConnectionCreateForm projectUuid={projectUuid} copy={c} onClose={() => setForm(null)} onConnectionCreated={async (connectionUuid) => { await loadAll(); setSelectedConnectionUuid(connectionUuid) }} />}
        {form === 'physical' && <form className="topology-form" onSubmit={(event) => void submit('physical', event, (data) => topologyApi.createPhysicalSchema(projectUuid, { connectionUuid: textValue(data, 'connectionUuid'), schema: textValue(data, 'schema') }))}>
          <Field label={tr('connection')} name="connectionUuid"><select name="connectionUuid" required value={physicalConnectionUuid} onChange={(event) => setPhysicalConnectionUuid(event.target.value)}><option value="">—</option>{resources.connections.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field>
          <Field label={tr('schemaUser')} name="schema"><input name="schema" list="oracle-schema-options" autoComplete="off" required /><datalist id="oracle-schema-options">{oracleSchemas.map((schema) => <option key={schema} value={schema} />)}</datalist></Field>
          <p className="topology-hint">{schemasLoading ? tr('schemaLoading') : schemaLoadError || ((connectionVersions[physicalConnectionUuid] ?? []).some((item) => item.lifecycleStatus === 'ACTIVE' || item.lifecycleStatus === 'TESTED') ? tr('schemaHint') : tr('schemaVersionRequired'))}</p>
          <Submit busy={busy === 'physical'} c={c} />
        </form>}
        {form === 'logical' && <form className="topology-form" onSubmit={(event) => void submit('logical', event, (data) => topologyApi.createLogicalSchema(projectUuid, { code: textValue(data, 'code'), name: textValue(data, 'name'), description: optionalValue(data, 'description') }))}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('description')} name="description" optional><textarea name="description" rows={3} /></Field><Submit busy={busy === 'logical'} c={c} /></form>}
        {form === 'environment' && <form className="topology-form" onSubmit={(event) => void submit('environment', event, (data) => topologyApi.createEnvironment(projectUuid, { code: textValue(data, 'code'), name: textValue(data, 'name') }))}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Submit busy={busy === 'environment'} c={c} /></form>}
        {form === 'binding' && <form className="topology-form" onSubmit={(event) => void submit('binding', event, (data) => topologyApi.createBinding(projectUuid, { logicalSchemaUuid: textValue(data, 'logicalSchemaUuid'), environmentUuid: textValue(data, 'environmentUuid'), physicalSchemaUuid: textValue(data, 'physicalSchemaUuid'), connectionVersionUuid: textValue(data, 'connectionVersionUuid') }))}><Field label={tr('logicalSchema')} name="logicalSchemaUuid"><select name="logicalSchemaUuid" required>{resources.logicalSchemas.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Field label={tr('environment')} name="environmentUuid"><select name="environmentUuid" required>{resources.environments.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Field label={tr('physicalSchema')} name="physicalSchemaUuid"><select name="physicalSchemaUuid" required value={selectedPhysicalUuid} onChange={(event) => {
          setSelectedPhysicalUuid(event.target.value)
          setSelectedConnectionUuid(resources.physicalSchemas.find((item) => item.uuid === event.target.value)?.connectionUuid ?? '')
        }}>{resources.physicalSchemas.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Field label={tr('version')} name="connectionVersionUuid"><select name="connectionVersionUuid" required disabled={versionsLoading || executableVersions.length === 0}>{executableVersions.map((item) => <option key={item.uuid} value={item.uuid}>v{item.versionNumber} · {connectionVersionSummary(item)}</option>)}</select></Field><Submit busy={busy === 'binding' || executableVersions.length === 0} c={c} /></form>}
        {form === 'model' && <form className="topology-form" onSubmit={(event) => void submit('model', event, (data) => topologyApi.createModel(projectUuid, { logicalSchemaUuid: textValue(data, 'logicalSchemaUuid'), code: textValue(data, 'code'), name: textValue(data, 'name'), description: optionalValue(data, 'description') }))}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('logicalSchema')} name="logicalSchemaUuid"><select name="logicalSchemaUuid" required>{resources.logicalSchemas.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Field label={tr('description')} name="description" optional><textarea name="description" rows={3} /></Field><Submit busy={busy === 'model'} c={c} /></form>}
        {form === 'submodel' && selectedModel && <form className="topology-form" onSubmit={(event) => void submit('submodel', event, (data) => topologyApi.createSubmodel(projectUuid, selectedModel.uuid, { parentUuid: optionalValue(data, 'parentUuid'), code: textValue(data, 'code'), name: textValue(data, 'name') }), reloadSelectedCatalog)}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('parent')} name="parentUuid" optional><select name="parentUuid" defaultValue=""><option value="">—</option>{submodels.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Submit busy={busy === 'submodel'} c={c} /></form>}
        {form === 'object' && selectedModel && <form className="topology-form" onSubmit={(event) => void submit('object', event, (data) => topologyApi.createDataObject(projectUuid, selectedModel.uuid, { submodelUuid: optionalValue(data, 'submodelUuid'), code: textValue(data, 'code'), name: textValue(data, 'name'), objectReference: textValue(data, 'objectReference'), type: textValue(data, 'type') }), reloadSelectedCatalog)}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('objectReference')} name="objectReference" /><Field label={tr('type')} name="type"><select name="type" defaultValue="TABLO" required><option value="TABLO">{tr('tableType')}</option><option value="VIEW">{tr('viewType')}</option></select></Field><Field label={tr('submodel')} name="submodelUuid" optional><select name="submodelUuid" defaultValue=""><option value="">—</option>{submodels.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Submit busy={busy === 'object'} c={c} /></form>}
      </Dialog>
    </section>
  )
}

function Submit({ busy, c }: { busy: boolean; c: ReturnType<typeof getTopologyCopy> }) {
  return <button className="topology-button topology-button--submit" type="submit" disabled={busy}>{busy ? <LoaderCircle className="is-spinning" /> : <Plus />}{busy ? c.creating : c.create}</button>
}
