import { BookOpenText, Cable, CalendarClock, FolderKanban, GitBranch, Globe2, Layers3, PlayCircle, Rocket, Workflow } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import { Menu } from 'antd'
import type { MenuProps } from 'antd'
import { projectRoute } from '../features/projects/CurrentProjectContext'
import { topologyApi, type DataObject, type Model, type Submodel } from '../features/topology/api'
import { matchesCatalogReference, modelPath } from '../features/models/modelRoutes'
import { DataObjectTypeIcon } from '../features/models/DataObjectTypeIcon'
import { ProjectFolderIcon } from '../features/definitions/DefinitionTypeIcon'

type ModelCatalog = { folders: Submodel[]; objects: DataObject[] }

export type WorkspaceId = 'project' | 'development' | 'operations' | 'connections' | 'schema-metadata'

export const destinations = [
  { id: 'project', path: '', key: 'nav.overview', icon: FolderKanban, absolute: false },
  { id: 'development', path: '/objects', key: 'nav.development', icon: Workflow, absolute: false },
  { id: 'models', path: '/models', key: 'nav.models', icon: Layers3, absolute: false },
  { id: 'operations', path: '/operations', key: 'nav.runs', icon: PlayCircle, absolute: false },
  { id: 'publications', path: '/publications', key: 'nav.publications', icon: Rocket, absolute: false },
  { id: 'schedules', path: '/schedules', key: 'nav.schedules', icon: CalendarClock, absolute: false },
  { id: 'connections', path: '/connections', key: 'nav.connections', icon: Cable, absolute: false },
  { id: 'logical-schemas', path: '/logical-schemas', key: 'nav.logicalSchemas', icon: GitBranch, absolute: false },
  { id: 'environments', path: '/environments', key: 'nav.environments', icon: Globe2, absolute: false },
  { id: 'schema-metadata', path: '/schema-metadata', key: 'nav.schemaMetadata', icon: BookOpenText, absolute: true },
] as const

const sections = [
  { key: 'project', label: 'nav.group.project', items: ['project'] },
  { key: 'design', label: 'nav.group.design', items: ['development', 'models'] },
  { key: 'operate', label: 'nav.group.operate', items: ['operations', 'publications', 'schedules'] },
  { key: 'infrastructure', label: 'nav.group.infrastructure', items: ['connections', 'logical-schemas', 'environments', 'schema-metadata'] },
] as const

export function resolveNavigationItem(pathname: string): string {
  if (pathname.includes('/schema-metadata')) return 'schema-metadata'
  if (pathname.includes('/publications')) return 'publications'
  if (pathname.includes('/schedules')) return 'schedules'
  if (pathname.includes('/operations') || pathname.includes('/runs')) return 'operations'
  if (pathname.includes('/logical-schemas') || pathname.includes('/schema-bindings')) return 'logical-schemas'
  if (pathname.includes('/environments')) return 'environments'
  if (pathname.includes('/connections') || pathname.includes('/topology')) return 'connections'
  if (pathname.includes('/models')) return 'models'
  if (pathname.includes('/objects') || pathname.includes('/development') || pathname.includes('/definitions')) return 'development'
  return 'project'
}

export function resolveWorkspace(pathname: string): WorkspaceId {
  if (pathname.includes('/schema-metadata')) return 'schema-metadata'
  if (pathname.includes('/operations') || pathname.includes('/runs') || pathname.includes('/publications') || pathname.includes('/schedules')) return 'operations'
  if (pathname.includes('/connections') || pathname.includes('/topology') || pathname.includes('/logical-schemas') || pathname.includes('/environments') || pathname.includes('/schema-bindings')) return 'connections'
  if (pathname.includes('/objects') || pathname.includes('/development') || pathname.includes('/definitions') || pathname.includes('/models')) return 'development'
  return 'project'
}

