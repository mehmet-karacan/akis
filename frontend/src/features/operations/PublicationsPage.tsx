import { Link, useParams } from 'react-router-dom'
import { operationsApi } from './api'
import { EmptyState, ErrorState, LoadingState, PageHeader, Panel, StatusBadge } from './OperationsUi'
import { useOperationsI18n } from './i18n'
import { apiErrorMessage, formatDate } from './utils'
import { useRemoteData } from './useRemoteData'
import { executionCodeLabel } from '../execution/i18n'

export function PublicationsPage() {
  const { projectUuid = '' } = useParams()
  const { t, locale } = useOperationsI18n()
  const publications = useRemoteData(
    () => operationsApi.listPublications(projectUuid),
    [projectUuid],
  )
  return (
    <section className="ops-page">
      <PageHeader
        title={t('publications')}
        description={t('publicationsHelp')}
      />

      <Panel>
        {publications.loading ? <LoadingState /> : null}
        {!publications.loading && publications.error ? (
          <ErrorState
            message={apiErrorMessage(publications.error, t('requestFailed'))}
            onRetry={() => void publications.reload()}
          />
        ) : null}
        {!publications.loading && !publications.error && publications.data?.length === 0 ? (
          <EmptyState>{t('emptyPublications')}</EmptyState>
        ) : null}
        {!publications.loading && publications.data && publications.data.length > 0 ? (
          <div className="ops-table-wrap">
            <table className="ops-table">
              <thead>
                <tr>
                  <th scope="col">{t('number')}</th>
                  <th scope="col">{t('environment')}</th>
                  <th scope="col">{t('status')}</th>
                  <th scope="col">{t('releaseHash')}</th>
                  <th scope="col">{t('createdAt')}</th>
                  <th scope="col" className="ops-actions-column">{t('actions')}</th>
                </tr>
              </thead>
              <tbody>
                {publications.data.map((publication) => (
                  <tr key={publication.uuid}>
                    <td>#{publication.publicationNumber}</td>
                    <td>
                      {publication.environmentCode}
                      <span className="ops-cell-secondary">{executionCodeLabel(publication.environmentRisk, locale)}</span>
                    </td>
                    <td><StatusBadge value={publication.status} /></td>
                    <td><code title={publication.releaseHash}>{publication.releaseHash.slice(0, 12)}…</code></td>
                    <td>{formatDate(publication.createdAt, locale)}</td>
                    <td>
                      <Link
                        className="ops-link"
                        to={`/projects/${encodeURIComponent(projectUuid)}/publications/${publication.uuid}`}
                      >
                        {t('viewDetails')}
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Panel>

    </section>
  )
}
