import {
  ChevronDown,
  ChevronRight,
  FileCode2,
  Folder,
  FolderInput,
  FolderOpen,
  FolderPlus,
  MoreHorizontal,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type MouseEvent } from 'react'
import { DefinitionTypeIcon } from './DefinitionTypeIcon'
import { definitionTypeKey, useDefinitionsI18n } from './i18n'
import type { Definition, Folder as ProjectFolder } from './types'

export interface FolderTreeNode extends ProjectFolder {
  children: FolderTreeNode[]
}

export function buildFolderTree(folders: ProjectFolder[]): FolderTreeNode[] {
  const sorted = [...folders].sort((left, right) => left.name.localeCompare(right.name))
  const byParent = new Map<string | null, ProjectFolder[]>()
  const knownUuids = new Set(sorted.map((folder) => folder.uuid))

  for (const folder of sorted) {
    const parentUuid = folder.parentUuid && knownUuids.has(folder.parentUuid) ? folder.parentUuid : null
    byParent.set(parentUuid, [...(byParent.get(parentUuid) ?? []), folder])
  }

  const visited = new Set<string>()
  const visit = (folder: ProjectFolder, ancestors: Set<string>): FolderTreeNode | null => {
    if (visited.has(folder.uuid) || ancestors.has(folder.uuid)) return null
    visited.add(folder.uuid)
    const lineage = new Set(ancestors).add(folder.uuid)
    const children = (byParent.get(folder.uuid) ?? [])
      .map((child) => visit(child, lineage))
      .filter((child): child is FolderTreeNode => child !== null)
    return { ...folder, children }
  }

  const roots = (byParent.get(null) ?? [])
    .map((folder) => visit(folder, new Set()))
    .filter((folder): folder is FolderTreeNode => folder !== null)

  // A defensive fallback keeps corrupt cyclic hierarchies visible without recursing forever.
  for (const folder of sorted) {
    if (visited.has(folder.uuid)) continue
    const recovered = visit(folder, new Set())
    if (recovered) roots.push(recovered)
  }
  return roots
}

interface ProjectExplorerProps {
  folders: ProjectFolder[]
  definitions: Definition[]
  selectedUuid: string | null
  onSelect: (uuid: string) => void
  onCreateFolder: () => void
  onMoveFolder: (folder: ProjectFolder) => void
}

