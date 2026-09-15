import { useEffect, useState, type Key } from 'react'
import { Button, Tree, Alert, type TreeDataNode } from 'antd'
import { Database, ExternalLink, Folder, Table2, Eye } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { topologyApi, type Model, type DataObject, type Submodel } from '../features/topology/api'
import { projectRoute } from '../features/projects/CurrentProjectContext'

export function ProjectModelTree({ projectUuid, onNavigate }: { projectUuid: string; onNavigate(path: string): void }) {
  const { t } = useTranslation()
  const [models, setModels] = useState<Model[] | null>(null)
  const [catalogs, setCatalogs] = useState<Record<string, { folders: Submodel[]; objects: DataObject[] }>>({})
  const [expanded, setExpanded] = useState<Key[]>([])
  const [error, setError] = useState<string | null>(null)
  const load = async (key: string) => {
    setError(null)
    try {
      if (key === 'models') setModels(await topologyApi.listModels(projectUuid))
      else if (key.startsWith('model:')) {
        const uuid = key.slice(6)
        const [objects, folders] = await Promise.all([topologyApi.listDataObjects(projectUuid, uuid), topologyApi.listSubmodels(projectUuid, uuid)])
        setCatalogs(current => ({ ...current, [uuid]: { objects, folders } }))
      }
    } catch { setError(key) }
  }
  useEffect(() => {
    const refresh = () => { setModels(null); setCatalogs({}); setExpanded([]) }
    window.addEventListener('akis:models-changed', refresh)
    return () => window.removeEventListener('akis:models-changed', refresh)
  }, [])
  const toggle = (key: string) => {
    if (expanded.includes(key)) setExpanded(current => current.filter(value => value !== key))
    else setExpanded(current => [...current, key])
  }
  const label = (key: string, name: string, route: string) => <div className="akis-tree-title">
    <Button type="text" icon={<Database size={16} />} onClick={event => { event.stopPropagation(); toggle(key) }}>{name}</Button>
    <Button type="text" size="small" icon={<ExternalLink size={14} />} aria-label={`${t('nav.openObject')}: ${name}`} onClick={event => { event.stopPropagation(); onNavigate(projectRoute(route)) }} />
  </div>
  const metadata = (model: Model): TreeDataNode[] | undefined => {
    const catalog = catalogs[model.uuid]
    if (!catalog) return undefined
    const object = (item: DataObject): TreeDataNode => ({ key: `object:${model.uuid}:${item.uuid}`, title: item.name, icon: item.type === 'VIEW' ? <Eye size={16} /> : <Table2 size={16} />, isLeaf: true })
    const folder = (item: Submodel, seen: Set<string>): TreeDataNode => ({ key: `folder:${item.uuid}`, title: item.name, selectable: false, icon: <Folder size={16} />, children: [
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
        children: models?.map(model => ({ key: `model:${model.uuid}`, title: label(`model:${model.uuid}`, model.name, `/models/${model.uuid}`), selectable: false, isLeaf: false, children: metadata(model) })) }]} />
    {error && <Alert type="error" title={t('common.loadError')} action={<Button onClick={() => void load(error)}>{t('common.retry')}</Button>} />}
  </div>
}
