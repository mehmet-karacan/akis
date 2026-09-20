import { DataGrid } from '../../core/ui/DataGrid'
import { ConnectionTestButton } from './ConnectionTestButton'
import { RecordActionButton } from '../../core/ui'
import { useTranslation } from 'react-i18next'
import { StatusBadge } from '../../core/ui'
import { DatabaseProviderIcon, databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { type ConnectionCatalogItem } from './catalog'
import type { useRecordAudit } from '../../core/ui/useRecordAudit'
import { createConnectionPresentation } from './presentation'

export function ConnectionsTable({ items, labels, onOpen, audit, editable = true }: {
  onOpen?: (uuid: string) => void
  audit?: ReturnType<typeof useRecordAudit>
  projectUuid: string
  items: ConnectionCatalogItem[]
  labels: Record<'provider' | 'connection' | 'endpoint' | 'physical' | 'logical' | 'status' | 'lastTest' | 'actions' | 'open' | 'ready' | 'testRequired' | 'host' | 'port' | 'service' | 'username', string>
  editable?: boolean
}) {
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  return <div className="connections-table-wrap"><DataGrid viewControls={false} className="connections-table">
    <thead><tr><th>{labels.provider}</th><th>{labels.connection}</th><th>{tr ? 'Açıklama' : 'Description'}</th><th>{labels.host}</th><th>{labels.port}</th><th>{labels.service}</th><th>{labels.username}</th><th>{tr ? 'Bağlantı Türü' : 'Connection Type'}</th><th>{labels.status}</th><th>{labels.lastTest}</th><th className="numeric">{labels.physical}</th><th className="numeric">{labels.logical}</th><th>{tr ? 'Oluşturan' : 'Created By'}</th><th>{tr ? 'Oluşturulma Zamanı' : 'Created At'}</th><th>{tr ? 'Güncelleyen' : 'Updated By'}</th><th>{tr ? 'Güncellenme Zamanı' : 'Updated At'}</th><th><span className="sr-only">{labels.actions}</span></th></tr></thead>
    <tbody>{items.map((item) => { const { connection, physicalSchemaCount, logicalSchemaCount } = item; const presentation = createConnectionPresentation(item, audit?.records[connection.uuid], i18n.language, audit?.state); return <tr key={connection.uuid}>
      <td><span className="provider-cell"><DatabaseProviderIcon databaseType={connection.databaseType} /><span>{databaseProviderVisual(connection.databaseType).label}</span></span></td>
      <td><span className="connection-record-identity"><strong>{presentation.identity.name}</strong><small>{presentation.identity.code}</small></span></td>
      <td>{presentation.identity.description}</td>
      <td>{presentation.values.host}</td><td>{presentation.values.port}</td><td>{presentation.values.service}</td><td>{presentation.values.username}</td><td>{presentation.values.mode}</td>
      <td><StatusBadge tone={presentation.status.tested ? 'success' : 'warning'}>{presentation.status.text}</StatusBadge></td>
      <td className="connection-last-test">{presentation.lastTest}</td>
      <td className="numeric">{physicalSchemaCount}</td><td className="numeric">{logicalSchemaCount}</td>
      <td>{presentation.audit.createdBy}</td><td>{presentation.audit.createdAt}</td><td>{presentation.audit.updatedBy}</td><td>{presentation.audit.updatedAt}</td>
      <td className="row-actions"><ConnectionTestButton connectionUuid={connection.uuid} /><RecordActionButton name={connection.name} editable={editable} onClick={() => onOpen?.(connection.uuid)} /></td>
    </tr> })}</tbody>
  </DataGrid></div>
}
