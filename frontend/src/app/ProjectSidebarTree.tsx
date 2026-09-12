import { ChevronDown, ChevronRight, FileCode2, Folder, FolderOpen, PanelRightOpen, RefreshCw, Search } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { DefinitionTypeIcon } from '../features/definitions/DefinitionTypeIcon'
import { buildFolderTree, type FolderTreeNode } from '../features/definitions/ProjectExplorer'
import { definitionTypeKey, useDefinitionsI18n } from '../features/definitions/i18n'
import type { Definition, Folder as ProjectFolder } from '../features/definitions/types'

interface Props {
  folders: ProjectFolder[]
  definitions: Definition[]
  selectedUuid: string | null
  loading: boolean
  failed: boolean
  onNavigate(path: string): void
  onRetry(): void
  projectUuid: string
}

export function ProjectSidebarTree({ folders, definitions, selectedUuid, loading, failed, onNavigate, onRetry, projectUuid }: Props) {
  const { t: shellT } = useTranslation()
  const { t } = useDefinitionsI18n()
  const [query, setQuery] = useState('')
  const tree = useMemo(() => buildFolderTree(folders.filter((item) => item.status !== 'PASIF')), [folders])
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const activeDefinitions = useMemo(() => definitions.filter((item) => item.status !== 'PASIF'), [definitions])
  const matches = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase()
    if (!normalized) return activeDefinitions
    return activeDefinitions.filter((item) => `${item.name} ${item.code} ${t(definitionTypeKey[item.type])}`.toLocaleLowerCase().includes(normalized))
  }, [activeDefinitions, query, t])
  const definitionsByFolder = useMemo(() => {
    const grouped = new Map<string | null, Definition[]>()
    for (const definition of activeDefinitions) {
      grouped.set(definition.folderUuid, [...(grouped.get(definition.folderUuid) ?? []), definition])
    }
    for (const values of grouped.values()) values.sort((a, b) => a.name.localeCompare(b.name))
    return grouped
  }, [activeDefinitions])

  useEffect(() => {
    setExpanded(new Set(tree.map((folder) => folder.uuid)))
  }, [projectUuid, tree]) // Tree expansion belongs to the selected project.

  const toggle = (uuid: string) => setExpanded((current) => {
    const next = new Set(current)
    if (next.has(uuid)) next.delete(uuid); else next.add(uuid)
    return next
  })
  const openDefinition = (uuid: string) => onNavigate(`/projects/${encodeURIComponent(projectUuid)}/development?definition=${encodeURIComponent(uuid)}`)
  const definitionItem = (definition: Definition) => <li key={definition.uuid}>
    <button type="button" className={`sidebar-object ${selectedUuid === definition.uuid ? 'is-selected' : ''}`} onClick={() => openDefinition(definition.uuid)} title={`${definition.name} · ${t(definitionTypeKey[definition.type])}`}>
      <span className={`sidebar-object-icon sidebar-object-icon--${definition.type.toLowerCase().replaceAll('_', '-')}`}><DefinitionTypeIcon type={definition.type} /></span>
      <span><strong>{definition.name}</strong><small>{t(definitionTypeKey[definition.type])}</small></span>
    </button>
  </li>
  const folderItem = (folder: FolderTreeNode) => {
    const open = expanded.has(folder.uuid)
    const directDefinitions = definitionsByFolder.get(folder.uuid) ?? []
    return <li key={folder.uuid} className="sidebar-folder">
      <button type="button" className="sidebar-folder-row" onClick={() => toggle(folder.uuid)} aria-expanded={open}>
        {open ? <ChevronDown /> : <ChevronRight />}{open ? <FolderOpen /> : <Folder />}<span>{folder.name}</span><small>{folder.children.length + directDefinitions.length}</small>
      </button>
      {open && <ul>{folder.children.map(folderItem)}{directDefinitions.map(definitionItem)}</ul>}
    </li>
  }
  const unfiled = definitionsByFolder.get(null) ?? []

  return <section className="sidebar-project-tree" aria-label={shellT('nav.objects')}>
    <header><span>{shellT('nav.objects')}</span><button type="button" title={shellT('nav.openObjectWorkspace')} aria-label={shellT('nav.openObjectWorkspace')} onClick={() => onNavigate(`/projects/${encodeURIComponent(projectUuid)}/development`)}><PanelRightOpen /></button></header>
    {activeDefinitions.length > 0 && <label className="sidebar-tree-search"><Search /><span className="sr-only">{shellT('nav.searchObjects')}</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={shellT('nav.searchObjects')} /></label>}
    <div className="sidebar-tree-scroll">
      {loading ? <p className="sidebar-tree-state">{shellT('common.loading')}</p> : failed ? <button className="sidebar-tree-retry" type="button" onClick={onRetry}><RefreshCw />{shellT('common.retry')}</button> : tree.length === 0 && unfiled.length === 0 ? <p className="sidebar-tree-state"><FileCode2 />{shellT('nav.noObjects')}</p> : query.trim() ? matches.length > 0 ? <ul className="sidebar-tree sidebar-tree--results">{matches.map(definitionItem)}</ul> : <p className="sidebar-tree-state">{shellT('nav.noMatchingObjects')}</p> : <ul className="sidebar-tree">{tree.map(folderItem)}{unfiled.length > 0 && <li className="sidebar-folder"><div className="sidebar-folder-row sidebar-folder-row--static"><span className="sidebar-folder-spacer" /><Folder /><span>{shellT('nav.unfiled')}</span><small>{unfiled.length}</small></div><ul>{unfiled.map(definitionItem)}</ul></li>}</ul>}
    </div>
  </section>
}
