import { DataGrid } from '../../core/ui/DataGrid'
import { Select as FormSelect } from '../../core/ui/Select'
import { Input as AntInput } from 'antd'
import { Cable, Database, GitBranch, Plus, Workflow } from 'lucide-react'
import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ContextRecordDialog } from './ContextRecordDialog'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, Dialog, PageHeader, SummaryStrip } from '../../core/ui'
import { FeedbackToast } from '../../core/ui/FeedbackToast'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import {
  topologyApi,
  type Connection,
  type Environment,
  type LogicalSchema,
  type PhysicalSchema,
} from '../topology/api'
import './schemas.css'

export function LogicalSchemasPage() {
  const [notice, setNotice] = useState('')
  const closeNotice = useCallback(() => setNotice(''), [])
  const projectUuid = useCurrentProjectUuid()
  const { t } = useTranslation()
  const { can } = useProjectAccess()
  const canManage = can('BAGLANTI_YONET')
  const [items, setItems] = useState<LogicalSchema[]>([])
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [physicalSchemas, setPhysicalSchemas] = useState<PhysicalSchema[]>([])
  const [connections, setConnections] = useState<Connection[]>([])
  const [environmentUuid, setEnvironmentUuid] = useState('')
  const [physicalSchemaUuid, setPhysicalSchemaUuid] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const [selected, setSelected] = useState<LogicalSchema | null>(null)
  const [busy, setBusy] = useState(false)
  const [query, setQuery] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [nextItems, nextEnvironments, nextPhysical, nextConnections] = await Promise.all([
        topologyApi.listLogicalSchemas(projectUuid),
        topologyApi.listEnvironments(projectUuid),
        topologyApi.listPhysicalSchemas(projectUuid),
        topologyApi.listConnections(projectUuid),
      ])
      setItems(nextItems)
      setEnvironments(nextEnvironments)
      setPhysicalSchemas(nextPhysical)
      setConnections(nextConnections)
    } catch {
      setError(t('common.loadError'))
    } finally {
      setLoading(false)
    }
  }, [projectUuid, t])

  useEffect(() => { void load() }, [load])

  const prerequisitesReady = environments.length > 0 && physicalSchemas.length > 0
  const filteredItems = items.filter((item) => `${item.name} ${item.code} ${item.description ?? ''}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))

  function openCreate() {
    setEnvironmentUuid(environments[0]?.uuid ?? '')
    setPhysicalSchemaUuid('')
    setOpen(true)
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    setBusy(true)
    setError('')
    try {
      await topologyApi.createLogicalSchema(projectUuid, {
        code: String(data.get('code')).trim().toUpperCase(),
        name: String(data.get('name')).trim(),
        description: String(data.get('description')).trim() || undefined,
        environmentUuid,
        physicalSchemaUuid,
      })
      setOpen(false)
      setNotice(t('common.savedSuccessfully'))
      await load()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t('common.saveError'))
    } finally {
      setBusy(false)
    }
  }

  const physicalLabel = (physical: PhysicalSchema) => {
    const connection = connections.find((item) => item.uuid === physical.connectionUuid)
    return `${connection?.code ?? '—'} / ${physical.schemaReference}`
  }

  return <section className="page-stack schema-page"><FeedbackToast message={notice} onClose={closeNotice} />
    <PageHeader
      eyebrow={t('schemas.eyebrow')}
      title={t('schemas.logicalTitle')}
      description={t('schemas.logicalDescription')}
      actions={canManage ? <Button tone="primary" icon={<Plus size={16} />} onClick={openCreate}>{t('schemas.addLogical')}</Button> : undefined}
    />
    <QueryFilter onApply={setQuery} placeholder={`${t('schemas.name')} · ${t('schemas.code')}`} />
    <SummaryStrip ariaLabel={t('schemas.logicalTitle')} items={[
      { label: t('schemas.logicalTitle'), value: items.length, icon: <GitBranch />, tone: 'info' },
      { label: t('schemas.environmentsTitle'), value: environments.length, icon: <Workflow />, tone: 'neutral' },
      { label: t('schemas.physicalSchema'), value: physicalSchemas.length, icon: <Database />, tone: 'success' },
      { label: t('connections.title'), value: connections.length, icon: <Cable />, tone: 'neutral' },
    ]} />
    {error ? <div className="error-banner" role="alert">{error}</div> : null}
    {loading ? <AsyncState state="loading" title={t('common.loading')} /> : filteredItems.length === 0 ? <AsyncState state="empty" title={t('schemas.noLogical')} /> : <div className="schema-list-table"><DataGrid auditKind="logical-schemas"><thead><tr><th>{t('schemas.name')}</th><th>{t('schemas.code')}</th><th>{t('schemas.description')}</th></tr></thead><tbody>{filteredItems.map((item) => <tr key={item.uuid} onClick={() => setSelected(item)}><td><Button tone="ghost" onClick={() => setSelected(item)}>{item.name}</Button></td><td><code>{item.code}</code></td><td>{item.description ?? t('common.noDescription')}</td></tr>)}</tbody></DataGrid></div>}
    {selected && <ContextRecordDialog key={selected.uuid} item={selected} kind="logical" onClose={() => setSelected(null)} onSaved={(deleted) => { setNotice(t(deleted ? 'common.deletedSuccessfully' : 'common.savedSuccessfully')); void load() }} />}
    <Dialog open={canManage && open} title={t('schemas.addLogical')} closeLabel={t('common.close')} busy={busy} onClose={() => setOpen(false)}>
      <form onSubmit={(event) => void create(event)}>
        <label>{t('schemas.name')}<AntInput name="name" required /></label>
        <label>{t('schemas.code')}<AntInput name="code" pattern="[A-Za-z][A-Za-z0-9_]{0,99}" required /></label>
        <label>{t('schemas.description')}<AntInput.TextArea name="description" rows={3} /></label>
        <fieldset className="logical-schema-binding-fields">
          <legend>{t('schemas.initialMapping')}</legend>
          <p>{t('schemas.initialMappingHint')}</p>
          {!prerequisitesReady ? <div className="logical-schema-prerequisite" role="alert">
            <strong>{t('schemas.mappingPrerequisiteMissing')}</strong>
            <span>{t('schemas.mappingPrerequisiteMissingHint')}</span>
          </div> : <>
            <label>{t('schemas.environment')}<FormSelect required value={environmentUuid} onChange={(event) => setEnvironmentUuid(event.target.value)}>{environments.map((environment) => <option key={environment.uuid} value={environment.uuid}>{environment.name} · {environment.code}</option>)}</FormSelect></label>
            <label>{t('schemas.physicalSchema')}<FormSelect required value={physicalSchemaUuid} onChange={(event) => setPhysicalSchemaUuid(event.target.value)}><option value="">{t('schemas.choosePhysicalSchema')}</option>{physicalSchemas.map((physical) => <option key={physical.uuid} value={physical.uuid}>{physicalLabel(physical)}</option>)}</FormSelect></label>
          </>}
        </fieldset>
        <footer><Button type="button" onClick={() => setOpen(false)}>{t('common.cancel')}</Button><Button type="submit" tone="primary" busy={busy} disabled={!prerequisitesReady || !environmentUuid || !physicalSchemaUuid}>{t('schemas.createAndMap')}</Button></footer>
      </form>
    </Dialog>
  </section>
}
