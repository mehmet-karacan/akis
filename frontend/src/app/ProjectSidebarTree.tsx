import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Tree, Dropdown, Button, Input, Spin, Alert, type MenuProps, type TreeDataNode } from 'antd'
import { Blocks, FolderPlus, MoreHorizontal, PanelRightOpen, Play, Plus, Trash2, Workflow, WandSparkles } from 'lucide-react'
import { Dialog } from '../core/ui/Dialog'
import { Button as AkisButton } from '../core/ui/Button'
import { useTranslation } from 'react-i18next'
import { DefinitionTypeIcon, ProjectFolderIcon } from '../features/definitions/DefinitionTypeIcon'
import { buildFolderTree, folderPath, type FolderTreeNode } from './ProjectObjectTreeAdapter'
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
const COMPONENT_TYPES = ['VARIABLE', 'SEQUENCE', 'KNOWLEDGE_MODULE'] as const
interface Props {
  folders: ProjectFolder[]; definitions: Definition[]; selectedUuid: string | null
  loading: boolean; failed: boolean; onNavigate(path: string): void; onRetry(): void; projectUuid: string
}

function TreeNodeTitle({ label, icon, onClick, items, count, actionLabel, variant = 'item', recordAction }: { label: string; icon: ReactNode; onClick(): void; items?: MenuProps['items']; count?: number; actionLabel: string; subtitle?: string; variant?: 'section' | 'folder' | 'group' | 'object' | 'item'; recordAction?: () => void }) {
  const [open, setOpen] = useState(false)
  const labelContent = <><span className="akis-tree-label"><span>{label}</span></span>{count !== undefined && <span className="akis-tree-count" aria-hidden="true">{count}</span>}</>
  const content = <div className={`akis-tree-title akis-tree-title--${variant} sidebar-folder-action-row`}>
    {variant === 'object'
      ? <div className="akis-tree-static-label" tabIndex={0} aria-label={label} onDoubleClick={event => { event.stopPropagation(); recordAction?.() }} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); recordAction?.() } }}>{icon}{labelContent}</div>
      : <Button type="text" aria-label={label} onClick={event => { event.stopPropagation(); onClick() }} icon={icon}>{labelContent}</Button>}
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
  const [query, setQuery] = useState('')
  const [selection, setSelection] = useState<string | null>(selectedUuid)
  useEffect(() => setSelection(selectedUuid), [selectedUuid])
  const [compiling, setCompiling] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)
  const [pendingDelete, setPendingDelete] = useState<Definition | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState('')
  // Delete = archive on the server; a package that still uses the object blocks it, and the message names the package.
  const confirmDelete = async () => {
    if (!pendingDelete) return
    setDeleting(true); setDeleteError('')
    try {
      await definitionsApi.deleteDefinition(projectUuid, pendingDelete.uuid, pendingDelete.version)
      setNotice({ tone: 'success', text: shellT('nav.deleted', { name: pendingDelete.name }) })
      setPendingDelete(null)
      window.dispatchEvent(new Event('akis:definitions-changed'))
      if (selectedUuid === pendingDelete.uuid) navigate('/objects')
    } catch (error) { setDeleteError(error instanceof Error ? error.message : shellT('nav.actionFailed')) }
    finally { setDeleting(false) }
  }
  const active = definitions.filter(item => !['PASIF', 'ARSIVLENDI'].includes(item.status) && item.type !== 'REUSABLE_MAPPING')
  const tree = useMemo(() => buildFolderTree(folders.filter(item => !['PASIF', 'ARSIVLENDI'].includes(item.status)), i18n.language), [folders, i18n.language])
  useEffect(() => {
    const selected = definitions.find(item => item.uuid === selectedUuid)
    setExpanded(current => [...new Set([...current, ...tree.map(item => item.uuid),
      ...(selected ? [FLOW_GROUPS.some(group => group.type === selected.type) ? 'flows' : 'components',
        `${selected.folderUuid ?? 'unfiled'}:${selected.type}`, selected.type,
        ...(selected.folderUuid ? folderPath(tree, selected.folderUuid) : ['unfiled'])] : [])])])
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
  const title = (label: string, icon: ReactNode, click: () => void, items?: MenuProps['items'], count?: number, actionLabel?: string, subtitle?: string, variant?: 'section' | 'folder' | 'group' | 'object' | 'item', recordAction?: () => void) => {
    return <TreeNodeTitle label={label} icon={icon} onClick={click} items={items} count={count} subtitle={subtitle} variant={variant} actionLabel={actionLabel ?? shellT('nav.objectActions', { name: label })} recordAction={recordAction} />
  }
  const objectNode = (definition: Definition): TreeDataNode => {
    const executable = FLOW_GROUPS.some(group => group.type === definition.type)
    const items: MenuProps['items'] = []
    if (executable && can('TANIM_DOGRULA')) items.push({ key: 'compile', label: shellT('nav.createScenario'), icon: <WandSparkles size={15} />, disabled: compiling === definition.uuid, onClick: () => void compile(definition) })
    if (executable) items.push({ key: 'run', label: shellT('nav.runObject'), icon: <Play size={15} />, onClick: () => navigate(`/operations?definition=${encodeURIComponent(definition.uuid)}&start=1`) })
    if (canWrite) { if (items.length) items.push({ type: 'divider' }); items.push({ key: 'delete', label: shellT('nav.deleteObject'), icon: <Trash2 size={15} />, danger: true, onClick: () => { setDeleteError(''); setPendingDelete(definition) } }) }
    return { key: definition.uuid, isLeaf: true, className: 'project-tree-object-node', title: <span title={`${definition.name} · ${t(definitionTypeKey[definition.type])}`}>{title(definition.name, <DefinitionTypeIcon type={definition.type} />, () => undefined, items, undefined, undefined, t(definitionTypeKey[definition.type]), 'object', () => openDefinition(definition.uuid))}</span> }
  }
  const flowGroups = (folderUuid: string | null): TreeDataNode[] => FLOW_GROUPS.flatMap(group => {
    const items = active.filter(item => item.folderUuid === folderUuid && item.type === group.type)
    const key = `${folderUuid ?? 'unfiled'}:${group.type}`
    const label = shellT(group.label)
    if (items.length === 0) return []
    return [{ key, className: 'project-tree-type-node', title: title(label, <DefinitionTypeIcon type={group.type} />, () => toggle(key), canWrite ? [{ key: 'add', label: shellT('nav.addNamed', { name: label }), icon: <Plus size={15} />, onClick: () => create(group.type, folderUuid ?? '') }] : [], items.length, undefined, undefined, 'group'), children: items.map(objectNode) }]
  })
  const folderCount = (folder: FolderTreeNode): number => active.filter(item => item.folderUuid === folder.uuid && FLOW_GROUPS.some(group => group.type === item.type)).length + folder.children.reduce((sum, child) => sum + folderCount(child), 0)
  const folderActions = (folderUuid: string): MenuProps['items'] => canWrite ? [
    { key: 'folder', label: shellT('nav.createSubfolder'), icon: <FolderPlus size={15} />, onClick: () => navigate(`/objects?createFolder=${encodeURIComponent(folderUuid)}`) },
    { type: 'divider' },
    ...FLOW_GROUPS.map(group => ({ key: group.type, label: shellT('nav.addNamed', { name: shellT(group.label) }), icon: <DefinitionTypeIcon type={group.type} />, onClick: () => create(group.type, folderUuid) })),
  ] : []
  const folderNode = (folder: FolderTreeNode): TreeDataNode => ({ key: folder.uuid, className: 'project-tree-folder-node',
    title: title(folder.name, <ProjectFolderIcon open={expanded.includes(folder.uuid)} />, () => toggle(folder.uuid), folderActions(folder.uuid), folderCount(folder), undefined, shellT('nav.folderContent'), 'folder'),
    children: [...folder.children.map(folderNode), ...flowGroups(folder.uuid)] })
  const componentActions: MenuProps['items'] = canWrite ? COMPONENT_TYPES.map(type => ({ key: type, label: shellT('nav.addNamed', { name: t(definitionTypeKey[type]) }), icon: <DefinitionTypeIcon type={type} />, onClick: () => navigate(`/objects?createType=${type}`) })) : []
  const nodes: TreeDataNode[] = [
    { key: 'flows', className: 'project-tree-section-node', title: title(shellT('nav.flows'), <Workflow className="project-flow-root-icon" size={16} />, () => toggle('flows'), canWrite ? [{ key: 'root', label: shellT('nav.createRootFolder'), icon: <FolderPlus size={15} />, onClick: () => navigate('/objects?createFolder=') }, { type: 'divider' }, ...(componentActions ?? [])] : [], tree.reduce((sum, folder) => sum + folderCount(folder), 0), undefined, shellT('nav.flowsHint'), 'section'), children: [...tree.map(folderNode), ...(active.some(item => !item.folderUuid && FLOW_GROUPS.some(group => group.type === item.type)) ? [{ key: 'unfiled', className: 'project-tree-folder-node', title: title(shellT('nav.unfiled'), <ProjectFolderIcon open={expanded.includes('unfiled')} />, () => toggle('unfiled'), undefined, undefined, undefined, shellT('nav.folderContent'), 'folder'), children: flowGroups(null) }] : [])] },
    { key: 'components', className: 'project-tree-section-node', title: title(shellT('nav.commonComponents'), <Blocks className="project-component-root-icon" size={16} />, () => toggle('components'), componentActions, active.filter(item => COMPONENT_TYPES.includes(item.type as typeof COMPONENT_TYPES[number])).length, shellT('nav.addComponent'), shellT('nav.componentsHint'), 'section'), children: COMPONENT_TYPES.map(type => { const items = active.filter(item => item.type === type); return { key: type, className: 'project-tree-type-node', title: title(t(definitionTypeKey[type]), <DefinitionTypeIcon type={type} />, () => toggle(type), componentActions?.filter(item => item?.key === type), items.length, undefined, undefined, 'group'), children: items.map(objectNode) } }) },
  ]
  const term = query.trim().toLocaleLowerCase(i18n.language)
  const matchingKeys = new Set([...active.filter(item => `${item.name} ${item.code}`.toLocaleLowerCase(i18n.language).includes(term)).map(item => item.uuid), ...folders.filter(item => item.name.toLocaleLowerCase(i18n.language).includes(term)).map(item => item.uuid)])
  const filtered = (items: TreeDataNode[]): TreeDataNode[] => items.flatMap(node => {
    if (!term || matchingKeys.has(String(node.key))) return [node]
    const children = filtered(node.children ?? [])
    return children.length ? [{ ...node, children }] : []
  })
  const visibleNodes = filtered(nodes)
  const allKeys = (items: TreeDataNode[]): React.Key[] => items.flatMap(item => [item.key, ...allKeys(item.children ?? [])])
  return <section className="sidebar-project-tree" aria-label={shellT('nav.objects')}>
    <header><strong>{shellT('nav.objects')}</strong><Button type="text" icon={<PanelRightOpen size={16} />} aria-label={shellT('nav.openObjectWorkspace')} onClick={() => navigate('/objects')} /></header>
    <FeedbackToast message={notice?.text ?? ''} tone={notice?.tone} onClose={() => setNotice(null)} />
    <Dialog open={pendingDelete !== null} title={shellT('nav.deleteObject')} closeLabel={shellT('common.cancel')} busy={deleting} onClose={() => setPendingDelete(null)} className="akis-modal">
      {pendingDelete && <div className="sidebar-delete-dialog">
        <p className="definition-type-chip"><DefinitionTypeIcon type={pendingDelete.type} size={12} />{t(definitionTypeKey[pendingDelete.type])} · <strong>{pendingDelete.name}</strong> <code>{pendingDelete.code}</code></p>
        <p>{shellT('nav.deleteExplain')}</p>
        <p className="sidebar-delete-warning">{shellT('nav.deletePackageRule')}</p>
        {deleteError && <div className="definition-notice definition-notice--error" role="alert">{deleteError}</div>}
        <div className="sidebar-delete-actions">
          <AkisButton tone="ghost" type="button" onClick={() => setPendingDelete(null)}>{shellT('common.cancel')}</AkisButton>
          <AkisButton tone="danger" type="button" icon={<Trash2 size={15} />} busy={deleting} onClick={() => void confirmDelete()}>{shellT('nav.deleteConfirm')}</AkisButton>
        </div>
      </div>}
    </Dialog>
    <Input.Search className="project-explorer-search" allowClear aria-label={i18n.language === 'tr' ? 'Proje nesnelerinde ara' : 'Search project objects'} placeholder={i18n.language === 'tr' ? 'Nesne ara' : 'Search objects'} value={query} onChange={event => setQuery(event.target.value)} />
    <div className="sidebar-tree-scroll">{loading ? <Spin /> : failed ? <Alert type="error" title={shellT('common.loadError')} action={<Button onClick={onRetry}>{shellT('common.retry')}</Button>} /> : <Tree blockNode motion={{ motionAppear: false, motionEnter: false, motionLeave: false }} virtual={false} expandedKeys={term ? allKeys(visibleNodes) : expanded} onExpand={setExpanded} selectedKeys={selection ? [selection] : []} onSelect={keys => setSelection(keys[0] ? String(keys[0]) : null)} treeData={visibleNodes} />}
      <ProjectModelTree key={projectUuid} projectUuid={projectUuid} onNavigate={onNavigate} />
    </div>
  </section>
}
