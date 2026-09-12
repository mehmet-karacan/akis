import { Plus } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, Button, Dialog, PageHeader, StatusBadge } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { topologyApi, type LogicalSchema, type Model } from '../topology/api'
import './models.css'

export function ModelsPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t, i18n } = useTranslation()
  const { can } = useProjectAccess()
  const canManage = can('BAGLANTI_YONET')
  const [models, setModels] = useState<Model[]>([])
  const [logicalSchemas, setLogicalSchemas] = useState<LogicalSchema[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const load = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const [nextModels, nextSchemas] = await Promise.all([topologyApi.listModels(projectUuid), topologyApi.listLogicalSchemas(projectUuid)])
      setModels(nextModels); setLogicalSchemas(nextSchemas)
    } catch { setError(t('common.loadError')) }
    finally { setLoading(false) }
  }, [projectUuid, t])
  useEffect(() => { void load() }, [load])
  const filtered = useMemo(() => {
    const text = query.trim().toLocaleLowerCase(i18n.language)
    return text ? models.filter((model) => `${model.name} ${model.code}`.toLocaleLowerCase(i18n.language).includes(text)) : models
  }, [i18n.language, models, query])
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(true); setError('')
    try {
      await topologyApi.createModel(projectUuid, { logicalSchemaUuid: String(data.get('logicalSchemaUuid')), name: String(data.get('name')).trim(), code: String(data.get('code')).trim().toUpperCase(), description: String(data.get('description')).trim() || undefined })
      setOpen(false); await load()
    } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) }
    finally { setBusy(false) }
  }
  const formatDate = (value?: string | null) => value ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : t('models.notImported')
  const statusLabel = (status: string) => status === 'AKTIF' ? t('models.statusActive') : t('models.statusInactive')

  return <section className="page-stack models-page">
    <PageHeader eyebrow={t('models.eyebrow')} title={t('models.title')} description={t('models.description')} actions={canManage ? <Button tone="primary" icon={<Plus size={16} />} disabled={logicalSchemas.length === 0} onClick={() => setOpen(true)}>{t('models.create')}</Button> : undefined} />
    {error ? <div className="error-banner" role="alert">{error}</div> : null}
    {!loading && logicalSchemas.length === 0 ? <div className="models-prerequisite"><strong>{t('models.logicalRequired')}</strong><Link to={`/projects/${projectUuid}/logical-schemas`}>{t('models.openLogicalSchemas')}</Link></div> : null}
    {loading ? <AsyncState state="loading" title={t('common.loading')} /> : models.length === 0 ? <AsyncState state="empty" title={t('models.empty')} description={t('models.emptyHint')} action={canManage && logicalSchemas.length ? <Button tone="primary" onClick={() => setOpen(true)}>{t('models.create')}</Button> : undefined} /> : <>
      <label className="models-search"><span>{t('models.search')}</span><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t('models.searchPlaceholder')} /></label>
      <div className="model-table-wrap"><table><thead><tr><th>{t('models.name')}</th><th>{t('models.logicalSchema')}</th><th>{t('models.objectCount')}</th><th>{t('models.lastMetadataUpdate')}</th><th>{t('models.status')}</th></tr></thead><tbody>{filtered.map((model) => <tr key={model.uuid}>
        <td><Link className="model-name-link" to={`/projects/${projectUuid}/models/${model.uuid}`}><strong>{model.name}</strong><small>{model.code}</small></Link></td>
        <td>{logicalSchemas.find((schema) => schema.uuid === model.logicalSchemaUuid)?.name ?? '—'}</td><td>{model.dataObjectCount ?? 0}</td><td>{formatDate(model.lastMetadataUpdate)}</td><td><StatusBadge tone={model.status === 'AKTIF' ? 'success' : 'neutral'}>{statusLabel(model.status)}</StatusBadge></td>
      </tr>)}</tbody></table></div>
    </>}
    <Dialog open={canManage && open} title={t('models.create')} closeLabel={t('common.close')} busy={busy} onClose={() => setOpen(false)}><form onSubmit={(event) => void create(event)}>
      <label>{t('models.name')}<input name="name" required /></label><label>{t('models.code')}<input name="code" pattern="[A-Za-z][A-Za-z0-9_]{0,99}" required /></label>
      <label>{t('models.logicalSchema')}<select name="logicalSchemaUuid" required>{logicalSchemas.map((schema) => <option key={schema.uuid} value={schema.uuid}>{schema.name}</option>)}</select></label>
      <label>{t('models.descriptionField')}<textarea name="description" rows={3} /></label><footer><Button type="button" onClick={() => setOpen(false)}>{t('common.cancel')}</Button><Button type="submit" tone="primary" busy={busy}>{t('models.create')}</Button></footer>
    </form></Dialog>
  </section>
}
