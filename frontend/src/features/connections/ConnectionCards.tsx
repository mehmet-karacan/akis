import { RecordAuditFields } from '../../core/ui/RecordAuditFields'
import type { useRecordAudit } from '../../core/ui/useRecordAudit'
import { ConnectionTestButton } from './ConnectionTestButton'
import { Database, GitBranch, Server, Network, UserRound, Plug, Clock3, CheckCircle2, CircleAlert } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { RecordFields, type RecordField } from '../../core/ui/RecordFields'
import { DatabaseProviderIcon } from '../topology/DatabaseProviderIcon'
import type { ConnectionCatalogItem } from './catalog'
import { Card } from 'antd'
import { memo } from 'react'

export const ConnectionCards = memo(function ConnectionCards({ items, view, onOpen, audit }: { audit?: ReturnType<typeof useRecordAudit>; items: ConnectionCatalogItem[]; view: 'card' | 'list'; onOpen: (uuid: string) => void }) {
  const { t, i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const missing = tr ? 'Tanımlanmadı' : 'Not Configured'
  return <div className={`ui-collection ui-collection--${view}`}>{items.map(({ connection, displayedVersion: version, physicalSchemaCount, logicalSchemaCount }) => {
    const tested = Boolean(version?.testedAt)
    const fields: RecordField[] = [
      { icon: <Server />, label: tr ? 'Sunucu' : 'Host', value: version?.host || missing },
      { icon: <Network />, label: 'Port', value: version?.port ?? missing },
      { icon: <Database />, label: version?.sid ? 'SID' : tr ? 'Servis Adı' : 'Service Name', value: version?.sid || version?.serviceName || version?.databaseName || missing },
      { icon: <UserRound />, label: tr ? 'Kullanıcı Adı' : 'Username', value: version?.username || missing },
      { icon: <Database />, label: tr ? 'Fiziksel Şema' : 'Physical Schemas', value: physicalSchemaCount, tone: 'info' },
      { icon: <GitBranch />, label: tr ? 'Mantıksal Şema' : 'Logical Schemas', value: logicalSchemaCount, tone: 'info' },
      { icon: <Plug />, label: tr ? 'Bağlantı Türü' : 'Connection Type', value: version?.mode || missing },
      { icon: tested ? <CheckCircle2 /> : <CircleAlert />, label: tr ? 'Test Durumu' : 'Test Status', value: tested ? (tr ? 'Son Test Başarılı' : 'Last Test Passed') : t('connections.notTested'), tone: tested ? 'success' : 'warning' },
      { icon: <Clock3 />, label: tr ? 'Son Test Zamanı' : 'Last Tested', value: version?.testedAt ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(version.testedAt)) : t('connections.notTested') },
    ]
    if (version?.mode === 'JNDI') fields.splice(0, 3, { icon: <Server />, label: tr ? 'JNDI Adı' : 'JNDI Name', value: version.jndiName || missing })
    return <Card key={connection.uuid} className="connection-record-card" data-connection-uuid={connection.uuid} role="button" tabIndex={0} onClick={() => onOpen(connection.uuid)} onKeyDown={(event) => { if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); onOpen(connection.uuid) } }}>
      <header><span className="ui-collection-icon"><DatabaseProviderIcon databaseType={connection.databaseType} /></span><div className="connection-record-identity"><strong>{connection.name}</strong><small>{connection.databaseType === 'ORACLE' ? 'Oracle' : connection.databaseType}</small></div><span title={version?.testedAt ? `${tr ? 'Son başarılı test' : 'Last successful test'}: ${new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(version.testedAt))}` : t('connections.notTested')} className={`connection-header-state ${tested ? 'connection-test-pass' : 'connection-test-pending'}`}>{tested ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}{tested ? (tr ? 'Doğrulandı' : 'Verified') : t('connections.notTested')}</span></header>
      <RecordFields fields={fields.filter(field => field.label !== (tr ? 'Test Durumu' : 'Test Status') && field.label !== (tr ? 'Son Test Zamanı' : 'Last Tested'))} />
      <RecordAuditFields record={audit?.records[connection.uuid]} state={audit?.state} />
      {connection.description && <p className="connection-card-description">{connection.description}</p>}
      <footer><time dateTime={version?.testedAt ?? undefined}>{version?.testedAt && <Clock3 size={14} />}{version?.testedAt ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(version.testedAt)) : t('connections.notTested')}</time><ConnectionTestButton connectionUuid={connection.uuid} versionUuid={version?.uuid} /></footer>
    </Card>
  })}</div>
})
