import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import {
  Boxes, Cable, ChevronRight, CircleAlert, Database,
  KeyRound, Layers3, Link2, LoaderCircle, Network, Plus, RefreshCw,
  Search, ServerCog, TableProperties, X,
} from 'lucide-react'
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
  type SecretReference,
  type Submodel,
} from './api'
import { getTopologyCopy, type CopyKey } from './copy'
import { ConnectionVersionLifecyclePanel, lifecycleLabel } from './ConnectionVersionLifecyclePanel'
import { DiscoverySnapshotPanel } from './DiscoverySnapshotPanel'
import { OracleConnectionVersionForm } from './OracleConnectionVersionForm'
import './topology.css'

type Tab = 'connections' | 'schemas' | 'bindings' | 'catalog'
type FormName = 'secret' | 'connection' | 'version' | 'physical' | 'logical' | 'environment' | 'binding' | 'model' | 'submodel' | 'object' | null

interface TopologyPageProps {
  projectUuid?: string
  initialTab?: Tab
}

interface Resources {
  secrets: SecretReference[]
  connections: Connection[]
  physicalSchemas: PhysicalSchema[]
  logicalSchemas: LogicalSchema[]
  environments: Environment[]
  bindings: SchemaBinding[]
  models: Model[]
}

const emptyResources: Resources = {
  secrets: [], connections: [], physicalSchemas: [], logicalSchemas: [],
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
  const normalized = value.toLowerCase()
  return <span className={`topology-status topology-status--${normalized}`}>{value}</span>
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

function Drawer({ title, closeLabel, onClose, children }: { title: string; closeLabel: string; onClose(): void; children: ReactNode }) {
  return (
    <div className="topology-drawer" role="dialog" aria-modal="true" aria-labelledby="topology-drawer-title">
      <button className="topology-drawer-backdrop" aria-label={closeLabel} onClick={onClose} />
      <section>
        <header><h2 id="topology-drawer-title">{title}</h2><button className="topology-icon-button" onClick={onClose} aria-label={closeLabel}><X /></button></header>
        {children}
      </section>
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
  const [versions, setVersions] = useState<ConnectionVersion[]>([])
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
      const [secrets, connections, physicalSchemas, logicalSchemas, environments, bindings, models] = await Promise.all([
        topologyApi.listSecrets(projectUuid), topologyApi.listConnections(projectUuid),
        topologyApi.listPhysicalSchemas(projectUuid), topologyApi.listLogicalSchemas(projectUuid),
        topologyApi.listEnvironments(projectUuid), topologyApi.listBindings(projectUuid), topologyApi.listModels(projectUuid),
      ])
      setResources({ secrets, connections, physicalSchemas, logicalSchemas, environments, bindings, models })
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
        setSelectedVersionUuid((current) => items.some((item) => item.uuid === current) ? current : items[0]?.uuid ?? '')
      })
      .catch((error: unknown) => active && setActionError(error instanceof Error ? error.message : tr('loadFailed')))
      .finally(() => active && setVersionsLoading(false))
    return () => { active = false }
  }, [projectUuid, selectedConnectionUuid, tr])

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
  const selectedVersion = versions.find((item) => item.uuid === selectedVersionUuid)
  const executableVersions = versions.filter((item) => item.mode === 'JDBC')
  const connectionPhysicalSchemas = resources.physicalSchemas.filter((item) => item.connectionUuid === selectedConnectionUuid)
  const discoveryVersion = discoveryResult
    ? versions.find((item) => item.uuid === discoveryResult.connectionVersionUuid)
    : undefined
  const discoveryPhysicalSchema = discoveryResult
    ? resources.physicalSchemas.find((item) => item.uuid === discoveryResult.physicalSchemaUuid)
    : undefined
  const selectedModel = resources.models.find((item) => item.uuid === selectedModelUuid)
  const refreshConnectionVersions = async (focusUuid = selectedVersionUuid) => {
    if (!selectedConnectionUuid) return undefined
    const items = await topologyApi.listVersions(projectUuid, selectedConnectionUuid)
    setVersions(items)
    const focused = items.find((item) => item.uuid === focusUuid)
    setSelectedVersionUuid(focused?.uuid ?? items[0]?.uuid ?? '')
    return focused
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
    secret: tr('addSecret'), connection: tr('addConnection'), version: tr('addVersion'),
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
    setBusy(name)
    setActionError('')
    try {
      await request(new FormData(event.currentTarget))
      event.currentTarget.reset()
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

  if (!projectUuid) return <main className="topology-page"><div className="topology-alert" role="alert"><CircleAlert />Missing project UUID.</div></main>

  const tabs: Array<{ id: Tab; label: string; icon: ReactNode; count: number }> = [
    { id: 'connections', label: tr('connections'), icon: <Cable />, count: resources.connections.length },
    { id: 'schemas', label: tr('schemas'), icon: <Layers3 />, count: resources.physicalSchemas.length + resources.logicalSchemas.length },
    { id: 'bindings', label: tr('bindings'), icon: <Link2 />, count: resources.bindings.length },
    { id: 'catalog', label: tr('catalog'), icon: <TableProperties />, count: resources.models.length },
  ]

  return (
    <main className="topology-page">
      <header className="topology-hero">
        <div><span className="topology-eyebrow"><Network /> PROJECT TOPOLOGY</span><h1>{tr('title')}</h1><p>{tr('subtitle')}</p></div>
        <button className="topology-button topology-button--quiet" onClick={() => void loadAll()} disabled={loading}><RefreshCw className={loading ? 'is-spinning' : ''} />{tr('refresh')}</button>
      </header>

      <nav className="topology-tabs" aria-label={tr('title')}>
        {tabs.map((item) => <button key={item.id} role="tab" aria-selected={tab === item.id} onClick={() => setTab(item.id)}>{item.icon}<span>{item.label}</span><b>{item.count}</b></button>)}
      </nav>

      {loadError && <div className="topology-alert" role="alert"><CircleAlert /><span>{loadError}</span><button onClick={() => void loadAll()}>{tr('tryAgain')}</button></div>}
      {actionError && <div className="topology-alert" role="alert"><CircleAlert /><span>{actionError}</span><button aria-label={tr('close')} onClick={() => setActionError('')}><X /></button></div>}
      {loading ? <div className="topology-loading" role="status"><LoaderCircle className="is-spinning" />{tr('loading')}</div> : (
        <>
          {tab === 'connections' && (
            <div className="topology-columns topology-columns--master-detail">
              <section className="topology-panel">
                <PanelHeading icon={<Cable />} title={tr('connections')} count={resources.connections.length} action={<button className="topology-button topology-button--small" onClick={() => setForm('connection')}><Plus />{tr('addConnection')}</button>} />
                {resources.connections.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-cards">{resources.connections.map((item) => (
                  <button key={item.uuid} className="topology-card" aria-pressed={selectedConnectionUuid === item.uuid} onClick={() => setSelectedConnectionUuid(item.uuid)}>
                    <span className="topology-card-icon"><Database /></span><span><strong>{item.name}</strong><small>{item.code} · {item.databaseType}</small></span><Status value={item.status} /><ChevronRight />
                  </button>
                ))}</div>}
              </section>
              <section className="topology-panel">
                <PanelHeading icon={<ServerCog />} title={tr('versions')} count={versions.length} action={selectedConnection && <button className="topology-button topology-button--small" onClick={() => setForm('version')} disabled={selectedConnection.databaseType !== 'ORACLE'}><Plus />{tr('addVersion')}</button>} />
                {!selectedConnection ? <Empty>{tr('chooseConnection')}</Empty> : versionsLoading ? <div className="topology-loading"><LoaderCircle className="is-spinning" />{tr('loading')}</div> : versions.length === 0 ? <Empty>{tr('noItems')}</Empty> : (
                  <div className="topology-version-list">{versions.map((item) => <button key={item.uuid} aria-pressed={selectedVersionUuid === item.uuid} onClick={() => setSelectedVersionUuid(item.uuid)}>
                    <span className="topology-version-card-heading"><span>v{item.versionNumber} · {item.mode}</span><span className={`topology-lifecycle-badge topology-lifecycle-badge--${item.lifecycleStatus.toLowerCase()}`}>{lifecycleLabel(item.lifecycleStatus, c)}</span></span>
                    <strong>{connectionVersionSummary(item)}</strong>
                    <small>{item.testedAt ? `${c.testedAt}: ${dateFormatter.format(new Date(item.testedAt))}` : c.notTested}</small>
                  </button>)}</div>
                )}
                {selectedVersion && selectedConnection && <ConnectionVersionLifecyclePanel
                  projectUuid={projectUuid}
                  connectionUuid={selectedConnection.uuid}
                  version={selectedVersion}
                  copy={c}
                  locale={locale}
                  onVersionChanged={() => refreshConnectionVersions(selectedVersion.uuid).then(() => undefined)}
                />}
              </section>
              <section className="topology-panel topology-panel--wide">
                <PanelHeading icon={<KeyRound />} title={tr('secretRefs')} count={resources.secrets.length} action={<button className="topology-button topology-button--small" onClick={() => setForm('secret')}><Plus />{tr('addSecret')}</button>} />
                <p className="topology-security-note"><KeyRound />{tr('noSecretValues')}</p>
                {resources.secrets.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-table-wrap"><table><thead><tr><th>{tr('name')}</th><th>{tr('provider')}</th><th>{tr('referencePath')}</th><th>{tr('status')}</th></tr></thead><tbody>{resources.secrets.map((item) => <tr key={item.uuid}><td><strong>{item.name}</strong><small>{item.code}</small></td><td>{item.provider}</td><td><code>{item.referencePath}</code></td><td><Status value={item.status} /></td></tr>)}</tbody></table></div>}
              </section>
            </div>
          )}

          {tab === 'schemas' && (
            <div className="topology-columns topology-columns--three">
              <section className="topology-panel"><PanelHeading icon={<Database />} title={tr('physical')} count={resources.physicalSchemas.length} action={<button className="topology-button topology-button--icon" onClick={() => setForm('physical')} aria-label={tr('addPhysical')}><Plus /></button>} />
                {resources.physicalSchemas.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-simple-list">{resources.physicalSchemas.map((item) => <article key={item.uuid}><div><strong>{item.name}</strong><small>{item.code} · {item.schemaReference}</small></div><Status value={item.status} /></article>)}</div>}
              </section>
              <section className="topology-panel"><PanelHeading icon={<Layers3 />} title={tr('logical')} count={resources.logicalSchemas.length} action={<button className="topology-button topology-button--icon" onClick={() => setForm('logical')} aria-label={tr('addLogical')}><Plus /></button>} />
                {resources.logicalSchemas.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-simple-list">{resources.logicalSchemas.map((item) => <article key={item.uuid}><div><strong>{item.name}</strong><small>{item.code}</small></div><Status value={item.status} /></article>)}</div>}
              </section>
              <section className="topology-panel"><PanelHeading icon={<ServerCog />} title={tr('environments')} count={resources.environments.length} action={<button className="topology-button topology-button--icon" onClick={() => setForm('environment')} aria-label={tr('addEnvironment')}><Plus /></button>} />
                {resources.environments.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-simple-list">{resources.environments.map((item) => <article key={item.uuid}><div><strong>{item.name}</strong><small>{item.code} · {item.risk || '—'}</small></div><Status value={item.status} /></article>)}</div>}
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
            <section className="topology-panel">
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
                return <article key={item.uuid}><div className="topology-binding-node topology-binding-node--logical"><Layers3 /><span>{tr('logicalSchema')}</span><strong>{logical?.name ?? item.logicalSchemaUuid}</strong></div><ChevronRight /><div className="topology-binding-node"><ServerCog /><span>{environment?.name ?? item.environmentUuid}</span><strong>{physical?.name ?? item.physicalSchemaUuid}</strong></div><Status value={item.status} /></article>
              })}</div>}
            </section>
          )}

          {tab === 'catalog' && (
            <div className="topology-columns topology-columns--catalog">
              <section className="topology-panel"><PanelHeading icon={<TableProperties />} title={tr('models')} count={resources.models.length} action={<button className="topology-button topology-button--icon" onClick={() => setForm('model')} aria-label={tr('addModel')} disabled={!resources.logicalSchemas.length}><Plus /></button>} />
                {resources.models.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-cards">{resources.models.map((item) => <button key={item.uuid} className="topology-card" aria-pressed={selectedModelUuid === item.uuid} onClick={() => setSelectedModelUuid(item.uuid)}><span className="topology-card-icon"><TableProperties /></span><span><strong>{item.name}</strong><small>{item.code}</small></span><Status value={item.status} /><ChevronRight /></button>)}</div>}
              </section>
              <div className="topology-catalog-detail">
                {!selectedModel ? <section className="topology-panel"><Empty>{tr('chooseModel')}</Empty></section> : catalogLoading ? <section className="topology-panel"><div className="topology-loading"><LoaderCircle className="is-spinning" />{tr('loading')}</div></section> : <>
                  <section className="topology-panel"><PanelHeading icon={<Network />} title={tr('submodels')} count={submodels.length} action={<button className="topology-button topology-button--small" onClick={() => setForm('submodel')}><Plus />{tr('addSubmodel')}</button>} />{submodels.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-simple-list">{submodels.map((item) => <article key={item.uuid}><div><strong>{item.name}</strong><small>{item.code}</small></div>{item.parentUuid && <span className="topology-muted">↳ {submodels.find((parent) => parent.uuid === item.parentUuid)?.name ?? item.parentUuid}</span>}</article>)}</div>}</section>
                  <section className="topology-panel"><PanelHeading icon={<Boxes />} title={tr('dataObjects')} count={dataObjects.length} action={<button className="topology-button topology-button--small" onClick={() => setForm('object')}><Plus />{tr('addDataObject')}</button>} />{dataObjects.length === 0 ? <Empty>{tr('noItems')}</Empty> : <div className="topology-table-wrap"><table><thead><tr><th>{tr('name')}</th><th>{tr('objectReference')}</th><th>{tr('type')}</th><th>{tr('submodel')}</th><th>{tr('status')}</th></tr></thead><tbody>{dataObjects.map((item) => <tr key={item.uuid}><td><strong>{item.name}</strong><small>{item.code}</small></td><td><code>{item.objectReference}</code></td><td>{item.type}</td><td>{submodels.find((entry) => entry.uuid === item.submodelUuid)?.name ?? '—'}</td><td><Status value={item.status} /></td></tr>)}</tbody></table></div>}</section>
                </>}
              </div>
            </div>
          )}
        </>
      )}

      {form && <Drawer title={formTitle[form]} closeLabel={tr('close')} onClose={() => setForm(null)}>
        {form === 'secret' && <form className="topology-form" onSubmit={(event) => void submit('secret', event, (data) => topologyApi.createSecret(projectUuid, { code: textValue(data, 'code'), name: textValue(data, 'name'), provider: textValue(data, 'provider'), referencePath: textValue(data, 'referencePath'), versionReference: optionalValue(data, 'versionReference') }))}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('provider')} name="provider" /><Field label={tr('referencePath')} name="referencePath" /><Field label={tr('versionReference')} name="versionReference" optional /> <p className="topology-security-note"><KeyRound />{tr('noSecretValues')}</p><Submit busy={busy === 'secret'} c={c} /></form>}
        {form === 'connection' && <form className="topology-form" onSubmit={(event) => void submit('connection', event, (data) => topologyApi.createConnection(projectUuid, { code: textValue(data, 'code'), name: textValue(data, 'name'), databaseType: textValue(data, 'databaseType'), description: optionalValue(data, 'description') }))}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('databaseType')} name="databaseType"><select name="databaseType" defaultValue="ORACLE" required><option value="ORACLE">Oracle</option></select></Field><Field label={tr('description')} name="description" optional><textarea name="description" rows={3} /></Field><Submit busy={busy === 'connection'} c={c} /></form>}
        {form === 'version' && selectedConnection?.databaseType === 'ORACLE' && <OracleConnectionVersionForm projectUuid={projectUuid} connectionUuid={selectedConnection.uuid} secrets={resources.secrets} copy={c} locale={locale} onClose={() => setForm(null)} onVersionChanged={refreshConnectionVersions} />}
        {form === 'physical' && <form className="topology-form" onSubmit={(event) => void submit('physical', event, (data) => topologyApi.createPhysicalSchema(projectUuid, { connectionUuid: textValue(data, 'connectionUuid'), code: textValue(data, 'code'), schemaReference: textValue(data, 'schemaReference'), name: textValue(data, 'name') }))}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('connection')} name="connectionUuid"><select name="connectionUuid" required defaultValue={selectedConnectionUuid}><option value="">—</option>{resources.connections.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Field label={tr('schemaReference')} name="schemaReference" /><Submit busy={busy === 'physical'} c={c} /></form>}
        {form === 'logical' && <form className="topology-form" onSubmit={(event) => void submit('logical', event, (data) => topologyApi.createLogicalSchema(projectUuid, { code: textValue(data, 'code'), name: textValue(data, 'name'), description: optionalValue(data, 'description') }))}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('description')} name="description" optional><textarea name="description" rows={3} /></Field><Submit busy={busy === 'logical'} c={c} /></form>}
        {form === 'environment' && <form className="topology-form" onSubmit={(event) => void submit('environment', event, (data) => topologyApi.createEnvironment(projectUuid, { code: textValue(data, 'code'), name: textValue(data, 'name'), risk: textValue(data, 'risk'), policyVersion: Number(textValue(data, 'policyVersion') || 1) }))}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('risk')} name="risk"><select name="risk" defaultValue="DUSUK" required><option value="DUSUK">{tr('riskLow')}</option><option value="ORTA">{tr('riskMedium')}</option><option value="URETIM">{tr('riskProduction')}</option></select></Field><Field label={tr('policyVersion')} name="policyVersion"><input name="policyVersion" type="number" min="1" defaultValue="1" required /></Field><Submit busy={busy === 'environment'} c={c} /></form>}
        {form === 'binding' && <form className="topology-form" onSubmit={(event) => void submit('binding', event, (data) => topologyApi.createBinding(projectUuid, { logicalSchemaUuid: textValue(data, 'logicalSchemaUuid'), environmentUuid: textValue(data, 'environmentUuid'), physicalSchemaUuid: textValue(data, 'physicalSchemaUuid'), connectionVersionUuid: textValue(data, 'connectionVersionUuid') }))}><Field label={tr('logicalSchema')} name="logicalSchemaUuid"><select name="logicalSchemaUuid" required>{resources.logicalSchemas.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Field label={tr('environment')} name="environmentUuid"><select name="environmentUuid" required>{resources.environments.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Field label={tr('physicalSchema')} name="physicalSchemaUuid"><select name="physicalSchemaUuid" required value={selectedPhysicalUuid} onChange={(event) => {
          setSelectedPhysicalUuid(event.target.value)
          setSelectedConnectionUuid(resources.physicalSchemas.find((item) => item.uuid === event.target.value)?.connectionUuid ?? '')
        }}>{resources.physicalSchemas.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Field label={tr('version')} name="connectionVersionUuid"><select name="connectionVersionUuid" required disabled={versionsLoading || executableVersions.length === 0}>{executableVersions.map((item) => <option key={item.uuid} value={item.uuid}>v{item.versionNumber} · {connectionVersionSummary(item)}</option>)}</select></Field><Submit busy={busy === 'binding' || executableVersions.length === 0} c={c} /></form>}
        {form === 'model' && <form className="topology-form" onSubmit={(event) => void submit('model', event, (data) => topologyApi.createModel(projectUuid, { logicalSchemaUuid: textValue(data, 'logicalSchemaUuid'), code: textValue(data, 'code'), name: textValue(data, 'name'), description: optionalValue(data, 'description') }))}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('logicalSchema')} name="logicalSchemaUuid"><select name="logicalSchemaUuid" required>{resources.logicalSchemas.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Field label={tr('description')} name="description" optional><textarea name="description" rows={3} /></Field><Submit busy={busy === 'model'} c={c} /></form>}
        {form === 'submodel' && selectedModel && <form className="topology-form" onSubmit={(event) => void submit('submodel', event, (data) => topologyApi.createSubmodel(projectUuid, selectedModel.uuid, { parentUuid: optionalValue(data, 'parentUuid'), code: textValue(data, 'code'), name: textValue(data, 'name') }), reloadSelectedCatalog)}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('parent')} name="parentUuid" optional><select name="parentUuid" defaultValue=""><option value="">—</option>{submodels.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Submit busy={busy === 'submodel'} c={c} /></form>}
        {form === 'object' && selectedModel && <form className="topology-form" onSubmit={(event) => void submit('object', event, (data) => topologyApi.createDataObject(projectUuid, selectedModel.uuid, { submodelUuid: optionalValue(data, 'submodelUuid'), code: textValue(data, 'code'), name: textValue(data, 'name'), objectReference: textValue(data, 'objectReference'), type: textValue(data, 'type') }), reloadSelectedCatalog)}><Field label={tr('name')} name="name" /><Field label={tr('code')} name="code" /><Field label={tr('objectReference')} name="objectReference" /><Field label={tr('type')} name="type"><select name="type" defaultValue="TABLO" required><option value="TABLO">{tr('tableType')}</option><option value="VIEW">{tr('viewType')}</option></select></Field><Field label={tr('submodel')} name="submodelUuid" optional><select name="submodelUuid" defaultValue=""><option value="">—</option>{submodels.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}</select></Field><Submit busy={busy === 'object'} c={c} /></form>}
      </Drawer>}
    </main>
  )
}

function Submit({ busy, c }: { busy: boolean; c: ReturnType<typeof getTopologyCopy> }) {
  return <button className="topology-button topology-button--submit" type="submit" disabled={busy}>{busy ? <LoaderCircle className="is-spinning" /> : <Plus />}{busy ? c.creating : c.create}</button>
}
