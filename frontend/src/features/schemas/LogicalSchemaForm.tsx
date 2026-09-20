import { Input } from 'antd'
import { CircleAlert, LoaderCircle, Save } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '../../core/ui'
import { Select as FormSelect } from '../../core/ui/Select'
import { topologyApi, type Connection, type Environment, type LogicalSchema, type PhysicalSchema } from '../topology/api'
import { DATABASE_TYPES } from '../topology/connectionFormModel'
import { databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { compatiblePhysicalSchemas } from './logicalCatalog'
import '../topology/topology.css'

interface Props {
  projectUuid: string
  environments: Environment[]
  physicalSchemas: PhysicalSchema[]
  connections: Connection[]
  onCreated(schema: LogicalSchema): Promise<void> | void
  onClose(): void
}

/** Creates a logical schema for one technology, optionally mapping it to a physical schema in one environment. */
export function LogicalSchemaForm({ projectUuid, environments, physicalSchemas, connections, onCreated, onClose }: Props) {
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [databaseType, setDatabaseType] = useState('')
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [description, setDescription] = useState('')
  const [environmentUuid, setEnvironmentUuid] = useState('')
  const [physicalSchemaUuid, setPhysicalSchemaUuid] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const compatible = compatiblePhysicalSchemas(databaseType, physicalSchemas)
  const physicalLabel = (physical: PhysicalSchema) => `${connections.find((item) => item.uuid === physical.connectionUuid)?.code ?? t('common.notConfigured')} / ${physical.schemaName}`
  const mappingPartial = (environmentUuid === '') !== (physicalSchemaUuid === '')

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!databaseType || !name.trim() || !/^[A-Za-z][A-Za-z0-9_]{0,99}$/.test(code) || mappingPartial) {
      setError(tr ? 'Sağlayıcı, ad ve kod zorunludur; eşleme için ortam ve fiziksel şema birlikte seçilmelidir.' : 'Provider, name and code are required; a mapping needs both an environment and a physical schema.')
      return
    }
    setBusy(true); setError('')
    try {
      const created = await topologyApi.createLogicalSchema(projectUuid, {
        code: code.trim().toUpperCase(), name: name.trim(), description: description.trim() || undefined, databaseType,
        ...(environmentUuid && physicalSchemaUuid ? { environmentUuid, physicalSchemaUuid } : {}),
      })
      await onCreated(created)
      onClose()
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }

  return <form className="topology-connection-form topology-connection-form--simple" onSubmit={(event) => void save(event)}>
    <fieldset disabled={busy} className="connection-editor-fields"><legend className="sr-only">{t('schemas.addLogical')}</legend>
    <div className="topology-form topology-form--grid">
      <label className="topology-field topology-field--wide"><span>{t('connections.provider')} *</span>
        <FormSelect required value={databaseType} onChange={(event) => { setDatabaseType(event.target.value); setPhysicalSchemaUuid('') }}>
          <option value="">{tr ? 'Sağlayıcı seçin' : 'Select a provider'}</option>
          {DATABASE_TYPES.map((type) => <option key={type} value={type}>{databaseProviderVisual(type).label}</option>)}
        </FormSelect>
      </label>
      {databaseType && <>
        <label className="topology-field"><span>{t('schemas.name')} *</span><Input required value={name} onChange={(e) => setName(e.target.value)} /></label>
        <label className="topology-field"><span>{t('schemas.code')} *</span><Input required pattern="[A-Za-z][A-Za-z0-9_]{0,99}" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} /></label>
        <label className="topology-field topology-field--wide"><span>{t('schemas.description')}</span><Input.TextArea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
        <label className="topology-field"><span>{t('schemas.environment')}</span>
          <FormSelect value={environmentUuid} onChange={(event) => setEnvironmentUuid(event.target.value)}>
            <option value="">{tr ? 'Şimdilik eşleme yok' : 'No mapping yet'}</option>
            {environments.map((environment) => <option key={environment.uuid} value={environment.uuid}>{environment.name} · {environment.code}</option>)}
          </FormSelect>
        </label>
        <label className="topology-field"><span>{t('schemas.physicalSchema')}</span>
          <FormSelect value={physicalSchemaUuid} onChange={(event) => setPhysicalSchemaUuid(event.target.value)}>
            <option value="">{t('schemas.choosePhysicalSchema')}</option>
            {compatible.map((physical) => <option key={physical.uuid} value={physical.uuid}>{physicalLabel(physical)}</option>)}
          </FormSelect>
        </label>
        {compatible.length === 0 && <p className="form-note topology-field--wide">{tr ? 'Bu sağlayıcı için tanımlı fiziksel şema yok; eşlemeyi sonra ekleyebilirsiniz.' : 'No physical schema exists for this provider yet; you can add the mapping later.'}</p>}
      </>}
    </div>
    </fieldset>
    {error && <div className="topology-inline-error" role="alert"><CircleAlert />{error}</div>}
    <footer className="topology-form-actions">
      <Button tone="ghost" className="topology-button topology-button--quiet" type="button" onClick={onClose}>{t('common.cancel')}</Button>
      {databaseType && <Button tone="primary" className="topology-button" type="submit" disabled={busy}>{busy ? <LoaderCircle className="is-spinning" /> : <Save />}{environmentUuid && physicalSchemaUuid ? t('schemas.createAndMap') : t('schemas.create')}</Button>}
    </footer>
  </form>
}
