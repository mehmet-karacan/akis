import { ArrowLeft } from 'lucide-react'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router-dom'
import { useCurrentProjectUuid } from '../projects/CurrentProjectContext'
import { PageHeader } from '../../core/ui'
import { getTopologyCopy } from '../topology/copy'
import { OracleConnectionCreateForm } from '../topology/OracleConnectionCreateForm'
import './connections.css'

export function ConnectionCreatePage() {
  const projectUuid = useCurrentProjectUuid()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const copy = useMemo(() => getTopologyCopy(i18n.resolvedLanguage ?? i18n.language), [i18n.language, i18n.resolvedLanguage])
  return <section className="page-stack connection-form-page">
    <Link className="connection-back-link" to={`/projects/${projectUuid}/connections`}><ArrowLeft size={16} />{t('connections.back')}</Link>
    <PageHeader title={t('connections.createTitle')} description={t('connections.createDescription')} />
    <div className="connection-form-surface"><OracleConnectionCreateForm projectUuid={projectUuid} copy={copy} onClose={() => navigate(`/projects/${projectUuid}/connections`)} onConnectionCreated={async (connectionUuid) => { navigate(`/projects/${projectUuid}/connections/${connectionUuid}`, { replace: true }) }} /></div>
  </section>
}
