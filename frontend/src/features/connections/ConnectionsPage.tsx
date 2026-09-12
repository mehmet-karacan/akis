import { Plus, Search } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, FilterBar, PageHeader } from '../../core/ui'
import { useProjectAccess } from '../../core/auth/ProjectAccessContext'
import { topologyApi } from '../topology/api'
import type { ConnectionCatalogItem } from './catalog'
import { ConnectionsTable } from './ConnectionsTable'
import './connections.css'

const pageSize = 25

export function ConnectionsPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t, i18n } = useTranslation()
  const [params, setParams] = useSearchParams()
  const [catalog, setCatalog] = useState<ConnectionCatalogItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const { can } = useProjectAccess()

  const load = useCallback(async () => {
    setLoading(true); setError('')
    try {
      const items = await topologyApi.listConnectionCatalog(projectUuid)
      setCatalog(items.map((item) => ({
        connection: item.connection,
        displayedVersion: item.displayedVersion ?? undefined,
        latestVersionNumber: item.latestVersionNumber ?? undefined,
        physicalSchemaCount: item.physicalSchemaCount,
        logicalSchemaCount: item.logicalSchemaCount,
      })))
    } catch { setError(t('common.loadError')) }
    finally { setLoading(false) }
  }, [projectUuid, t])

  useEffect(() => { void load() }, [load])
  const query = params.get('q') ?? ''
  const provider = params.get('provider') ?? 'ALL'
  const sort = params.get('sort') ?? 'name'
  const page = Math.max(1, Number(params.get('page') ?? 1) || 1)
  const updateParam = (key: string, value: string) => setParams((current) => { const next = new URLSearchParams(current); if (!value || value === 'ALL' || (key === 'page' && value === '1')) next.delete(key); else next.set(key, value); if (key !== 'page') next.delete('page'); return next })

  const filtered = useMemo(() => catalog
    .filter((item) => provider === 'ALL' || item.connection.databaseType === provider)
    .filter((item) => `${item.connection.name} ${item.connection.code} ${item.connection.databaseType}`.toLocaleLowerCase(i18n.language).includes(query.trim().toLocaleLowerCase(i18n.language)))
    .sort((a, b) => sort === 'code' ? a.connection.code.localeCompare(b.connection.code, i18n.language) : a.connection.name.localeCompare(b.connection.name, i18n.language)), [catalog, i18n.language, provider, query, sort])
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize))
  const visible = filtered.slice((Math.min(page, pages) - 1) * pageSize, Math.min(page, pages) * pageSize)
  const labels = { provider: t('connections.provider'), connection: t('connections.connection'), endpoint: t('connections.endpoint'), physical: t('connections.physicalShort'), logical: t('connections.logicalShort'), status: t('connections.status'), actions: t('connections.actions'), open: t('connections.open'), ready: t('connections.ready'), testRequired: t('connections.testRequired') }

  return <section className="page-stack connections-page">
    <PageHeader eyebrow={t('connections.eyebrow')} title={t('connections.title')} description={t('connections.description')} actions={can('BAGLANTI_YONET') ? <Link className="button primary" to={`/projects/${projectUuid}/connections/new`}><Plus size={16} />{t('connections.add')}</Link> : undefined} />
    <FilterBar>
      <label className="connections-search"><Search size={16} /><span className="sr-only">{t('connections.search')}</span><input value={query} onChange={(event) => updateParam('q', event.target.value)} placeholder={t('connections.searchPlaceholder')} /></label>
      <label><span className="sr-only">{t('connections.provider')}</span><select value={provider} onChange={(event) => updateParam('provider', event.target.value)}><option value="ALL">{t('connections.allProviders')}</option><option value="ORACLE">Oracle</option></select></label>
      <label><span className="sr-only">{t('connections.sort')}</span><select value={sort} onChange={(event) => updateParam('sort', event.target.value)}><option value="name">{t('connections.sortName')}</option><option value="code">{t('connections.sortCode')}</option></select></label>
    </FilterBar>
    {loading ? <AsyncState state="loading" title={t('common.loading')} /> : error ? <AsyncState state="error" title={error} retryLabel={t('common.retry')} onRetry={() => void load()} /> : filtered.length === 0 ? <AsyncState state="empty" title={t('connections.empty')} description={t('connections.emptyHint')} /> : <><ConnectionsTable projectUuid={projectUuid} items={visible} labels={labels} /><footer className="connections-pagination"><span>{t('connections.resultCount', { count: filtered.length })}</span><div><button className="button secondary" disabled={page <= 1} onClick={() => updateParam('page', String(page - 1))}>{t('connections.previous')}</button><span>{Math.min(page, pages)} / {pages}</span><button className="button secondary" disabled={page >= pages} onClick={() => updateParam('page', String(page + 1))}>{t('connections.next')}</button></div></footer></>}
  </section>
}
