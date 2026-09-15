import { Button as AntActionButton } from '../../core/ui/Button'
import { DataGrid } from '../../core/ui/DataGrid'
import { Select as FormSelect } from '../../core/ui/Select'
import { Link2 } from 'lucide-react'
import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, Dialog, PageHeader } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import {
  topologyApi,
  type Connection,
  type Environment,
  type LogicalSchema,
  type PhysicalSchema,
  type SchemaBinding,
} from '../topology/api'
import { bindingForContext } from './bindingModel'
import './schemas.css'

export function SchemaBindingsPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t } = useTranslation()
  const { can } = useProjectAccess()
  const canManage = can('BAGLANTI_YONET')
  const [logical, setLogical] = useState<LogicalSchema[]>([])
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [physical, setPhysical] = useState<PhysicalSchema[]>([])
  const [connections, setConnections] = useState<Connection[]>([])
  const [bindings, setBindings] = useState<SchemaBinding[]>([])
  const [selection, setSelection] = useState<{ logicalUuid: string; environmentUuid: string } | null>(null)
  const [physicalUuid, setPhysicalUuid] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [nextLogical, nextEnvironments, nextPhysical, nextConnections, nextBindings] = await Promise.all([
        topologyApi.listLogicalSchemas(projectUuid),
        topologyApi.listEnvironments(projectUuid),
        topologyApi.listPhysicalSchemas(projectUuid),
        topologyApi.listConnections(projectUuid),
        topologyApi.listBindings(projectUuid),
      ])
      setLogical(nextLogical)
      setEnvironments(nextEnvironments)
      setPhysical(nextPhysical)
      setConnections(nextConnections)
      setBindings(nextBindings)
    } catch {
      setError(t('common.loadError'))
    } finally {
      setLoading(false)
    }
  }, [projectUuid, t])

  useEffect(() => { void load() }, [load])

  const bindingFor = (logicalUuid: string, environmentUuid: string) =>
    bindingForContext(bindings, logicalUuid, environmentUuid)

  const openEditor = (logicalUuid: string, environmentUuid: string) => {
    const existing = bindingFor(logicalUuid, environmentUuid)
    setSelection({ logicalUuid, environmentUuid })
    setPhysicalUuid(existing?.physicalSchemaUuid ?? '')
  }

  const physicalLabel = (schema?: PhysicalSchema) => {
    if (!schema) return '—'
    const connection = connections.find((item) => item.uuid === schema.connectionUuid)
    return `${connection?.code ?? '—'} / ${schema.schemaReference}`
  }

  const labelForBinding = (binding?: SchemaBinding) => {
    if (!binding) return t('schemas.notMapped')
    return physicalLabel(physical.find((item) => item.uuid === binding.physicalSchemaUuid))
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!selection) return
    const existing = bindingFor(selection.logicalUuid, selection.environmentUuid)
    setBusy(true)
    setError('')
    const body = {
      logicalSchemaUuid: selection.logicalUuid,
      environmentUuid: selection.environmentUuid,
      physicalSchemaUuid: physicalUuid,
      ...(existing ? { expectedVersion: existing.version } : {}),
    }
    try {
      if (existing) await topologyApi.updateBinding(projectUuid, existing.uuid, body)
      else await topologyApi.createBinding(projectUuid, body)
      setSelection(null)
      await load()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t('common.saveError'))
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <AsyncState state="loading" title={t('common.loading')} />

  return <section className="page-stack schema-page">
    <PageHeader eyebrow={t('schemas.eyebrow')} title={t('schemas.bindingsTitle')} description={t('schemas.bindingsDescription')} />
    {error ? <div className="error-banner" role="alert">{error}</div> : null}
    {logical.length === 0 || environments.length === 0
      ? <AsyncState state="empty" title={t('schemas.bindingPrerequisite')} description={t('schemas.bindingPrerequisiteHint')} />
      : <div className="binding-matrix-wrap"><DataGrid className="binding-matrix">
        <thead><tr><th>{t('schemas.logicalSchema')}</th>{environments.map((environment) => <th key={environment.uuid}>{environment.name}<small>{environment.code}</small></th>)}</tr></thead>
        <tbody>{logical.map((schema) => <tr key={schema.uuid}><th><strong>{schema.name}</strong><small>{schema.code}</small></th>{environments.map((environment) => {
          const binding = bindingFor(schema.uuid, environment.uuid)
          return <td key={environment.uuid}><AntActionButton tone="ghost" type="button" className={binding ? 'is-mapped' : ''} disabled={!canManage} onClick={() => openEditor(schema.uuid, environment.uuid)}><Link2 /><span>{labelForBinding(binding)}</span></AntActionButton></td>
        })}</tr>)}</tbody>
      </DataGrid></div>}
    <Dialog open={canManage && selection !== null} title={t('schemas.editBinding')} closeLabel={t('common.close')} busy={busy} onClose={() => setSelection(null)}>
      <form onSubmit={(event) => void save(event)}>
        <label>{t('schemas.physicalSchema')}<FormSelect required value={physicalUuid} onChange={(event) => setPhysicalUuid(event.target.value)}><option value="">—</option>{physical.map((item) => <option key={item.uuid} value={item.uuid}>{physicalLabel(item)}</option>)}</FormSelect></label>
        <footer><Button type="button" onClick={() => setSelection(null)}>{t('common.cancel')}</Button><Button type="submit" tone="primary" busy={busy} disabled={!physicalUuid}>{t('schemas.saveBinding')}</Button></footer>
      </form>
    </Dialog>
  </section>
}
