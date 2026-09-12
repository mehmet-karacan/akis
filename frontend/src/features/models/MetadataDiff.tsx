import { useTranslation } from 'react-i18next'
import type { MetadataChange } from './metadataComparison'

export function MetadataDiff({ changes }: { changes: MetadataChange[] }) {
  const { t } = useTranslation()
  if (changes.length === 0) return <p className="metadata-diff-empty">{t('models.noMetadataChanges')}</p>
  return <section className="metadata-diff" aria-label={t('models.metadataChanges')}>
    <header><strong>{t('models.metadataChanges')}</strong><span>{changes.length}</span></header>
    <ul>{changes.map((change) => <li key={`${change.kind}:${change.name}`} className={`metadata-change metadata-change--${change.kind.toLowerCase()}`}>
      <span>{t(`models.change${change.kind}`)}</span><code>{change.name}</code>
      {change.kind === 'CHANGED' ? <small>{change.before?.producerType} → {change.after?.producerType}</small> : null}
    </li>)}</ul>
    {changes.some((change) => change.kind === 'REMOVED') ? <p className="metadata-impact-warning">{t('models.removedImpactWarning')}</p> : null}
  </section>
}
