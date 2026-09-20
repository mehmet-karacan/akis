import { useEffect, useRef, useState, type Key, type ReactNode } from 'react'
import { ProjectFolderIcon } from '../features/definitions/DefinitionTypeIcon'
import { Button, Dropdown, Tree, Alert, type TreeDataNode } from 'antd'
import { Database, Table2, Eye, MoreHorizontal, ScanSearch } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { topologyApi, type Model, type DataObject, type Submodel } from '../features/topology/api'
import { projectRoute } from '../features/projects/CurrentProjectContext'
import { encodeModelObjectDrag, MODEL_OBJECT_DRAG_TYPE } from '../features/models/modelObjectDrag'

interface Props { projectUuid: string; onNavigate(path: string): void }
type RoutedTreeDataNode = TreeDataNode & { route?: string }
export function ProjectModelTree(props: Props) {
  return <ProjectModelTreeSession key={props.projectUuid} {...props} />
}

function ProjectModelTreeSession({ projectUuid, onNavigate }: Props) {
  const { t, i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [models, setModels] = useState<Model[] | null>(null)
  const [catalogs, setCatalogs] = useState<Record<string, { folders: Submodel[]; objects: DataObject[] }>>({})
  const [expanded, setExpanded] = useState<Key[]>([])
  const [selected, setSelected] = useState<Key[]>([])
  const [loaded, setLoaded] = useState<Key[]>([])
  const [error, setError] = useState<string | null>(null)
  const generation = useRef(0)
  const load = async (key: string) => {
    const request = generation.current
    setError(null)
    try {
      if (key === 'models') {
        const items = await topologyApi.listModels(projectUuid)
        if (request !== generation.current) return
        setModels(items)
      }
      else if (key.startsWith('model:')) {
        const uuid = key.slice(6)
        const [objects, folders] = await Promise.all([topologyApi.listDataObjects(projectUuid, uuid), topologyApi.listSubmodels(projectUuid, uuid)])
        if (request !== generation.current) return
        setCatalogs(current => ({ ...current, [uuid]: { objects, folders } }))
      }
      setLoaded(current => current.includes(key) ? current : [...current, key])
    } catch {
      if (request !== generation.current) return
      setError(key)
      // A failed expansion stays explicitly retryable, not an automatic fetch loop.
      setLoaded(current => current.includes(key) ? current : [...current, key])
    }
  }
  useEffect(() => {
    const refresh = () => { generation.current++; setModels(null); setCatalogs({}); setExpanded([]); setSelected([]); setLoaded([]); setError(null) }
    window.addEventListener('akis:models-changed', refresh)
    return () => { generation.current++; window.removeEventListener('akis:models-changed', refresh) }
  }, [])
  const open = (route: string) => onNavigate(projectRoute(route))
  const title = (name: string, icon: ReactNode, route?: string, reverse?: string, object?: { item: DataObject; model: Model }) => <div className="akis-tree-title model-tree-title" data-route={route}
    onKeyDown={event => { if (event.key === 'Enter' && route) { event.stopPropagation(); event.preventDefault(); open(route) } }}>
    <div className="akis-tree-static-label" tabIndex={0} title={name}
      draggable={!!object} onDragStart={event => {
        if (!object) return
        event.stopPropagation(); const { item, model } = object
        event.dataTransfer.effectAllowed = 'copy'
        event.dataTransfer.setData(MODEL_OBJECT_DRAG_TYPE, encodeModelObjectDrag({ objectUuid: item.uuid, modelUuid: model.uuid, objectName: item.name, objectReference: item.objectReference, modelName: model.name, logicalSchemaUuid: model.logicalSchemaUuid }))
        event.dataTransfer.setData('text/plain', item.name)
      }}>
      {icon}<span className="akis-tree-label"><span>{name}</span></span>
    </div>
    {reverse && <Dropdown trigger={['click']} menu={{ items: [{ key: 'reverse', icon: <ScanSearch size={15} />, label: 'Reverse Engineer', onClick: () => open(reverse) }] }}>
      <Button type="text" size="small" icon={<MoreHorizontal size={14} />} aria-label={(tr ? 'İşlemler: ' : 'Actions: ') + name} onClick={event => event.stopPropagation()} />
    </Dropdown>}
  </div>
  const metadata = (model: Model): RoutedTreeDataNode[] | undefined => {
    const catalog = catalogs[model.uuid]
    if (!catalog) return undefined
    const object = (item: DataObject): RoutedTreeDataNode => {
      const route = '/models/' + model.uuid + '?object=' + encodeURIComponent(item.uuid)
      return { key: 'object:' + model.uuid + ':' + item.uuid, isLeaf: true, route,
        title: title(item.name, item.type === 'VIEW' ? <span className="definition-type-icon definition-type-icon--view" aria-hidden="true"><Eye size={14} /></span> : <span className="definition-type-icon definition-type-icon--table" aria-hidden="true"><Table2 size={14} /></span>,
          route, '/models/' + model.uuid + '/import?object=' + encodeURIComponent(item.uuid), { item, model }) }
    }
    const folder = (item: Submodel, seen: Set<string>): RoutedTreeDataNode => {
      const route = '/models/' + model.uuid + '?folder=' + encodeURIComponent(item.uuid)
      return { key: 'folder:' + item.uuid, route,
        title: title(item.name, <ProjectFolderIcon open={expanded.includes('folder:' + item.uuid)} size={14} />, route, '/models/' + model.uuid + '/import?folder=' + encodeURIComponent(item.uuid)), children: [
          ...catalog.folders.filter(child => child.parentUuid === item.uuid && !seen.has(child.uuid)).map(child => folder(child, new Set([...seen, child.uuid]))),
          ...catalog.objects.filter(child => child.submodelUuid === item.uuid).map(object),
        ] }
    }
    return [...catalog.folders.filter(item => !item.parentUuid || !catalog.folders.some(parent => parent.uuid === item.parentUuid)).map(item => folder(item, new Set([item.uuid]))),
      ...catalog.objects.filter(item => !item.submodelUuid || !catalog.folders.some(parent => parent.uuid === item.submodelUuid)).map(object)]
  }
  return <div className="sidebar-model-link">
    <Tree blockNode virtual={false} motion={null} expandedKeys={expanded} onExpand={setExpanded} selectedKeys={selected} onSelect={setSelected}
      loadedKeys={loaded}
      loadData={node => load(String(node.key))}
      treeData={[{ key: 'models', className: 'project-tree-section-node', title: <span className="akis-tree-title--section">{title(t('nav.models'), <Database size={15} className="model-tree-model-icon" aria-hidden="true" />)}</span>, isLeaf: false,
        children: models?.map(model => {
          const route = '/models/' + model.uuid
          return { key: 'model:' + model.uuid, route, title: title(model.name, <span className="definition-type-icon definition-type-icon--model" aria-hidden="true"><Database size={14} /></span>, route, route + '/import'), isLeaf: false, children: metadata(model) }
        }) }] as RoutedTreeDataNode[]}
      onDoubleClick={(_event, node) => { const routed = node as RoutedTreeDataNode; if (routed.route) open(routed.route) }} />
    {error && <Alert type="error" title={t('common.loadError')} action={<Button onClick={() => void load(error).catch(() => undefined)}>{t('common.retry')}</Button>} />}
  </div>
}
