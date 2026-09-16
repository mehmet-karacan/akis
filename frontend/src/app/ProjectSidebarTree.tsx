import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Tree, Dropdown, Button, Spin, Alert, type MenuProps, type TreeDataNode } from 'antd'
import { Blocks, Folder, FolderPlus, MoreHorizontal, PanelRightOpen, Play, Plus, Workflow, ExternalLink, WandSparkles } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { DefinitionTypeIcon } from '../features/definitions/DefinitionTypeIcon'
import { buildFolderTree, type FolderTreeNode } from './ProjectObjectTreeAdapter'
import { definitionTypeKey, useDefinitionsI18n } from '../features/definitions/i18n'
import type { Definition, Folder as ProjectFolder } from '../features/definitions/types'
import { definitionsApi } from '../features/definitions/api'
import { useProjectAccess } from '../core/auth/ProjectAccessContext'
import { projectRoute } from '../features/projects/CurrentProjectContext'
import { ProjectModelTree } from './ProjectModelTree'
import { FeedbackToast } from '../core/ui/FeedbackToast'

const FLOW_GROUPS = [
  { type: 'MAPPING', label: 'nav.interfaces' }, { type: 'PROCEDURE', label: 'nav.procedures' },
  { type: 'PACKAGE', label: 'nav.packages' }, { type: 'LOAD_PLAN', label: 'nav.loadPlans' },
] as const
const COMPONENT_TYPES = ['VARIABLE', 'SEQUENCE', 'USER_FUNCTION', 'KNOWLEDGE_MODULE'] as const
interface Props {
  folders: ProjectFolder[]; definitions: Definition[]; selectedUuid: string | null
  loading: boolean; failed: boolean; onNavigate(path: string): void; onRetry(): void; projectUuid: string
}

function TreeNodeTitle({ label, icon, onClick, items, count, actionLabel }: { label: string; icon: ReactNode; onClick(): void; items?: MenuProps['items']; count?: number; actionLabel: string }) {
  const [open, setOpen] = useState(false)
  const content = <div className="akis-tree-title sidebar-folder-action-row">
    <Button type="text" onClick={event => { event.stopPropagation(); onClick() }} icon={icon}><span>{label}</span>{count !== undefined && <small>{count}</small>}</Button>
    {!!items?.length && <Button type="text" size="small" icon={<MoreHorizontal size={14} />} aria-label={actionLabel} onClick={event => { event.stopPropagation(); setOpen(value => !value) }} />}
  </div>
  return items?.length ? <Dropdown open={open} onOpenChange={setOpen} destroyOnHidden menu={{ items, onClick: () => setOpen(false), onKeyDown: event => event.stopPropagation() }} trigger={['contextMenu']} autoFocus>{content}</Dropdown> : content
}

