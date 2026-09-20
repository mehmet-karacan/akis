import { Tag } from 'antd'
import { CheckCircle2, CircleAlert, Folder, Layers3, Plus } from 'lucide-react'
import { useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import { AsyncState, Button, PageHeader, RecordActionButton, SummaryStrip } from '../../core/ui'
import { DataGrid } from '../../core/ui/DataGrid'
import { ProgressiveRecords } from '../../core/ui/ProgressiveRecords'
import { QueryFilter } from '../../core/ui/QueryFilter'
import { useCollectionView } from '../../core/ui/ViewToggle'
import { connectionStatusTagStyles } from '../connections/presentation'
import { DefinitionTypeIcon } from './DefinitionTypeIcon'
import { useDefinitionsI18n } from './i18n'
import type { Definition, DefinitionType, Folder as DefinitionFolder } from './types'
import '../connections/connections.css'
import '../connections/catalog-layout.css'

interface Props {
  definitions: Definition[]
  folders: DefinitionFolder[]
  typeLabel(type: DefinitionType): string
  canWrite: boolean
  onOpen(uuid: string): void
  onCreate(): void
}

/** Project objects in the shared catalog layout; shown in the workbench until a definition is selected. */
export function DefinitionCatalog({ definitions, folders, typeLabel, canWrite, onOpen, onCreate }: Props) {
  const { language, t } = useDefinitionsI18n()
  const tr = language.startsWith('tr')
  const [view, setView] = useCollectionView('akis:definitions:view')
  const [params, setParams] = useSearchParams()
  const query = params.get('q') ?? ''
  const applyQuery = (next: string) => { const nextParams = new URLSearchParams(params); if (next) nextParams.set('q', next); else nextParams.delete('q'); setParams(nextParams) }
  const folderOf = (definition: Definition) => folders.find((folder) => folder.uuid === definition.folderUuid)
  const filtered = useMemo(() => definitions
    .filter((definition) => `${definition.name} ${definition.code} ${typeLabel(definition.type)} ${folderOf(definition)?.name ?? ''}`.toLocaleLowerCase(language).includes(query.trim().toLocaleLowerCase(language)))
    .sort((a, b) => a.name.localeCompare(b.name, language)), [definitions, query, language])
  const typeCounts = useMemo(() => Object.entries(definitions.reduce<Record<string, number>>((acc, definition) => { acc[definition.type] = (acc[definition.type] ?? 0) + 1; return acc }, {})).sort((a, b) => b[1] - a[1]).slice(0, 3), [definitions])
  const addButton = canWrite ? <Button tone="primary" icon={<Plus size={16} />} onClick={onCreate}>{t('newDefinition')}</Button> : undefined

  return <section className="page-stack connections-page definitions-catalog">
    <section className="connection-management-panel"><PageHeader icon={<Layers3 />} eyebrow={tr ? 'PROJE NESNELERİ' : 'PROJECT OBJECTS'} title={t('title')} description={t('subtitle')} />
    <QueryFilter onApply={applyQuery} placeholder={tr ? 'Ad, kod, tür veya klasöre göre ara' : 'Search by name, code, type or folder'} /></section>
    <SummaryStrip ariaLabel={t('title')} items={[
      { label: tr ? 'Toplam Nesne' : 'Total Objects', value: definitions.length, icon: <Layers3 />, tone: 'info' },
      { label: tr ? 'Klasör' : 'Folders', value: folders.length, icon: <Folder />, tone: 'neutral' },
      ...typeCounts.map(([type, count]) => ({ label: typeLabel(type as DefinitionType), value: count, icon: <DefinitionTypeIcon type={type as DefinitionType} size={18} />, tone: 'teal' as const })),
    ]} />
    <section className="connections-records">
      {filtered.length === 0 ? <AsyncState state="empty" title={definitions.length === 0 ? t('selectDefinition') : (tr ? 'Eşleşen nesne yok' : 'No matching objects')} action={addButton} /> : <ProgressiveRecords key={query} items={filtered}>{(visible) => <DataGrid auditKind="definitions" auditInFooter collectionTitle={tr ? 'Nesne Kataloğu' : 'Object Catalog'} collectionIcon={<Layers3 />} toolbarActions={addButton} cardHeaderLeadingField="type" cardHeaderField="status" cardHiddenFields={['type', 'status']} headerFieldsInList view={view} onViewChange={setView}>
        <thead><tr><th data-field-key="type">{t('type')}</th><th data-field-key="name">{t('name')}</th><th data-field-key="description">{t('description')}</th><th data-field-key="folder">{t('folder')}</th><th data-field-key="status">{t('status')}</th><th data-field-key="actions" className="ui-grid-actions-column"><span className="sr-only">{tr ? 'İşlemler' : 'Actions'}</span></th></tr></thead>
        <tbody>{visible.map((definition) => { const ok = definition.status === 'AKTIF' || definition.status === 'ETKIN'; const tone = ok ? 'success' : 'neutral'; return <tr key={definition.uuid} data-connection-uuid={definition.uuid} onDoubleClick={() => onOpen(definition.uuid)}>
          <td><span className="provider-cell"><DefinitionTypeIcon type={definition.type} size={18} /><span>{typeLabel(definition.type)}</span></span></td>
          <td><span className="connection-record-identity"><strong>{definition.name}</strong><small>{definition.code}</small></span></td>
          <td>{definition.description || (tr ? 'Açıklama yok' : 'No description')}</td>
          <td>{folderOf(definition)?.name ?? (tr ? 'Kök' : 'Root')}</td>
          <td><Tag className={`connection-status-tag connection-status-tag--${tone}`} style={connectionStatusTagStyles[tone]} icon={ok ? <CheckCircle2 size={12} /> : <CircleAlert size={12} />}><span className="connection-status-tag-label">{ok ? (tr ? 'Etkin' : 'Active') : definition.status}</span></Tag></td>
          <td className="row-actions"><div className="connection-row-actions"><RecordActionButton name={definition.name} editable={canWrite} onClick={() => onOpen(definition.uuid)} /></div></td>
        </tr> })}</tbody>
      </DataGrid>}</ProgressiveRecords>}
    </section>
  </section>
}
