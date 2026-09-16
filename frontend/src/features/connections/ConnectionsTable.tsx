import { DataGrid } from '../../core/ui/DataGrid'
import { ConnectionTestButton } from './ConnectionTestButton'
import { Pencil } from 'lucide-react'
import { Button } from '../../core/ui'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { StatusBadge } from '../../core/ui'
import { DatabaseProviderIcon } from '../topology/DatabaseProviderIcon'
import { type ConnectionCatalogItem } from './catalog'

export function ConnectionsTable({ projectUuid, items, labels, onOpen }: {
  onOpen?: (uuid: string) => void
  projectUuid: string
  items: ConnectionCatalogItem[]
  labels: Record<'provider' | 'connection' | 'endpoint' | 'physical' | 'logical' | 'status' | 'actions' | 'open' | 'ready' | 'testRequired' | 'host' | 'port' | 'service' | 'username', string>
}) {
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  return <div className="connections-table-wrap"><DataGrid auditKind="connections" viewControls={false} className="connections-table">
    <thead><tr><th>{labels.provider}</th><th>{labels.connection}</th><th>{labels.host}</th><th>{labels.port}</th><th>{labels.service}</th><th>{labels.username}</th><th>{labels.status}</th><th className="numeric">{labels.physical}</th><th className="numeric">{labels.logical}</th><th><span className="sr-only">{labels.actions}</span></th></tr></thead>
    <tbody>{items.map(({ connection, displayedVersion, physicalSchemaCount, logicalSchemaCount }) => <tr key={connection.uuid}>
      <td><span className="provider-cell"><DatabaseProviderIcon databaseType={connection.databaseType} /><span>{connection.databaseType === 'ORACLE' ? 'Oracle' : connection.databaseType}</span></span></td>
      <td><Link className="connection-name-link" to={`/projects/${projectUuid}/connections/${connection.uuid}`} onClick={onOpen ? (event) => { event.preventDefault(); onOpen(connection.uuid) } : undefined}><strong>{connection.name}</strong><small>{connection.code}</small></Link></td>
      <td>{displayedVersion?.host || displayedVersion?.jndiName || labels.testRequired}</td><td>{displayedVersion?.port ?? labels.testRequired}</td><td>{displayedVersion?.sid || displayedVersion?.serviceName || displayedVersion?.databaseName || labels.testRequired}</td><td>{displayedVersion?.username || labels.testRequired}</td>
      <td><StatusBadge tone={displayedVersion?.testedAt ? 'success' : 'warning'}>{displayedVersion?.testedAt ? (tr ? 'Son Test Başarılı' : 'Last Test Passed') : labels.testRequired}</StatusBadge></td>
      <td className="numeric">{physicalSchemaCount}</td><td className="numeric">{logicalSchemaCount}</td>
      <td className="row-actions"><ConnectionTestButton connectionUuid={connection.uuid} versionUuid={displayedVersion?.uuid} /><Button type="button" className="connection-card-action" icon={<Pencil size={16} />} aria-label={`${tr ? 'Düzenle' : 'Edit'}: ${connection.name}`} onClick={() => onOpen?.(connection.uuid)} /></td>
    </tr>)}</tbody>
  </DataGrid></div>
}