export function WorkspaceNavigation({ hasPendingChanges, onNavigate, collapsed = false, projectUuid }: {
  hasPendingChanges: boolean
  onNavigate: (path: string) => void
  collapsed?: boolean
  projectUuid?: string
}) {
  const { t, i18n } = useTranslation()
  const location = useLocation()
  const [models, setModels] = useState<Model[]>([])
  const [catalogs, setCatalogs] = useState<Record<string, ModelCatalog>>({})
  const [catalogErrors, setCatalogErrors] = useState<string[]>([])
  const [openKeys, setOpenKeys] = useState<string[]>(location.pathname.includes('/models') ? ['models'] : [])
  const manuallyClosed = useRef(new Set<string>())
  const requestedModels = useRef(new Set<string>())
  const catalogGeneration = useRef(0)
  const currentProject = useRef(projectUuid)
  currentProject.current = projectUuid
  const loadCatalog = useCallback((model: Model) => {
    if (!projectUuid || requestedModels.current.has(model.uuid)) return
    requestedModels.current.add(model.uuid)
    const generation = catalogGeneration.current
    void Promise.all([topologyApi.listSubmodels(projectUuid, model.uuid), topologyApi.listDataObjects(projectUuid, model.uuid)])
      .then(([folders, objects]) => { if (currentProject.current === projectUuid && catalogGeneration.current === generation) {
        setCatalogs(current => ({ ...current, [model.uuid]: { folders, objects } }))
        setCatalogErrors(current => current.filter(uuid => uuid !== model.uuid))
      } })
      .catch(() => { if (currentProject.current === projectUuid && catalogGeneration.current === generation) {
        requestedModels.current.delete(model.uuid)
        setCatalogErrors(current => current.includes(model.uuid) ? current : [...current, model.uuid])
      } })
  }, [projectUuid])
  useEffect(() => {
    if (!projectUuid) return
    let active = true
    const refresh = () => { catalogGeneration.current++; requestedModels.current.clear(); manuallyClosed.current.clear(); setCatalogs({}); setCatalogErrors([]); void topologyApi.listModels(projectUuid).then(rows => { if (active) setModels(rows) }).catch(() => { if (active) setModels([]) }) }
    refresh()
    window.addEventListener('akis:models-changed', refresh)
    return () => { active = false; window.removeEventListener('akis:models-changed', refresh) }
  }, [projectUuid])
  useEffect(() => { if (location.pathname.includes('/models') && !manuallyClosed.current.has('models')) setOpenKeys(keys => keys.includes('models') ? keys : [...keys, 'models']) }, [location.pathname])
  const modelSegment = location.pathname.match(/\/models\/([^/?]+)/)?.[1]
  const selectedModel = models.find(model => model.code === decodeURIComponent(modelSegment ?? ''))
  const catalog = selectedModel ? catalogs[selectedModel.uuid] : undefined
  const params = new URLSearchParams(location.search)
  const selectedObject = catalog?.objects.find(item => matchesCatalogReference(item, params.get('object')))
  const selectedFolder = catalog?.folders.find(item => matchesCatalogReference(item, params.get('folder')))
  const selectedKey = selectedObject ? `object-open:${selectedObject.uuid}` : selectedFolder ? `folder-open:${selectedFolder.uuid}` : selectedModel ? `model-open:${selectedModel.uuid}` : resolveNavigationItem(location.pathname)
  const selectedBranchKeys = () => {
    if (!selectedModel) return []
    const keys = ['models', `model:${selectedModel.uuid}`]
    const visited = new Set<string>()
    let current = catalog?.folders.find(item => item.uuid === (selectedObject?.submodelUuid ?? selectedFolder?.uuid))
    while (current && !visited.has(current.uuid)) {
      visited.add(current.uuid)
      keys.push(`folder:${current.uuid}`)
      current = catalog?.folders.find(item => item.uuid === current?.parentUuid)
    }
    return keys
  }
  useEffect(() => {
    if (!selectedModel) return
    setOpenKeys(keys => {
      const branch = ['models', `model:${selectedModel.uuid}`].filter(key => !manuallyClosed.current.has(key))
      return branch.every(key => keys.includes(key)) ? keys : [...new Set([...keys, ...branch])]
    })
    loadCatalog(selectedModel)
  }, [selectedModel, loadCatalog])
  useEffect(() => {
    if (!catalog) return
    const branch = selectedBranchKeys().filter(key => !manuallyClosed.current.has(key))
    setOpenKeys(keys => branch.every(key => keys.includes(key)) ? keys : [...new Set([...keys, ...branch])])
  }, [catalog, selectedObject, selectedFolder])
  const tr = i18n.language.startsWith('tr')
  const folderItems = (folder: Submodel, modelCatalog: ModelCatalog, visited: Set<string>): NonNullable<MenuProps['items']>[number] => {
    const children = modelCatalog.folders.filter(item => item.parentUuid === folder.uuid && !visited.has(item.uuid))
      .map(item => folderItems(item, modelCatalog, new Set([...visited, item.uuid])))
    const objects = modelCatalog.objects.filter(item => item.submodelUuid === folder.uuid).map(objectItem)
    if (!children.length && !objects.length) return { key: `folder-open:${folder.uuid}`, label: folder.name, title: folder.name, icon: <ProjectFolderIcon size={15} /> }
    return { key: `folder:${folder.uuid}`, label: folder.name, title: folder.name, icon: <ProjectFolderIcon size={15} open={openKeys.includes(`folder:${folder.uuid}`)} />,
      children: [{ key: `folder-open:${folder.uuid}`, label: tr ? 'Klasörü Aç' : 'Open Folder' }, ...children, ...objects] }
  }
  const objectItem = (item: DataObject): NonNullable<MenuProps['items']>[number] => ({
    key: `object-open:${item.uuid}`, label: item.name, title: item.name,
    icon: <DataObjectTypeIcon type={item.type} size={15} />,
  })
  const modelItems = models.map(model => {
    const modelCatalog = catalogs[model.uuid]
    const folders = modelCatalog?.folders.filter(item => !item.parentUuid || !modelCatalog.folders.some(parent => parent.uuid === item.parentUuid))
      .map(item => folderItems(item, modelCatalog, new Set([item.uuid]))) ?? []
    const objects = modelCatalog?.objects.filter(item => !item.submodelUuid || !modelCatalog.folders.some(folder => folder.uuid === item.submodelUuid))
      .map(objectItem) ?? []
    return { key: `model:${model.uuid}`, label: model.name, title: model.name,
      icon: <span className="workspace-icon workspace-icon--models" aria-hidden="true"><Layers3 size={15} /></span>,
      children: [{ key: `model-open:${model.uuid}`, label: tr ? 'Modeli Aç' : 'Open Model' },
        ...(catalogErrors.includes(model.uuid) ? [{ key: `model-retry:${model.uuid}`, label: tr ? 'İçeriği Yeniden Yükle' : 'Retry Loading Contents' }] : []),
        ...folders, ...objects] }
  })
  void hasPendingChanges // The shell guards every onNavigate request.
  return <div className={`workspace-navigation-shell${collapsed ? ' is-collapsed' : ''}`}>
    <div className="workspace-navigation-heading" aria-hidden={collapsed}>
      <span className="workspace-navigation-heading-copy"><small>{t('nav.workspace')}</small><strong>{t('nav.sidebarTitle')}</strong></span>
    </div>
    <Menu mode="inline" inlineCollapsed={collapsed} className="workspace-navigation" aria-label={t('nav.workspaces')}
      selectedKeys={[selectedKey]}
      openKeys={collapsed ? [] : openKeys}
      onOpenChange={keys => {
        const next = keys.map(String)
        openKeys.filter(key => !next.includes(key)).forEach(key => manuallyClosed.current.add(key))
        next.forEach(key => manuallyClosed.current.delete(key))
        setOpenKeys(next)
        next.filter(key => key.startsWith('model:') && !openKeys.includes(key)).forEach(key => {
          const model = models.find(item => item.uuid === key.slice(6))
          if (model) loadCatalog(model)
        })
      }}
      onClick={({ key }) => {
        if (key === 'models-all') { onNavigate(projectRoute('/models')); return }
        if (key.startsWith('model-retry:')) { const model = models.find(item => item.uuid === key.slice(12)); if (model) loadCatalog(model); return }
        if (key.startsWith('model-open:')) { const model = models.find(item => item.uuid === key.slice(11)); if (model) onNavigate(modelPath(model)); return }
        if (key.startsWith('folder-open:')) {
          const folder = Object.values(catalogs).flatMap(value => value.folders).find(item => item.uuid === key.slice(12))
          const model = models.find(item => item.uuid === folder?.modelUuid)
          if (folder && model) onNavigate(`${modelPath(model)}?folder=${encodeURIComponent(folder.code)}`)
          return
        }
        if (key.startsWith('object-open:')) {
          const item = Object.values(catalogs).flatMap(value => value.objects).find(value => value.uuid === key.slice(12))
          const model = models.find(value => value.uuid === item?.modelUuid)
          const folder = catalogs[model?.uuid ?? '']?.folders.find(value => value.uuid === item?.submodelUuid)
          if (item && model) onNavigate(`${modelPath(model)}?${folder ? `folder=${encodeURIComponent(folder.code)}&` : ''}object=${encodeURIComponent(item.code)}`)
          return
        }
        const destination = destinations.find(item => item.id === key)!
        onNavigate(destination.absolute ? destination.path : projectRoute(destination.path))
      }}
      items={sections.map(({ key: sectionKey, label, items }) => ({
        key: sectionKey,
        type: 'group' as const,
        label: <span className="workspace-navigation-group-label">{t(label)}</span>,
        children: items.map(id => {
          const destination = destinations.find(item => item.id === id)!
          const Icon = destination.icon
          return {
            key: destination.id,
            className: `workspace-nav--${destination.id}`,
            label: t(destination.key),
            title: t(destination.key),
            icon: <span className={`workspace-icon workspace-icon--${destination.id}`} data-ui-icon={destination.id} aria-hidden="true"><Icon size={16} /></span>,
            ...(id === 'models' ? { children: [
              { key: 'models-all', label: tr ? 'Tüm Modeller' : 'All Models', icon: <Layers3 size={15} /> },
              ...modelItems,
            ] } : {}),
          }
        }),
      })) as MenuProps['items']} />
  </div>
}
