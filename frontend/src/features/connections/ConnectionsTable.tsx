import { ChevronRight, MoreHorizontal } from 'lucide-react'
import { Link } from 'react-router-dom'
import { StatusBadge } from '../../core/ui'
import { DatabaseProviderIcon } from '../topology/DatabaseProviderIcon'
import { endpointLabel, type ConnectionCatalogItem } from './catalog'

export function ConnectionsTable({ projectUuid, items, labels }: {
  projectUuid: string
  items: ConnectionCatalogItem[]
  labels: Record<'provider' | 'connection' | 'endpoint' | 'physical' | 'logical' | 'status' | 'actions' | 'open' | 'ready' | 'testRequired', string>
}) {
  return <div className="connections-table-wrap"><table className="connections-table">
    <thead><tr><th>{labels.provider}</th><th>{labels.connection}</th><th>{labels.endpoint}</th><th>{labels.status}</th><th className="numeric">{labels.physical}</th><th className="numeric">{labels.logical}</th><th><span className="sr-only">{labels.actions}</span></th></tr></thead>
    <tbody>{items.map(({ connection, displayedVersion, physicalSchemaCount, logicalSchemaCount }) => <tr key={connection.uuid}>
      <td><span className="provider-cell"><DatabaseProviderIcon databaseType={connection.databaseType} /><span>{connection.databaseType === 'ORACLE' ? 'Oracle' : connection.databaseType}</span></span></td>
      <td><Link className="connection-name-link" to={`/projects/${projectUuid}/connections/${connection.uuid}`}><strong>{connection.name}</strong><small>{connection.code}</small></Link></td>
      <td><span className="connection-endpoint" title={endpointLabel(displayedVersion)}>{endpointLabel(displayedVersion)}</span></td>
      <td>{displayedVersion ? <StatusBadge tone={displayedVersion.lifecycleStatus === 'ACTIVE' || displayedVersion.lifecycleStatus === 'TESTED' ? 'success' : 'warning'}>{displayedVersion.lifecycleStatus === 'ACTIVE' || displayedVersion.lifecycleStatus === 'TESTED' ? labels.ready : labels.testRequired}</StatusBadge> : '—'}</td>
      <td className="numeric">{physicalSchemaCount}</td><td className="numeric">{logicalSchemaCount}</td>
      <td className="row-actions"><Link to={`/projects/${projectUuid}/connections/${connection.uuid}`} aria-label={`${labels.open}: ${connection.name}`}><MoreHorizontal aria-hidden="true" /><ChevronRight aria-hidden="true" /></Link></td>
    </tr>)}</tbody>
  </table></div>
}
