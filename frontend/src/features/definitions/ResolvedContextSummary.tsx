import type { Connection, Environment, LogicalSchema, PhysicalSchema, SchemaBinding } from '../topology/api'
import { useDefinitionsI18n } from './i18n'

export function resolveProcedureContext(logicalSchemaUuid: string | undefined, environmentUuid: string | undefined, bindings: SchemaBinding[], physical: PhysicalSchema[], connections: Connection[]) {
  const binding = bindings.find((item) => item.logicalSchemaUuid === logicalSchemaUuid && item.environmentUuid === environmentUuid)
  const physicalSchema = physical.find((item) => item.uuid === binding?.physicalSchemaUuid)
  return { binding, physicalSchema, connection: connections.find((item) => item.uuid === physicalSchema?.connectionUuid) }
}

export function ResolvedContextSummary({ logicalSchema, environment, context }: {
  logicalSchema?: LogicalSchema
  environment?: Environment
  context: ReturnType<typeof resolveProcedureContext>
}) {
  const { t } = useDefinitionsI18n()
  return <section className={`resolved-context ${context.binding ? '' : 'is-missing'}`} aria-label={t('resolvedContext')}><header><strong>{t('resolvedContext')}</strong><span>{context.binding ? t('contextResolved') : t('contextMissing')}</span></header><dl>
    <div><dt>{t('logicalSchema')}</dt><dd>{logicalSchema?.name ?? t('notConfigured')}</dd></div><div><dt>{t('environment')}</dt><dd>{environment?.name ?? t('notConfigured')}</dd></div><div><dt>{t('resolvedConnection')}</dt><dd>{context.connection?.name ?? t('notConfigured')}</dd></div><div><dt>{t('resolvedPhysicalSchema')}</dt><dd>{context.physicalSchema?.schemaName ?? t('notConfigured')}</dd></div>
  </dl></section>
}
