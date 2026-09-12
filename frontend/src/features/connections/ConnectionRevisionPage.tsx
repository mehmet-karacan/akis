import { ArrowLeft } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router-dom'
import { AsyncState, PageHeader, StatusBadge } from '../../core/ui'
import { ConnectionVersionLifecyclePanel } from '../topology/ConnectionVersionLifecyclePanel'
import { topologyApi, type Connection, type ConnectionVersion } from '../topology/api'
import { getTopologyCopy } from '../topology/copy'
import { endpointLabel } from './catalog'

export function ConnectionRevisionPage() {
  const { projectUuid = '', connectionUuid = '', revisionUuid = '' } = useParams()
  const { t, i18n } = useTranslation()
  const copy = useMemo(() => getTopologyCopy(i18n.resolvedLanguage ?? i18n.language), [i18n.language, i18n.resolvedLanguage])
  const [connection, setConnection] = useState<Connection | null>(null)
  const [versions, setVersions] = useState<ConnectionVersion[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const load = useCallback(async () => {
    setLoading(true); setError('')
    try { const [nextConnection, nextVersions] = await Promise.all([topologyApi.getConnection(projectUuid, connectionUuid), topologyApi.listVersions(projectUuid, connectionUuid)]); setConnection(nextConnection); setVersions(nextVersions) }
    catch { setError(t('common.loadError')) }
    finally { setLoading(false) }
  }, [connectionUuid, projectUuid, t])
  useEffect(() => { void load() }, [load])
  const version = versions.find((item) => item.uuid === revisionUuid)
  if (loading) return <AsyncState state="loading" title={t('common.loading')} />
  if (error || !connection || !version) return <AsyncState state="error" title={error || t('connections.revisionNotFound')} retryLabel={t('common.retry')} onRetry={() => void load()} />
  const policy = version.policy as Partial<Record<'connectTimeoutMs' | 'readTimeoutMs' | 'networkTimeoutMs' | 'queryTimeoutSeconds', number>> | undefined
  const lifecycleText = version.lifecycleStatus === 'ACTIVE' ? t('connections.active') : version.lifecycleStatus === 'TESTED' ? t('connections.tested') : t('connections.draft')
  const runtimeText = version.runtimeCapability === 'EXECUTABLE' ? t('connections.executable') : t('connections.testOnly')
  return <section className="page-stack connection-detail-page">
    <Link className="connection-back-link" to={`/projects/${projectUuid}/connections/${connectionUuid}`}><ArrowLeft size={16} />{connection.name}</Link>
    <PageHeader title={`${t('connections.revision')} r${version.versionNumber}`} description={endpointLabel(version)} eyebrow={connection.code} actions={<StatusBadge tone={version.lifecycleStatus === 'ACTIVE' ? 'success' : version.lifecycleStatus === 'TESTED' ? 'info' : 'warning'}>{lifecycleText}</StatusBadge>} />
    <div className="connection-revision-grid"><section className="connection-detail-section"><h2>{t('connections.technicalDetails')}</h2><dl className="revision-details"><div><dt>{t('connections.mode')}</dt><dd>{version.mode}</dd></div><div><dt>{t('connections.username')}</dt><dd>{version.username ?? '—'}</dd></div><div><dt>{t('connections.endpoint')}</dt><dd>{endpointLabel(version)}</dd></div><div><dt>{t('connections.runtimeCapability')}</dt><dd>{runtimeText}</dd></div><div><dt>{t('connections.createdAt')}</dt><dd>{new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(version.createdAt))}</dd></div></dl></section><section className="connection-detail-section"><h2>{t('connections.timeouts')}</h2><dl className="revision-details"><div><dt>Connect</dt><dd>{policy?.connectTimeoutMs ?? '—'} ms</dd></div><div><dt>Read</dt><dd>{policy?.readTimeoutMs ?? '—'} ms</dd></div><div><dt>Network</dt><dd>{policy?.networkTimeoutMs ?? '—'} ms</dd></div><div><dt>Query</dt><dd>{policy?.queryTimeoutSeconds ?? '—'} s</dd></div></dl></section></div>
    <ConnectionVersionLifecyclePanel projectUuid={projectUuid} connectionUuid={connectionUuid} version={version} copy={copy} locale={i18n.language} onVersionChanged={load} />
  </section>
}
