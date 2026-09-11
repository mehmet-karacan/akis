import { PowerOff } from 'lucide-react'
import { useExecutionI18n } from './i18n'

export function ExecutionDisabledNotice({ reason = 'requests' }: { reason?: 'requests' | 'worker' }) {
  const { t } = useExecutionI18n()
  const title = reason === 'worker' ? t('executionWorkerUnavailableTitle') : t('executionDisabledTitle')
  const body = reason === 'worker' ? t('executionWorkerUnavailableBody') : t('executionDisabledBody')
  return (
    <div className="execution-disabled" role="status">
      <PowerOff aria-hidden="true" />
      <div><strong>{title}</strong><p>{body}</p></div>
    </div>
  )
}
