import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

/** ODI-style document tabs: every opened object (definition, model, …) keeps a tab until the user closes it. */
export interface DocumentTab {
  path: string
  title: string
  /** Icon kind used by the tab strip to pick the colored type badge. */
  kind: string
  subtitle?: string
}

interface DocumentTabsValue {
  tabs: DocumentTab[]
  maximized: boolean
  open(tab: DocumentTab): void
  close(path: string): string | null
  closeOthers(path: string): void
  setMaximized(value: boolean): void
}

const DocumentTabsContext = createContext<DocumentTabsValue | null>(null)
const storageKey = (projectUuid: string) => `akis.documentTabs:${projectUuid}`

export function DocumentTabsProvider({ projectUuid, children }: { projectUuid: string; children: ReactNode }) {
  const [tabs, setTabs] = useState<DocumentTab[]>(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey(projectUuid)) ?? '[]') as DocumentTab[] } catch { return [] }
  })
  const [maximized, setMaximized] = useState(false)
  useEffect(() => { try { sessionStorage.setItem(storageKey(projectUuid), JSON.stringify(tabs)) } catch { /* Tabs are a convenience; storage is optional. */ } }, [projectUuid, tabs])
  useEffect(() => { try { localStorage.setItem('akis.workbench.maximized', maximized ? '1' : '0') } catch { /* optional */ } }, [maximized])
  const open = useCallback((tab: DocumentTab) => setTabs((current) => {
    const existing = current.find((item) => item.path === tab.path)
    if (existing) return existing.title === tab.title && existing.subtitle === tab.subtitle && existing.kind === tab.kind ? current : current.map((item) => item.path === tab.path ? { ...item, ...tab } : item)
    return [...current, tab]
  }), [])
  /** Returns the path that should become active when the closed tab was the active one (its neighbour), or null when nothing is left. */
  const close = useCallback((path: string) => {
    let next: string | null = null
    setTabs((current) => {
      const index = current.findIndex((item) => item.path === path)
      if (index < 0) return current
      const remaining = current.filter((item) => item.path !== path)
      next = remaining[Math.min(index, remaining.length - 1)]?.path ?? null
      return remaining
    })
    return next
  }, [])
  const closeOthers = useCallback((path: string) => setTabs((current) => current.filter((item) => item.path === path)), [])
  const value = useMemo(() => ({ tabs, maximized, open, close, closeOthers, setMaximized }), [tabs, maximized, open, close, closeOthers])
  return <DocumentTabsContext.Provider value={value}>{children}</DocumentTabsContext.Provider>
}

export function useDocumentTabs() {
  const value = useContext(DocumentTabsContext)
  if (!value) throw new Error('useDocumentTabs requires DocumentTabsProvider')
  return value
}

/** Pages call this once their record is known; the tab appears (or its title refreshes) without the page owning tab state. */
export function useDocumentTab(tab: DocumentTab | null) {
  const context = useContext(DocumentTabsContext)
  const open = context?.open
  const path = tab?.path, title = tab?.title, subtitle = tab?.subtitle, kind = tab?.kind
  useEffect(() => { if (path && title && kind && open) open({ path, title, subtitle, kind }) }, [open, path, title, subtitle, kind])
}
