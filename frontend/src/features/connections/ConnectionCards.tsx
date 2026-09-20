import { RecordAuditFields } from '../../core/ui/RecordAuditFields'
import type { useRecordAudit } from '../../core/ui/useRecordAudit'
import { ConnectionTestButton } from './ConnectionTestButton'
import { Clock3, CheckCircle2, CircleAlert } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { RecordFields } from '../../core/ui/RecordFields'
import { DatabaseProviderIcon } from '../topology/DatabaseProviderIcon'
import type { ConnectionCatalogItem } from './catalog'
import { RecordCard } from '../../core/ui/RecordCard'
import { RecordActionButton } from '../../core/ui'
import { memo } from 'react'
import { createConnectionPresentation } from './presentation'

export const ConnectionCards = memo(function ConnectionCards({ items, view, onOpen, audit, editable = true }: { audit?: ReturnType<typeof useRecordAudit>; items: ConnectionCatalogItem[]; view: 'card' | 'list'; onOpen: (uuid: string) => void; editable?: boolean }) {
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  return <div className={`ui-collection ui-collection--${view}`}>{items.map((item) => {
    const { connection } = item
    const presentation = createConnectionPresentation(item, audit?.records[connection.uuid], i18n.language, audit?.state)
    const tested = presentation.status.tested
    return <RecordCard key={connection.uuid} className="connection-record-card" data-connection-uuid={connection.uuid}
      header={<><span className="ui-collection-icon"><DatabaseProviderIcon databaseType={connection.databaseType} /></span><div className="connection-record-identity"><strong>{presentation.identity.name}</strong><small>{presentation.identity.code} · {presentation.identity.provider}</small></div><span title={presentation.lastTest} className={`connection-header-state ${tested ? 'connection-test-pass' : 'connection-test-pending'}`}>{tested ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}{presentation.status.text}</span></>}
      audit={<RecordAuditFields record={audit?.records[connection.uuid]} state={audit?.state} />}
      description={presentation.identity.description}
      footer={<><time dateTime={connection.lastTestedAt ?? undefined} title={tr ? 'Son test zamanı' : 'Last tested'}>{connection.lastTestedAt && <Clock3 size={14} />}<span>{tr ? 'Son Test' : 'Last Test'}:</span>{presentation.lastTest}</time><div className="connection-card-actions"><ConnectionTestButton connectionUuid={connection.uuid} /><RecordActionButton name={connection.name} editable={editable} onClick={() => onOpen(connection.uuid)} /></div></>}>
      <RecordFields fields={presentation.fields.filter(field => !['Test Durumu', 'Test status', 'Son Test Zamanı', 'Last tested'].includes(field.label))} />
    </RecordCard>
  })}</div>
})
