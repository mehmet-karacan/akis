import type { CSSProperties } from 'react'
import { CheckCircle2, CircleAlert, Clock3, Database, GitBranch, Network, Plug, Server, UserRound } from 'lucide-react'
import type { RecordAudit } from '../../core/ui/useRecordAudit'
import type { RecordField } from '../../core/ui/RecordFields'
import type { ConnectionCatalogItem } from './catalog'
import { recordAuditPresentation } from '../../core/ui/recordAuditPresentation'
import { databaseProviderVisual } from '../topology/DatabaseProviderIcon'

/** Same colored-tag treatment as the reference schema-metadata catalog (see schema-metadata/SchemaMetadataPage.tsx metadataTagStyles). */
export const connectionStatusTagStyles: Record<'success' | 'warning' | 'danger' | 'neutral', CSSProperties> = {
  success: {
    color: 'var(--schema-color-success)',
    borderColor: 'color-mix(in srgb, var(--schema-color-success) 38%, var(--line))',
    background: 'color-mix(in srgb, var(--schema-color-success) 11%, var(--surface))',
  },
  warning: {
    color: 'var(--schema-color-warning)',
    borderColor: 'color-mix(in srgb, var(--schema-color-warning) 38%, var(--line))',
    background: 'color-mix(in srgb, var(--schema-color-warning) 11%, var(--surface))',
  },
  danger: {
    color: 'var(--danger)',
    borderColor: 'color-mix(in srgb, var(--danger) 38%, var(--line))',
    background: 'color-mix(in srgb, var(--danger) 11%, var(--surface))',
  },
  neutral: {
    color: 'var(--text-muted, var(--text))',
    borderColor: 'var(--line)',
    background: 'var(--surface)',
  },
}

export interface ConnectionPresentation {
  identity: { name: string; code: string; provider: string; description: string }
  values: { host: string | number; port: string | number; service: string | number; username: string | number; mode: string | number }
  fields: RecordField[]
  status: { tested: boolean; text: string }
  lastTest: string
  audit: { createdBy: string; createdAt: string; updatedBy: string; updatedAt: string }
}

export function createConnectionPresentation(item: ConnectionCatalogItem, audit: RecordAudit | undefined, locale: string, auditState: 'loading' | 'ready' | 'error' = 'ready'): ConnectionPresentation {
  const tr = locale.startsWith('tr')
  const { connection, physicalSchemaCount, logicalSchemaCount } = item
  const version = connection
  const notConfigured = tr ? 'Tanımlanmadı' : 'Not configured'
  const notTested = tr ? 'Test Edilmedi' : 'Not tested'
  const auditValues = recordAuditPresentation(audit, auditState, locale)
  const date = (value: string) => new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  const tested = Boolean(connection.lastTestedAt) && connection.lastTestPassed === true
  const testedAt = connection.lastTestedAt ?? null
  const statusText = tested ? (tr ? 'Test Başarılı' : 'Test passed') : connection.lastTestedAt ? (tr ? 'Test Başarısız' : 'Test failed') : notTested
  const fields: RecordField[] = [
    { icon: <Server />, label: tr ? 'Sunucu' : 'Host', value: version.host || notConfigured },
    { icon: <Network />, label: 'Port', value: version.port ?? notConfigured },
    { icon: <Database />, label: version.sid ? 'SID' : tr ? 'Servis Adı' : 'Service name', value: version.sid || version.serviceName || version.databaseName || notConfigured },
    { icon: <UserRound />, label: tr ? 'Kullanıcı Adı' : 'Username', value: version.username || notConfigured },
    { icon: <Database />, label: tr ? 'Fiziksel Şema' : 'Physical schemas', value: physicalSchemaCount, tone: 'info' },
    { icon: <GitBranch />, label: tr ? 'Mantıksal Şema' : 'Logical schemas', value: logicalSchemaCount, tone: 'info' },
    { icon: <Plug />, label: tr ? 'Bağlantı Türü' : 'Connection type', value: version.mode || notConfigured },
    { icon: tested ? <CheckCircle2 /> : <CircleAlert />, label: tr ? 'Test Durumu' : 'Test status', value: statusText, tone: tested ? 'success' : 'warning' },
    { icon: <Clock3 />, label: tr ? 'Son Test Zamanı' : 'Last tested', value: testedAt ? date(testedAt) : notTested },
  ]
  if (version.mode === 'JNDI') fields.splice(0, 3, { icon: <Server />, label: tr ? 'JNDI Adı' : 'JNDI name', value: version.jndiName || notConfigured })
  return {
    identity: { name: connection.name, code: connection.code, provider: databaseProviderVisual(connection.databaseType).label, description: connection.description || notConfigured },
    values: version.mode === 'JNDI'
      ? { host: version.jndiName || notConfigured, port: notConfigured, service: notConfigured, username: notConfigured, mode: version.mode }
      : { host: version.host || notConfigured, port: version.port ?? notConfigured, service: version.sid || version.serviceName || version.databaseName || notConfigured, username: version.username || notConfigured, mode: version.mode || notConfigured },
    fields,
    status: { tested, text: statusText },
    lastTest: testedAt ? date(testedAt) : notTested,
    audit: auditValues,
  }
}
