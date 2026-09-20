import { Database, Plus, Star } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AsyncState, Button, RecordActionButton } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { topologyApi, type Connection, type PhysicalSchema, type SchemaBinding } from '../topology/api'
import '../schemas/schemas.css'
import { PhysicalSchemaDetailDialog } from './PhysicalSchemaDetailDialog'

export function PhysicalSchemaManager({ projectUuid, connection, items, bindings, canManage, onChanged }: {
  projectUuid: string
  connection: Connection
  items: PhysicalSchema[]
  bindings?: SchemaBinding[]
  canManage: boolean
  onChanged: () => Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [available, setAvailable] = useState<string[]>([])
  const [dialog, setDialog] = useState<{ item?: PhysicalSchema } | null>(null)
  const loadSuggestions = async () => setAvailable(await topologyApi.listDatabaseSchemas(projectUuid, connection.uuid))

  return <div className="physical-schema-manager">
    {canManage && <div className="physical-schema-toolbar"><Button type="button" tone="primary" icon={<Plus size={14} />} onClick={() => setDialog({})}>{t('schemas.addPhysical')}</Button></div>}
    {items.length ? <div className="physical-schema-table"><DataGrid auditKind="physical-schemas" viewControls={false}>
      <thead><tr>
        <th>{tr ? 'Şema' : 'Schema'}</th>
        <th>{tr ? 'Çalışma Şeması' : 'Work Schema'}</th>
        <th>{tr ? 'Prefixler (LKM / IKM / CKM)' : 'Prefixes (LKM / IKM / CKM)'}</th>
        <th>{tr ? 'Varsayılan' : 'Default'}</th>
        <th className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th>
      </tr></thead>
      <tbody>{items.map((item) => <tr key={item.uuid}>
        <td><span className="physical-schema-name"><Database size={15} /><strong>{item.name}</strong><code>{item.schemaName}</code></span></td>
        <td><code>{item.workSchemaName ?? item.schemaName}</code></td>
        <td><code>{item.loadingPrefix}</code> / <code>{item.integrationPrefix}</code> / <code>{item.errorPrefix}</code></td>
        <td>{item.defaultSchema ? <span className="physical-schema-default"><Star size={14} />{tr ? 'Varsayılan' : 'Default'}</span> : null}</td>
        <td className="ui-grid-actions-column"><RecordActionButton name={item.name} editable={canManage} onClick={() => setDialog({ item })} /></td>
      </tr>)}</tbody>
    </DataGrid></div> : <AsyncState state="empty" compact title={t('schemas.noPhysical')} />}
    {dialog && <PhysicalSchemaDetailDialog
      key={dialog.item?.uuid ?? 'new'}
      projectUuid={projectUuid}
      connectionUuid={connection.uuid}
      item={dialog.item}
      canManage={canManage}
      dependencyCount={dialog.item ? (bindings ?? []).filter((binding) => binding.physicalSchemaUuid === dialog.item?.uuid).length : 0}
      suggestions={available}
      onLoadSuggestions={connection.mode === 'JDBC' ? loadSuggestions : undefined}
      onClose={() => setDialog(null)}
      onChanged={async () => { await onChanged() }}
    />}
  </div>
}
