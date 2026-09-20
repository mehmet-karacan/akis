import { Tag } from 'antd'
import { CheckCircle2, CircleAlert, CircleX, Hourglass, Rocket, ShieldAlert } from 'lucide-react'
import { useMemo } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { AsyncState, PageHeader, RecordActionButton, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { connectionStatusTagStyles } from '../connections/presentation'
import { definitionsApi } from '../definitions/api'
import { executionCodeLabel } from '../execution/i18n'
import { operationsApi } from './api'
import { useOperationsI18n } from './i18n'
import type { Publication } from './types'
import { apiErrorMessage, formatDate } from './utils'
import { useRemoteData } from './useRemoteData'
import '../connections/connections.css'
import '../connections/catalog-layout.css'

type Tone = 'success' | 'warning' | 'danger' | 'neutral'
const toneOf = (status: string): Tone => ['AKTIF', 'ETKIN'].includes(status) ? 'success' : status === 'ONAY_BEKLIYOR' ? 'warning' : status === 'IPTAL' ? 'danger' : 'neutral'
const iconOf = (tone: Tone) => tone === 'success' ? <CheckCircle2 size={12} /> : tone === 'warning' ? <Hourglass size={12} /> : tone === 'danger' ? <CircleX size={12} /> : <CircleAlert size={12} />

/** Publications in the shared catalog layout; the record action opens the publication detail page. */
export function PublicationsPage() {
  const projectUuid = useCurrentProjectUuid()
  const { t, locale } = useOperationsI18n()
  const tr = locale.startsWith('tr')
  const navigate = useNavigate()
  const [view, setView] = useCollectionView('akis:publications:view')
  const [params, setParams] = useSearchParams()
  const publications = useRemoteData(() => operationsApi.listPublications(projectUuid), [projectUuid])
  const definitions = useRemoteData(() => definitionsApi.listDefinitions(projectUuid), [projectUuid])
  const definitionOf = (publication: Publication) => definitions.data?.find((item) => item.uuid === publication.definitionUuid)
  const statusText = (status: string) => { const key = `status_${status}` as Parameters<typeof t>[0]; return ['ONAY_BEKLIYOR', 'AKTIF', 'IPTAL', 'ETKIN'].includes(status) ? t(key) : status }

  const query = params.get('q') ?? ''
  const applyQuery = (next: string) => setParams(next ? { q: next } : {})
  const items = useMemo(() => (publications.data ?? [])
    .filter((publication) => { const definition = definitions.data?.find((item) => item.uuid === publication.definitionUuid); return `#${publication.publicationNumber} ${definition?.name ?? ''} ${definition?.code ?? ''} ${publication.environmentCode} ${publication.releaseHash}`.toLocaleLowerCase(locale).includes(query.trim().toLocaleLowerCase(locale)) })
    .sort((a, b) => b.publicationNumber - a.publicationNumber), [publications.data, definitions.data, query, locale])
  const all = publications.data ?? []

  return <section className="page-stack connections-page publications-page">
    <section className="connection-management-panel"><PageHeader icon={<Rocket />} eyebrow={tr ? 'YAYIN' : 'RELEASE'} title={t('publications')} description={t('publicationsHelp')} />
    <QueryFilter onApply={applyQuery} placeholder={tr ? 'Numara, tanım, ortam veya sürüm özetine göre ara' : 'Search by number, definition, environment or release hash'} /></section>
    <SummaryStrip ariaLabel={t('publications')} items={[
      { label: tr ? 'Toplam Yayın' : 'Total Publications', value: all.length, icon: <Rocket />, tone: 'info' },
      { label: t('status_AKTIF'), value: all.filter((item) => ['AKTIF', 'ETKIN'].includes(item.status)).length, icon: <CheckCircle2 />, tone: 'success' },
      { label: t('status_ONAY_BEKLIYOR'), value: all.filter((item) => item.status === 'ONAY_BEKLIYOR').length, icon: <Hourglass />, tone: 'warning' },
      { label: tr ? 'Üretim Yayını' : 'Production Releases', value: all.filter((item) => item.environmentRisk === 'URETIM').length, icon: <ShieldAlert />, tone: 'neutral' },
    ]} />
    <section className="connections-records">
    {publications.loading ? <AsyncState state="loading" title={t('loading')} /> : publications.error ? <AsyncState state="error" title={apiErrorMessage(publications.error, t('requestFailed'))} retryLabel={t('retry')} onRetry={() => void publications.reload()} /> : items.length === 0 ? <AsyncState state="empty" title={t('emptyPublications')} /> : <ProgressiveRecords key={query} items={items}>{(visible) => <DataGrid collectionTitle={tr ? 'Yayın Kataloğu' : 'Publication Catalog'} collectionIcon={<Rocket />} cardHeaderField="status" cardHiddenFields={['status']} headerFieldsInList view={view} onViewChange={setView}>
      <thead><tr><th data-field-key="name">{t('number')}</th><th data-field-key="definition">{tr ? 'Tanım' : 'Definition'}</th><th data-field-key="environment">{t('environment')}</th><th data-field-key="risk">{tr ? 'Risk' : 'Risk'}</th><th data-field-key="status">{t('status')}</th><th data-field-key="hash">{t('releaseHash')}</th><th data-field-key="publishedAt">{t('publishedAt')}</th><th data-field-key="createdAt">{t('createdAt')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{t('actions')}</span></th></tr></thead>
      <tbody>{visible.map((publication) => { const tone = toneOf(publication.status); const definition = definitionOf(publication); return <tr key={publication.uuid} data-connection-uuid={publication.uuid}>
        <td><span className="connection-record-identity"><strong>#{publication.publicationNumber}</strong><small>{publication.uuid.slice(0, 8)}</small></span></td>
        <td>{definition ? <span className="connection-record-identity"><strong>{definition.name}</strong><small>{definition.code}</small></span> : publication.definitionUuid.slice(0, 8)}</td>
        <td>{publication.environmentCode}</td>
        <td>{executionCodeLabel(publication.environmentRisk, locale)}</td>
        <td><Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={iconOf(tone)}><span className="connection-status-tag-label">{statusText(publication.status)}</span></Tag></td>
        <td><code title={publication.releaseHash}>{publication.releaseHash.slice(0, 12)}…</code></td>
        <td>{formatDate(publication.publishedAt, locale, '—')}</td>
        <td>{formatDate(publication.createdAt, locale)}</td>
        <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={`#${publication.publicationNumber}`} editable={false} onClick={() => navigate(`/project/publications/${publication.uuid}`)} /></div></td>
      </tr> })}</tbody>
    </DataGrid>}</ProgressiveRecords>}
    </section>
  </section>
}
