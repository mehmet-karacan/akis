import type { Connection, ConnectionVersion, Environment, LogicalSchema, PhysicalSchema, SchemaBinding } from '../topology/api'
import { useDefinitionsI18n } from './i18n'

export function resolveProcedureContext(logicalSchemaUuid: string | undefined, environmentUuid: string | undefined, bindings: SchemaBinding[], physical: PhysicalSchema[], connections: Connection[], versions: ConnectionVersion[]) {
  const binding = bindings.find((item) => item.logicalSchemaUuid === logicalSchemaUuid && item.environmentUuid === environmentUuid)
  const physicalSchema = physical.find((item) => item.uuid === binding?.physicalSchemaUuid)
  return { binding, physicalSchema, connection: connections.find((item) => item.uuid === physicalSchema?.connectionUuid), version: versions.find((item) => item.uuid === binding?.connectionVersionUuid) }
}

export function ResolvedContextSummary({ logicalSchema, environment, context }: {
  logicalSchema?: LogicalSchema
  environment?: Environment
  context: ReturnType<typeof resolveProcedureContext>
}) {
  const { t } = useDefinitionsI18n()
  return <section className={`resolved-context ${context.binding ? '' : 'is-missing'}`} aria-label={t('resolvedContext')}><header><strong>{t('resolvedContext')}</strong><span>{context.binding ? t('contextResolved') : t('contextMissing')}</span></header><dl>
    <div><dt>{t('logicalSchema')}</dt><dd>{logicalSchema?.name ?? '—'}</dd></div><div><dt>{t('environment')}</dt><dd>{environment?.name ?? '—'}</dd></div><div><dt>{t('resolvedConnection')}</dt><dd>{context.connection?.name ?? '—'}</dd></div><div><dt>{t('resolvedPhysicalSchema')}</dt><dd>{context.physicalSchema?.schemaReference ?? '—'}</dd></div>
  </dl></section>
}
