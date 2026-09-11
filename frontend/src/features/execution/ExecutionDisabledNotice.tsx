import { PowerOff } from 'lucide-react'
import { useExecutionI18n } from './i18n'

export function ExecutionDisabledNotice() {
  const { t } = useExecutionI18n()
  return (
    <div className="execution-disabled" role="status">
      <PowerOff aria-hidden="true" />
      <div><strong>{t('executionDisabledTitle')}</strong><p>{t('executionDisabledBody')}</p></div>
    </div>
  )
}