export function ProjectExplorer({ folders, definitions, selectedUuid, onSelect, onCreateFolder, onMoveFolder }: ProjectExplorerProps) {
  const { t } = useDefinitionsI18n()
  const tree = useMemo(() => buildFolderTree(folders), [folders])
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(folders.filter((folder) => !folder.parentUuid).map((folder) => folder.uuid)))
  const knownFolders = useRef(new Set(folders.map((folder) => folder.uuid)))
  const [menuFolderUuid, setMenuFolderUuid] = useState<string | null>(null)
  const definitionsByFolder = useMemo(() => {
    const grouped = new Map<string | null, Definition[]>()
    for (const definition of definitions) {
      grouped.set(definition.folderUuid, [...(grouped.get(definition.folderUuid) ?? []), definition])
    }
    for (const items of grouped.values()) items.sort((left, right) => left.name.localeCompare(right.name))
    return grouped
  }, [definitions])

  useEffect(() => {
    const available = new Set(folders.map((folder) => folder.uuid))
    setExpanded((current) => {
      const next = new Set([...current].filter((uuid) => available.has(uuid)))
      for (const folder of folders) if (!knownFolders.current.has(folder.uuid) && !folder.parentUuid) next.add(folder.uuid)
      return next
    })
    knownFolders.current = available
  }, [folders])

  useEffect(() => {
    if (!menuFolderUuid) return
    const close = (event: KeyboardEvent) => { if (event.key === 'Escape') setMenuFolderUuid(null) }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [menuFolderUuid])

  const toggle = (uuid: string) => {
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(uuid)) next.delete(uuid)
      else next.add(uuid)
      return next
    })
  }

  const definitionItem = (definition: Definition) => (
    <li className="explorer-definition" key={definition.uuid} role="treeitem" aria-selected={definition.uuid === selectedUuid}>
      <button
        type="button"
        className={definition.uuid === selectedUuid ? 'is-selected' : ''}
        aria-current={definition.uuid === selectedUuid ? 'page' : undefined}
        onClick={() => onSelect(definition.uuid)}
      >
        <span className={`explorer-definition-icon explorer-definition-icon--${definition.type.toLowerCase().replaceAll('_', '-')}`}>
          <DefinitionTypeIcon type={definition.type} />
        </span>
        <span><strong>{definition.name}</strong><small>{t(definitionTypeKey[definition.type])} · {definition.code}</small></span>
      </button>
    </li>
  )

  const folderItem = (folder: FolderTreeNode) => {
    const isExpanded = expanded.has(folder.uuid)
    const directDefinitions = definitionsByFolder.get(folder.uuid) ?? []
    const childCount = folder.children.length + directDefinitions.length
    const openMenu = (event: MouseEvent) => { event.preventDefault(); setMenuFolderUuid(folder.uuid) }
    return <li className="explorer-folder" key={folder.uuid} role="treeitem" aria-expanded={isExpanded}>
      <div className="explorer-folder-row" onContextMenu={openMenu}>
        <button type="button" className="explorer-toggle" aria-expanded={isExpanded} aria-label={isExpanded ? t('collapseFolder') : t('expandFolder')} onClick={() => toggle(folder.uuid)}>
          {isExpanded ? <ChevronDown size={15} aria-hidden="true" /> : <ChevronRight size={15} aria-hidden="true" />}
        </button>
        <button type="button" className="explorer-folder-name" onClick={() => toggle(folder.uuid)}>
          {isExpanded ? <FolderOpen size={16} aria-hidden="true" /> : <Folder size={16} aria-hidden="true" />}
          <span>{folder.name}</span><small>{childCount}</small>
        </button>
        <button className="explorer-folder-action" type="button" aria-label={t('folderActionsNamed', { name: folder.name })} aria-haspopup="menu" aria-expanded={menuFolderUuid === folder.uuid} onClick={() => setMenuFolderUuid((current) => current === folder.uuid ? null : folder.uuid)}><MoreHorizontal size={15} aria-hidden="true" /></button>
        {menuFolderUuid === folder.uuid && <div className="explorer-context-menu" role="menu">
          <button type="button" role="menuitem" onClick={() => { setMenuFolderUuid(null); onMoveFolder(folder) }}><FolderInput size={14} aria-hidden="true" />{t('moveFolder')}</button>
        </div>}
      </div>
      {isExpanded ? <ul role="group">{folder.children.map(folderItem)}{directDefinitions.map(definitionItem)}</ul> : null}
    </li>
  }

  const unfiled = definitionsByFolder.get(null) ?? []
  const visibleCount = definitions.length

  return <nav className="project-explorer" aria-label={t('projectExplorer')}>
    <header>
      <div><span>{t('projectExplorer')}</span><small>{t('objectCount', { count: visibleCount })}</small></div>
      <button className="definition-icon-button" type="button" aria-label={t('newFolder')} title={t('newFolder')} onClick={onCreateFolder}><FolderPlus size={16} aria-hidden="true" /></button>
    </header>
    {tree.length === 0 && unfiled.length === 0 ? <div className="definition-state"><FileCode2 aria-hidden="true" /><p>{t('empty')}</p></div> : <ul className="explorer-tree" role="tree">
      {tree.map(folderItem)}
      {unfiled.length > 0 ? <li className="explorer-folder explorer-unfiled">
        <div className="explorer-folder-row"><span className="explorer-toggle" /><span className="explorer-folder-name"><Folder size={16} aria-hidden="true" /><span>{t('unfiled')}</span><small>{unfiled.length}</small></span></div>
        <ul role="group">{unfiled.map(definitionItem)}</ul>
      </li> : null}
    </ul>}
  </nav>
}