export function ProjectSidebarTree({ folders, definitions, selectedUuid, loading, failed, onNavigate, onRetry, projectUuid }: Props) {
  const { t: shellT, i18n } = useTranslation()
  const { t } = useDefinitionsI18n()
  const { can } = useProjectAccess()
  const canWrite = can('TANIM_DUZENLE')
  const [expanded, setExpanded] = useState<React.Key[]>(['flows', 'components'])
  const [compiling, setCompiling] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)
  const active = definitions.filter(item => !['PASIF', 'ARSIVLENDI'].includes(item.status) && item.type !== 'REUSABLE_MAPPING')
  const tree = useMemo(() => buildFolderTree(folders.filter(item => !['PASIF', 'ARSIVLENDI'].includes(item.status)), i18n.language), [folders, i18n.language])
  useEffect(() => {
    const selected = definitions.find(item => item.uuid === selectedUuid)
    setExpanded(current => [...new Set([...current, ...tree.map(item => item.uuid),
      ...(selected ? [`${selected.folderUuid ?? 'unfiled'}:${selected.type}`, selected.type, selected.folderUuid ?? 'unfiled'] : [])])])
  }, [definitions, selectedUuid, tree])
  const navigate = (suffix: string) => onNavigate(projectRoute(suffix))
  const openDefinition = (uuid: string) => navigate(`/objects/definitions/${encodeURIComponent(uuid)}`)
  const create = (type: string, folder = '') => navigate(`/objects?createType=${encodeURIComponent(type)}&folder=${encodeURIComponent(folder)}`)
  const toggle = (key: string) => setExpanded(current => current.includes(key) ? current.filter(item => item !== key) : [...current, key])
  const compile = async (definition: Definition) => {
    setCompiling(definition.uuid)
    try {
      const versions = await definitionsApi.listVersions(projectUuid, definition.uuid)
      if (!versions[0]) throw new Error(shellT('nav.scenarioNeedsVersion'))
      const result = await definitionsApi.compileScenario(projectUuid, definition.uuid, versions[0].uuid)
      setNotice({ tone: 'success', text: shellT('nav.scenarioCreated', { version: result.scenarioVersion }) })
    } catch (error) { setNotice({ tone: 'error', text: error instanceof Error ? error.message : shellT('nav.actionFailed') }) }
    finally { setCompiling(null) }
  }
  const title = (label: string, icon: ReactNode, click: () => void, items?: MenuProps['items'], count?: number, actionLabel?: string) => {
    return <TreeNodeTitle label={label} icon={icon} onClick={click} items={items} count={count} actionLabel={actionLabel ?? shellT('nav.objectActions', { name: label })} />
  }
  const objectNode = (definition: Definition): TreeDataNode => {
    const executable = FLOW_GROUPS.some(group => group.type === definition.type)
    const items: MenuProps['items'] = [{ key: 'open', label: shellT('nav.openObject'), icon: <ExternalLink size={15} />, onClick: () => openDefinition(definition.uuid) }]
    if (executable && can('TANIM_DOGRULA')) items.push({ key: 'compile', label: shellT('nav.createScenario'), icon: <WandSparkles size={15} />, disabled: compiling === definition.uuid, onClick: () => void compile(definition) })
    if (executable) items.push({ key: 'run', label: shellT('nav.runObject'), icon: <Play size={15} />, onClick: () => navigate(`/operations?definition=${encodeURIComponent(definition.uuid)}&start=1`) })
    return { key: definition.uuid, isLeaf: true, title: <span title={`${definition.name} · ${t(definitionTypeKey[definition.type])}`}>{title(definition.name, <DefinitionTypeIcon type={definition.type} />, () => openDefinition(definition.uuid), items)}</span> }
  }
  const flowGroups = (folderUuid: string | null): TreeDataNode[] => FLOW_GROUPS.map(group => {
    const items = active.filter(item => item.folderUuid === folderUuid && item.type === group.type)
    const key = `${folderUuid ?? 'unfiled'}:${group.type}`
    const label = shellT(group.label)
    return { key, title: title(label, <DefinitionTypeIcon type={group.type} />, () => toggle(key), canWrite ? [{ key: 'add', label: shellT('nav.addNamed', { name: label }), icon: <Plus size={15} />, onClick: () => create(group.type, folderUuid ?? '') }] : [], items.length), children: items.map(objectNode) }
  })
  const folderNode = (folder: FolderTreeNode): TreeDataNode => ({ key: folder.uuid,
    title: title(folder.name, <Folder className="project-folder-icon" size={16} />, () => toggle(folder.uuid), canWrite ? [{ key: 'folder', label: shellT('nav.createSubfolder'), icon: <FolderPlus size={15} />, onClick: () => navigate(`/objects?createFolder=${encodeURIComponent(folder.uuid)}`) }] : []),
    children: [...folder.children.map(folderNode), ...flowGroups(folder.uuid)] })
  const componentActions: MenuProps['items'] = canWrite ? COMPONENT_TYPES.map(type => ({ key: type, label: shellT('nav.addNamed', { name: t(definitionTypeKey[type]) }), icon: <DefinitionTypeIcon type={type} />, onClick: () => navigate(`/objects?createType=${type}`) })) : []
  const nodes: TreeDataNode[] = [
    { key: 'flows', title: title(shellT('nav.flows'), <Workflow className="project-flow-root-icon" size={16} />, () => toggle('flows'), canWrite ? [{ key: 'root', label: shellT('nav.createRootFolder'), icon: <FolderPlus size={15} />, onClick: () => navigate('/objects?createFolder=') }] : []), children: [...tree.map(folderNode), ...(active.some(item => !item.folderUuid && FLOW_GROUPS.some(group => group.type === item.type)) ? [{ key: 'unfiled', title: title(shellT('nav.unfiled'), <Folder className="project-folder-icon" size={16} />, () => toggle('unfiled')), children: flowGroups(null) }] : [])] },
    { key: 'components', title: title(shellT('nav.commonComponents'), <Blocks className="project-component-root-icon" size={16} />, () => toggle('components'), componentActions, undefined, shellT('nav.addComponent')), children: COMPONENT_TYPES.map(type => ({ key: type, title: title(t(definitionTypeKey[type]), <DefinitionTypeIcon type={type} />, () => toggle(type), componentActions?.filter(item => item?.key === type)), children: active.filter(item => item.type === type).map(objectNode) })) },
  ]
  return <section className="sidebar-project-tree" aria-label={shellT('nav.objects')}>
    <header><strong>{shellT('nav.objects')}</strong><Button type="text" icon={<PanelRightOpen size={16} />} aria-label={shellT('nav.openObjectWorkspace')} onClick={() => navigate('/objects')} /></header>
    <FeedbackToast message={notice?.text ?? ''} tone={notice?.tone} onClose={() => setNotice(null)} />
    <div className="sidebar-tree-scroll">{loading ? <Spin /> : failed ? <Alert type="error" title={shellT('common.loadError')} action={<Button onClick={onRetry}>{shellT('common.retry')}</Button>} /> : <Tree blockNode motion={{ motionAppear: false, motionEnter: false, motionLeave: false }} virtual={false} expandedKeys={expanded} onExpand={setExpanded} selectedKeys={selectedUuid ? [selectedUuid] : []} treeData={nodes} />}
      <ProjectModelTree key={projectUuid} projectUuid={projectUuid} onNavigate={onNavigate} />
    </div>
  </section>
}
