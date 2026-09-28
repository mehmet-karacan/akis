import { Columns3, GitCompareArrows, Info, KeyRound } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { DiscoveryTable, SchemaSnapshot } from '../topology/api'
import { DataObjectTypeIcon, dataObjectTypeLabel } from './DataObjectTypeIcon'
import { MetadataDiff } from './MetadataDiff'
import { compareMetadata } from './metadataComparison'

type DetailTab = 'overview' | 'columns' | 'changes' | 'constraints'

export function MetadataDiscoveryDetail({ table, previous, isExisting, snapshotStatus }: { table: DiscoveryTable | null; previous: SchemaSnapshot | null; isExisting: boolean; snapshotStatus: 'loading' | 'ready' | 'unavailable' }) {
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [tab, setTab] = useState<DetailTab>('overview')

  if (!table) return <aside className="metadata-detail-panel metadata-detail-empty"><Info size={24} /><strong>{tr ? 'Bir nesne seçin' : 'Select an object'}</strong><p>{tr ? 'Kolonları, kısıtları ve modelde oluşacak farkları burada inceleyin.' : 'Review columns, constraints and model changes here.'}</p></aside>

  const canCompare = !isExisting || snapshotStatus === 'ready'
  const changes = canCompare ? compareMetadata(previous?.columns ?? [], table.columns) : []
  const tabs: { key: DetailTab; label: string; icon: typeof Info; count?: number }[] = [
    { key: 'overview', label: tr ? 'Özet' : 'Overview', icon: Info },
    { key: 'columns', label: tr ? 'Kolonlar' : 'Columns', icon: Columns3, count: table.columns.length },
    { key: 'changes', label: tr ? 'Değişiklikler' : 'Changes', icon: GitCompareArrows, count: canCompare ? changes.length : undefined },
    { key: 'constraints', label: tr ? 'Kısıtlar' : 'Constraints', icon: KeyRound, count: table.constraints.length },
  ]

  return <aside className="metadata-detail-panel" aria-label={tr ? 'Seçili nesne' : 'Selected object'}>
    <header className="metadata-detail-heading"><span className="model-object-kind"><DataObjectTypeIcon type={table.type} />{dataObjectTypeLabel(table.type, tr)}</span><h2>{table.name}</h2><p>{table.owner}.{table.name}</p></header>
    <div className="metadata-detail-body">
      <nav className="metadata-detail-tabs" role="tablist" aria-label={tr ? 'Nesne detayları' : 'Object details'}>{tabs.map(item => <button key={item.key} type="button" role="tab" aria-selected={tab === item.key} aria-controls="metadata-detail-content" onClick={() => setTab(item.key)}><item.icon size={16} aria-hidden="true" /><span>{item.label}</span>{item.count !== undefined && <small>{item.count}</small>}</button>)}</nav>
      <div id="metadata-detail-content" className="metadata-detail-content" role="tabpanel">
        {tab === 'overview' && <><h3>{tr ? 'Keşif Özeti' : 'Discovery Summary'}</h3><dl className="metadata-detail-facts"><div><dt>{tr ? 'Katalog durumu' : 'Catalog status'}</dt><dd>{isExisting ? (tr ? 'Mevcut nesne' : 'Existing object') : (tr ? 'Yeni nesne' : 'New object')}</dd></div><div><dt>{tr ? 'Nesne türü' : 'Object type'}</dt><dd>{dataObjectTypeLabel(table.type, tr)}</dd></div><div><dt>{tr ? 'Kolon' : 'Columns'}</dt><dd>{table.columns.length}</dd></div><div><dt>{tr ? 'Kısıt' : 'Constraints'}</dt><dd>{table.constraints.length}</dd></div><div><dt>{tr ? 'Değişiklik' : 'Changes'}</dt><dd>{canCompare ? changes.length : '—'}</dd></div></dl><p className="form-note">{tr ? 'Keşif kaynak yapıyı yalnızca okur. Modele kayıt, soldaki seçim ve Kaydet işlemiyle yapılır.' : 'Discovery only reads the source structure. Select objects on the left and save them to the model.'}</p></>}
        {tab === 'columns' && <><h3>{tr ? 'Kolonlar' : 'Columns'}</h3>{table.columns.length ? <div className="metadata-detail-table-scroll"><table className="metadata-detail-table"><thead><tr><th>#</th><th>{tr ? 'Kolon' : 'Column'}</th><th>{tr ? 'Kaynak tipi' : 'Source type'}</th><th>{tr ? 'Boş olabilir' : 'Nullable'}</th></tr></thead><tbody>{table.columns.map(column => <tr key={`${column.ordinal}:${column.name}`}><td>{column.ordinal}</td><td><strong>{column.name}</strong></td><td>{column.producerType}</td><td>{column.nullable ? (tr ? 'Evet' : 'Yes') : (tr ? 'Hayır' : 'No')}</td></tr>)}</tbody></table></div> : <p className="form-note">{tr ? 'Kolon bulunamadı.' : 'No columns found.'}</p>}</>}
        {tab === 'changes' && <><h3>{tr ? 'Model Farkları' : 'Model Differences'}</h3>{canCompare ? <MetadataDiff changes={changes} /> : <p className="form-note">{snapshotStatus === 'loading' ? (tr ? 'Önceki metadata yükleniyor…' : 'Loading previous metadata…') : (tr ? 'Önceki metadata bulunamadı; güvenilir fark üretilemiyor.' : 'Previous metadata is unavailable; a reliable diff cannot be shown.')}</p>}</>}
        {tab === 'constraints' && <><h3>{tr ? 'Kısıtlar' : 'Constraints'}</h3>{table.constraints.length ? <ul className="metadata-constraint-list">{table.constraints.map(constraint => <li key={constraint.name}><strong>{constraint.name}</strong><span>{constraint.type} · {constraint.columns.join(', ') || '—'}</span>{constraint.referencedTable && <small>→ {constraint.referencedOwner && `${constraint.referencedOwner}.`}{constraint.referencedTable}</small>}</li>)}</ul> : <p className="form-note">{tr ? 'Kısıt bulunamadı.' : 'No constraints found.'}</p>}</>}
      </div>
    </div>
  </aside>
}
