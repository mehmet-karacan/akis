import { ChevronDown, ChevronRight, ExternalLink, FileCode2, Folder, FolderOpen, FolderPlus, MoreHorizontal, PanelRightOpen, Play, RefreshCw, Search, WandSparkles, X } from 'lucide-react'
import { useEffect, useMemo, useState, type MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { DefinitionTypeIcon } from '../features/definitions/DefinitionTypeIcon'
import { buildFolderTree, type FolderTreeNode } from '../features/definitions/ProjectExplorer'
import { definitionTypeKey, useDefinitionsI18n } from '../features/definitions/i18n'
import type { Definition, Folder as ProjectFolder } from '../features/definitions/types'
import { definitionsApi } from '../features/definitions/api'

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
  const [menu, setMenu] = useState<{ definition: Definition; x: number; y: number } | null>(null)
  const [folderMenu, setFolderMenu] = useState<{ folder: ProjectFolder; x: number; y: number } | null>(null)
  const [compilingUuid, setCompilingUuid] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)
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

  useEffect(() => {
    if (!menu && !folderMenu) return
    const close = () => { setMenu(null); setFolderMenu(null) }
    const closeWithEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') close() }
    window.addEventListener('click', close)
    window.addEventListener('scroll', close, true)
    window.addEventListener('keydown', closeWithEscape)
    return () => { window.removeEventListener('click', close); window.removeEventListener('scroll', close, true); window.removeEventListener('keydown', closeWithEscape) }
  }, [folderMenu, menu])

  const toggle = (uuid: string) => setExpanded((current) => {
    const next = new Set(current)
    if (next.has(uuid)) next.delete(uuid); else next.add(uuid)
    return next
  })
  const openDefinition = (uuid: string) => onNavigate(`/projects/${encodeURIComponent(projectUuid)}/development?definition=${encodeURIComponent(uuid)}`)
  const openMenu = (definition: Definition, event: MouseEvent) => {
    event.preventDefault(); event.stopPropagation()
    const rect = event.currentTarget.getBoundingClientRect()
    const fromContextMenu = event.type === 'contextmenu'
    setFolderMenu(null); setMenu({ definition, x: fromContextMenu ? event.clientX : rect.right - 4, y: fromContextMenu ? event.clientY : rect.bottom + 3 })
  }
  const openFolderMenu = (folder: ProjectFolder, event: MouseEvent) => {
    event.preventDefault(); event.stopPropagation()
    const rect = event.currentTarget.getBoundingClientRect()
    const fromContextMenu = event.type === 'contextmenu'
    setMenu(null); setFolderMenu({ folder, x: fromContextMenu ? event.clientX : rect.right - 4, y: fromContextMenu ? event.clientY : rect.bottom + 3 })
  }
  const compileScenario = async (definition: Definition) => {
    setMenu(null); setNotice(null); setCompilingUuid(definition.uuid)
    try {
      const versions = await definitionsApi.listVersions(projectUuid, definition.uuid)
      const latest = versions[0]
      if (!latest) { setNotice({ tone: 'error', text: shellT('nav.scenarioNeedsVersion') }); return }
      const scenario = await definitionsApi.compileScenario(projectUuid, definition.uuid, latest.uuid)
      setNotice({ tone: 'success', text: shellT('nav.scenarioCreated', { version: scenario.scenarioVersion }) })
    } catch (error) { setNotice({ tone: 'error', text: error instanceof Error ? error.message : shellT('nav.actionFailed') }) }
    finally { setCompilingUuid(null) }
  }
  const executable = (definition: Definition) => ['MAPPING', 'PACKAGE', 'PROCEDURE', 'LOAD_PLAN'].includes(definition.type)
  const definitionItem = (definition: Definition) => <li key={definition.uuid}>
    <div className={`sidebar-object-row ${selectedUuid === definition.uuid ? 'is-selected' : ''}`} onContextMenu={(event) => openMenu(definition, event)}>
    <button type="button" className="sidebar-object" onClick={() => openDefinition(definition.uuid)} title={`${definition.name} · ${t(definitionTypeKey[definition.type])}`}>
      <span className={`sidebar-object-icon sidebar-object-icon--${definition.type.toLowerCase().replaceAll('_', '-')}`}><DefinitionTypeIcon type={definition.type} /></span>
      <span><strong>{definition.name}</strong><small>{t(definitionTypeKey[definition.type])}</small></span>
    </button>
    <button type="button" className="sidebar-object-menu-button" aria-label={shellT('nav.objectActions', { name: definition.name })} aria-haspopup="menu" aria-expanded={menu?.definition.uuid === definition.uuid} onClick={(event) => openMenu(definition, event)}><MoreHorizontal /></button>
    </div>
  </li>
  const folderItem = (folder: FolderTreeNode) => {
    const open = expanded.has(folder.uuid)
    const directDefinitions = definitionsByFolder.get(folder.uuid) ?? []
    return <li key={folder.uuid} className="sidebar-folder">
      <div className="sidebar-folder-action-row" onContextMenu={(event) => openFolderMenu(folder, event)}>
        <button type="button" className="sidebar-folder-row" onClick={() => toggle(folder.uuid)} aria-expanded={open}>
          {open ? <ChevronDown /> : <ChevronRight />}{open ? <FolderOpen /> : <Folder />}<span>{folder.name}</span><small>{folder.children.length + directDefinitions.length}</small>
        </button>
        <button type="button" className="sidebar-object-menu-button" aria-label={shellT('nav.objectActions', { name: folder.name })} aria-haspopup="menu" aria-expanded={folderMenu?.folder.uuid === folder.uuid} onClick={(event) => openFolderMenu(folder, event)}><MoreHorizontal /></button>
      </div>
      {open && <ul>{folder.children.map(folderItem)}{directDefinitions.map(definitionItem)}</ul>}
    </li>
  }
  const unfiled = definitionsByFolder.get(null) ?? []

  return <section className="sidebar-project-tree" aria-label={shellT('nav.objects')}>
    <header><span>{shellT('nav.objects')}</span><button type="button" title={shellT('nav.openObjectWorkspace')} aria-label={shellT('nav.openObjectWorkspace')} onClick={() => onNavigate(`/projects/${encodeURIComponent(projectUuid)}/development`)}><PanelRightOpen /></button></header>
    {activeDefinitions.length > 0 && <label className="sidebar-tree-search"><Search /><span className="sr-only">{shellT('nav.searchObjects')}</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={shellT('nav.searchObjects')} /></label>}
    {notice && <div className={`sidebar-tree-notice sidebar-tree-notice--${notice.tone}`} role={notice.tone === 'error' ? 'alert' : 'status'}><span>{notice.text}</span><button type="button" aria-label={shellT('common.close')} onClick={() => setNotice(null)}><X /></button></div>}
    <div className="sidebar-tree-scroll">
      {loading ? <p className="sidebar-tree-state">{shellT('common.loading')}</p> : failed ? <button className="sidebar-tree-retry" type="button" onClick={onRetry}><RefreshCw />{shellT('common.retry')}</button> : tree.length === 0 && unfiled.length === 0 ? <p className="sidebar-tree-state"><FileCode2 />{shellT('nav.noObjects')}</p> : query.trim() ? matches.length > 0 ? <ul className="sidebar-tree sidebar-tree--results">{matches.map(definitionItem)}</ul> : <p className="sidebar-tree-state">{shellT('nav.noMatchingObjects')}</p> : <ul className="sidebar-tree">{tree.map(folderItem)}{unfiled.length > 0 && <li className="sidebar-folder"><div className="sidebar-folder-row sidebar-folder-row--static"><span className="sidebar-folder-spacer" /><Folder /><span>{shellT('nav.unfiled')}</span><small>{unfiled.length}</small></div><ul>{unfiled.map(definitionItem)}</ul></li>}</ul>}
    </div>
    {menu && <div className="sidebar-object-context-menu" role="menu" style={{ left: Math.min(menu.x, window.innerWidth - 220), top: Math.min(menu.y, window.innerHeight - 190) }} onClick={(event) => event.stopPropagation()}>
      <header><DefinitionTypeIcon type={menu.definition.type} /><span><strong>{menu.definition.name}</strong><small>{menu.definition.code}</small></span></header>
      <button type="button" role="menuitem" onClick={() => { setMenu(null); openDefinition(menu.definition.uuid) }}><ExternalLink />{shellT('nav.openObject')}</button>
      {executable(menu.definition) && <button type="button" role="menuitem" disabled={compilingUuid === menu.definition.uuid} onClick={() => void compileScenario(menu.definition)}><WandSparkles />{compilingUuid === menu.definition.uuid ? shellT('nav.creatingScenario') : shellT('nav.createScenario')}</button>}
      {executable(menu.definition) && <button type="button" role="menuitem" onClick={() => { const uuid = menu.definition.uuid; setMenu(null); onNavigate(`/projects/${encodeURIComponent(projectUuid)}/operations?definition=${encodeURIComponent(uuid)}&start=1`) }}><Play />{shellT('nav.runObject')}</button>}
    </div>}
    {folderMenu && <div className="sidebar-object-context-menu" role="menu" style={{ left: Math.min(folderMenu.x, window.innerWidth - 220), top: Math.min(folderMenu.y, window.innerHeight - 120) }} onClick={(event) => event.stopPropagation()}>
      <header><Folder /><span><strong>{folderMenu.folder.name}</strong><small>{folderMenu.folder.code}</small></span></header>
      <button type="button" role="menuitem" onClick={() => { const uuid = folderMenu.folder.uuid; setFolderMenu(null); onNavigate(`/projects/${encodeURIComponent(projectUuid)}/development?createFolder=${encodeURIComponent(uuid)}`) }}><FolderPlus />{shellT('nav.createSubfolder')}</button>
    </div>}
  </section>
}
