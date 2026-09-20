import { Database, DatabaseZap, Layers3, Plug } from 'lucide-react'
import { DataGrid } from '../../core/ui/DataGrid'
import { databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import type { Connection, PhysicalSchema } from '../topology/api'

/** One line per connection a run will open: which system, where, as whom, which schema — the part ODI shows in the physical tab. */
export interface ConnectionUse { role: 'SOURCE' | 'TARGET' | 'STAGING'; label: string; connection?: Connection; physical?: PhysicalSchema; owner: string; object?: string; readOnly?: boolean }

export function connectionEndpoint(connection?: Connection) {
  if (!connection) return ''
  if (connection.jndiName) return `JNDI ${connection.jndiName}`
  const database = connection.serviceName ?? connection.sid ?? connection.databaseName ?? ''
  return `${connection.host ?? ''}${connection.port ? `:${connection.port}` : ''}${database ? `/${database}` : ''}`
}
export function connectionLine(use: ConnectionUse) {
  return `${use.connection?.name ?? '—'}${use.connection ? ` (${use.connection.code})` : ''} · ${connectionEndpoint(use.connection) || '—'} · ${use.connection?.username ? `user ${use.connection.username}` : ''} · ${use.owner}${use.object ? `.${use.object}` : ''}`
}
export function connectionsMarkdown(uses: ConnectionUse[], tr: boolean) {
  return [`## ${tr ? 'Bağlantılar' : 'Connections'}`, '', `| ${tr ? 'Rol' : 'Role'} | ${tr ? 'Bağlantı' : 'Connection'} | ${tr ? 'Sunucu' : 'Endpoint'} | ${tr ? 'Kullanıcı' : 'User'} | ${tr ? 'Şema' : 'Schema'} | ${tr ? 'Nesne' : 'Object'} | ${tr ? 'Erişim' : 'Access'} |`, '|---|---|---|---|---|---|---|',
    ...uses.map((use) => `| ${use.label} | ${use.connection ? `${use.connection.name} (${use.connection.code}) · ${databaseProviderVisual(use.connection.databaseType).label}` : '—'} | ${connectionEndpoint(use.connection) || '—'} | ${use.connection?.username ?? '—'} | ${use.owner || '—'} | ${use.object ?? '—'} | ${use.readOnly ? (tr ? 'yalnız SELECT' : 'SELECT only') : (tr ? 'yazma' : 'write')} |`), '']
}

export function ConnectionsSection({ uses, tr }: { uses: ConnectionUse[]; tr: boolean }) {
  const icon = (role: ConnectionUse['role']) => role === 'SOURCE' ? <DatabaseZap size={14} /> : role === 'STAGING' ? <Layers3 size={14} /> : <Database size={14} />
  return <section className="prerun-section">
    <h4><Plug size={15} aria-hidden="true" />{tr ? 'Bağlantılar' : 'Connections'} <span className="procedure-heading-count">{uses.length}</span></h4>
    <DataGrid viewControls={false} className="prerun-grid">
      <thead><tr><th scope="col">{tr ? 'Rol' : 'Role'}</th><th scope="col">{tr ? 'Bağlantı' : 'Connection'}</th><th scope="col">{tr ? 'Sunucu' : 'Endpoint'}</th><th scope="col">{tr ? 'Kullanıcı' : 'User'}</th><th scope="col">{tr ? 'Şema' : 'Schema'}</th><th scope="col">{tr ? 'Nesne' : 'Object'}</th><th scope="col">{tr ? 'Erişim' : 'Access'}</th></tr></thead>
      <tbody>{uses.map((use, index) => <tr key={index}>
        <td><span className={`procedure-route-chip is-ready procedure-route-chip--${use.role === 'SOURCE' ? 'source' : 'target'}`}>{icon(use.role)}{use.label}</span></td>
        <td>{use.connection ? <><strong>{use.connection.name}</strong><small className="prerun-cell-hint">{use.connection.code} · {databaseProviderVisual(use.connection.databaseType).label}{use.connection.lastTestPassed === false ? (tr ? ' · son test başarısız' : ' · last test failed') : ''}</small></> : <em>{tr ? 'Bu ortamda bağlı değil' : 'Not bound in this environment'}</em>}</td>
        <td><code>{connectionEndpoint(use.connection) || '—'}</code></td>
        <td>{use.connection?.username ?? '—'}</td>
        <td><code>{use.owner || '—'}</code>{use.physical?.workSchemaName && use.physical.workSchemaName !== use.owner ? <small className="prerun-cell-hint">work {use.physical.workSchemaName}</small> : null}</td>
        <td>{use.object ? <code>{use.object}</code> : '—'}</td>
        <td>{use.readOnly ? <span className="prerun-status is-ready">{tr ? 'yalnız SELECT' : 'SELECT only'}</span> : <span className="prerun-status is-warning">{tr ? 'yazma' : 'write'}</span>}</td>
      </tr>)}</tbody>
    </DataGrid>
  </section>
}
