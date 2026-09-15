import { useCallback } from 'react'
import { apiRequest, jsonBody } from '../../core/api/client'
import { SqlEditor } from '../../core/ui'
import type { ComponentProps } from 'react'
import { useDefinitionsI18n } from './i18n'

/** Policy validation never executes the command or tests a database connection. */
export function ProcedureSqlEditor({ projectUuid, role, ...props }: ComponentProps<typeof SqlEditor> & { projectUuid: string; role: string }) {
  const { t } = useDefinitionsI18n()
  const validateSql = useCallback(async (command: string) => {
    await apiRequest(`/api/v1/projects/${encodeURIComponent(projectUuid)}/sql/validate`, {
      method: 'POST', ...jsonBody({ command, connectionRole: role }),
    })
    return []
  }, [projectUuid, role])
  return <SqlEditor {...props} validateSql={validateSql} checkLabel={t('sqlCheck')} checkedLabel={t('sqlPolicyChecked')} />
}
