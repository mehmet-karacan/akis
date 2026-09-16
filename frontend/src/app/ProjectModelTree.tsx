import { useEffect, useState, type Key } from 'react'
import { Button, Tree, Alert, type TreeDataNode } from 'antd'
import { Database, ExternalLink, Folder, Table2, Eye, GripVertical } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { topologyApi, type Model, type DataObject, type Submodel, type LogicalSchema } from '../features/topology/api'
import { projectRoute } from '../features/projects/CurrentProjectContext'
import { encodeModelObjectDrag, MODEL_OBJECT_DRAG_TYPE } from '../features/models/modelObjectDrag'

export function ProjectModelTree({ projectUuid, onNavigate }: { projectUuid: string; onNavigate(path: string): void }) {
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [models, setModels] = useState<Model[] | null>(null)
  const [logicalSchemas, setLogicalSchemas] = useState<LogicalSchema[]>([])
  const [catalogs, setCatalogs] = useState<Record<string, { folders: Submodel[]; objects: DataObject[] }>>({})
  const [expanded, setExpanded] = useState<Key[]>([])
  const [error, setError] = useState<string | null>(null)
  const load = async (key: string) => {
    setError(null)
    try {
      if (key === 'models') {
        const [nextModels, nextLogicalSchemas] = await Promise.all([
          topologyApi.listModels(projectUuid),
          topologyApi.listLogicalSchemas(projectUuid),
        ])
        setModels(nextModels)
        setLogicalSchemas(nextLogicalSchemas)
      }
      else if (key.startsWith('model:')) {
        const uuid = key.slice(6)
        const [objects, folders] = await Promise.all([topologyApi.listDataObjects(projectUuid, uuid), topologyApi.listSubmodels(projectUuid, uuid)])
        setCatalogs(current => ({ ...current, [uuid]: { objects, folders } }))
      }
    } catch { setError(key) }
  }
  useEffect(() => {
    const refresh = () => { setModels(null); setLogicalSchemas([]); setCatalogs({}); setExpanded([]) }
    window.addEventListener('akis:models-changed', refresh)
    return () => window.removeEventListener('akis:models-changed', refresh)
  }, [])
  const toggle = (key: string) => {
    if (expanded.includes(key)) setExpanded(current => current.filter(value => value !== key))
    else setExpanded(current => [...current, key])
  }
  const label = (key: string, name: string, route: string, logicalSchemaName?: string) => <div className="akis-tree-title model-tree-title">
    <Button type="text" icon={<Database className="model-tree-model-icon" size={16} />} onClick={event => { event.stopPropagation(); toggle(key) }}><span className="model-tree-label"><span>{name}</span>{logicalSchemaName && <small>{logicalSchemaName}</small>}</span></Button>
    <Button type="text" size="small" icon={<ExternalLink size={14} />} aria-label={`${t('nav.openObject')}: ${name}`} onClick={event => { event.stopPropagation(); onNavigate(projectRoute(route)) }} />
  </div>
  const metadata = (model: Model): TreeDataNode[] | undefined => {
    const catalog = catalogs[model.uuid]
    if (!catalog) return undefined
    const object = (item: DataObject): TreeDataNode => ({ key: `object:${model.uuid}:${item.uuid}`, title: <span className="model-object-drag" draggable title={tr ? 'Mapping ekranında kaynak veya hedef alanına sürükleyin' : 'Drag into a source or target area on the mapping screen'} onDragStart={event => {
      event.stopPropagation()
      event.dataTransfer.effectAllowed = 'copy'
      event.dataTransfer.setData(MODEL_OBJECT_DRAG_TYPE, encodeModelObjectDrag({ objectUuid: item.uuid, modelUuid: model.uuid, objectName: item.name, objectReference: item.objectReference, modelName: model.name, logicalSchemaUuid: model.logicalSchemaUuid }))
      event.dataTransfer.setData('text/plain', item.name)
    }}><GripVertical size={13} aria-hidden="true" /><span>{item.name}</span><small>{item.type === 'VIEW' ? (tr ? 'Görünüm' : 'View') : (tr ? 'Tablo' : 'Table')}</small></span>, icon: item.type === 'VIEW' ? <Eye className="model-tree-view-icon" size={16} /> : <Table2 className="model-tree-table-icon" size={16} />, isLeaf: true })
    const folder = (item: Submodel, seen: Set<string>): TreeDataNode => ({ key: `folder:${item.uuid}`, title: item.name, selectable: false, icon: <Folder className="project-folder-icon" size={16} />, children: [
      ...catalog.folders.filter(child => child.parentUuid === item.uuid && !seen.has(child.uuid)).map(child => folder(child, new Set([...seen, child.uuid]))),
      ...catalog.objects.filter(child => child.submodelUuid === item.uuid).map(object),
    ] })
    return [...catalog.folders.filter(item => !item.parentUuid || !catalog.folders.some(parent => parent.uuid === item.parentUuid)).map(item => folder(item, new Set([item.uuid]))),
      ...catalog.objects.filter(item => !item.submodelUuid || !catalog.folders.some(parent => parent.uuid === item.submodelUuid)).map(object)]
  }
  return <div className="sidebar-model-link">
    <Tree blockNode showIcon virtual={false} motion={null} expandedKeys={expanded} onExpand={setExpanded}
      loadData={node => load(String(node.key))}
      onSelect={keys => { const key = String(keys[0] ?? ''); if (key.startsWith('object:')) { const [, model, object] = key.split(':'); onNavigate(projectRoute(`/models/${model}?object=${encodeURIComponent(object!)}`)) } }}
      treeData={[{ key: 'models', title: label('models', t('nav.models'), '/models'), selectable: false, isLeaf: false,
        children: models?.map(model => ({ key: `model:${model.uuid}`, title: label(`model:${model.uuid}`, model.name, `/models/${model.uuid}`, logicalSchemas.find(schema => schema.uuid === model.logicalSchemaUuid)?.name), selectable: false, isLeaf: false, children: metadata(model) })) }]} />
    {error && <Alert type="error" title={t('common.loadError')} action={<Button onClick={() => void load(error)}>{t('common.retry')}</Button>} />}
  </div>
}
