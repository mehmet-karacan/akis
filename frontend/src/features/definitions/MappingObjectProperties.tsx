import { Descriptions, Input } from 'antd'
import { Database, DatabaseZap, Maximize2, Minimize2, Trash2, X } from 'lucide-react'
import { Button } from '../../core/ui'
import { useDefinitionsI18n } from './i18n'

export function MappingObjectProperties({ name, alias, role, model, schema, folder, onAlias, onRemove, onClose, onMaximize, maximized }: {
  name?: string; alias: string; role: 'SOURCE' | 'TARGET'; model?: string; schema?: string; folder?: string
  onAlias(alias: string): void; onRemove(): void; onClose(): void; onMaximize(): void; maximized: boolean
}) {
  const { language } = useDefinitionsI18n()
  const tr = language === 'tr'
  const missing = tr ? 'Model ağacından veri nesnesi bırakın' : 'Drop a data object from the model tree'
  return <section className="mapping-column-inspector mapping-object-properties" aria-label={tr ? 'Nesne Özellikleri' : 'Object Properties'}>
    <header><div className="mapping-inspector-identity"><span className={`procedure-heading-icon procedure-heading-icon--${role.toLowerCase()}`} aria-hidden="true">{role === 'SOURCE' ? <DatabaseZap size={17} /> : <Database size={17} />}</span><div><span>{role === 'SOURCE' ? (tr ? 'Kaynak' : 'Source') : (tr ? 'Hedef' : 'Target')}</span><strong>{name || alias}</strong></div></div>
      <div className="mapping-inspector-actions">
        <Button tone="ghost" icon={maximized ? <Minimize2 size={16} /> : <Maximize2 size={16} />} aria-label={maximized ? (tr ? 'Önceki boyuta dön' : 'Restore panel') : (tr ? 'Paneli büyüt' : 'Maximize panel')} onClick={onMaximize} />
        <Button tone="ghost" icon={<X size={16} />} aria-label={tr ? 'Özellikleri daralt' : 'Collapse properties'} onClick={onClose} />
      </div></header>
    <div className="mapping-inspector-context"><Descriptions size="small" column={{ xs: 1, sm: 2, lg: 4 }} items={[
      { key: 'object', label: 'Data Store', children: name || missing },
      { key: 'model', label: 'Model', children: model || missing },
      { key: 'schema', label: tr ? 'Mantıksal Şema' : 'Logical Schema', children: schema || (tr ? 'Tanımlanmadı' : 'Not defined') },
      { key: 'folder', label: tr ? 'Klasör' : 'Folder', children: folder || (tr ? 'Model Kökü' : 'Model Root') },
    ]} />
    <label className="mapping-inspector-alias"><span>{tr ? 'Takma Ad' : 'Alias'}</span><Input value={alias} onChange={event => onAlias(event.target.value)} /></label>
    <div><Button tone="danger" icon={<Trash2 size={16} />} onClick={onRemove}>{tr ? 'Nesneyi Kaldır' : 'Remove Object'}</Button></div></div>
  </section>
}
