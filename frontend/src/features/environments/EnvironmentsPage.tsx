import { Plus } from 'lucide-react'
import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { AsyncState, Button, Dialog, PageHeader } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { topologyApi, type Environment } from '../topology/api'
import '../schemas/schemas.css'

export function EnvironmentsPage() {
  const { projectUuid = '' } = useParams(); const { t } = useTranslation(); const { can } = useProjectAccess(); const canManage = can('BAGLANTI_YONET')
  const [items, setItems] = useState<Environment[]>([]); const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [open, setOpen] = useState(false); const [busy, setBusy] = useState(false)
  const load = useCallback(async () => { setLoading(true); setError(''); try { setItems(await topologyApi.listEnvironments(projectUuid)) } catch { setError(t('common.loadError')) } finally { setLoading(false) } }, [projectUuid, t])
  useEffect(() => { void load() }, [load])
  async function create(event: FormEvent<HTMLFormElement>) { event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(true); setError(''); try { await topologyApi.createEnvironment(projectUuid, { code: String(data.get('code')).trim().toUpperCase(), name: String(data.get('name')).trim() }); setOpen(false); await load() } catch (reason) { setError(reason instanceof Error ? reason.message : t('common.saveError')) } finally { setBusy(false) } }
  return <section className="page-stack schema-page"><PageHeader eyebrow={t('schemas.eyebrow')} title={t('schemas.environmentsTitle')} description={t('schemas.environmentsDescription')} actions={canManage ? <Button tone="primary" icon={<Plus size={16} />} onClick={() => setOpen(true)}>{t('schemas.addEnvironment')}</Button> : undefined} />{error ? <div className="error-banner" role="alert">{error}</div> : null}{loading ? <AsyncState state="loading" title={t('common.loading')} /> : items.length === 0 ? <AsyncState state="empty" title={t('schemas.noEnvironments')} /> : <div className="schema-list-table"><table><thead><tr><th>{t('schemas.name')}</th><th>{t('schemas.code')}</th></tr></thead><tbody>{items.map((item) => <tr key={item.uuid}><td><Link className="schema-name-link" to={`/projects/${projectUuid}/environments/${item.uuid}`}>{item.name}</Link></td><td><code>{item.code}</code></td></tr>)}</tbody></table></div>}<Dialog open={canManage && open} title={t('schemas.addEnvironment')} closeLabel={t('common.close')} busy={busy} onClose={() => setOpen(false)}><form onSubmit={(event) => void create(event)}><label>{t('schemas.name')}<input name="name" required /></label><label>{t('schemas.code')}<input name="code" pattern="[A-Za-z][A-Za-z0-9_]{0,99}" required /></label><p className="form-note">{t('schemas.environmentSimpleHint')}</p><footer><Button type="button" onClick={() => setOpen(false)}>{t('common.cancel')}</Button><Button type="submit" tone="primary" busy={busy}>{t('schemas.create')}</Button></footer></form></Dialog></section>
}
