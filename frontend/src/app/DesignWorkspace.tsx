import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { definitionsApi } from '../features/definitions/api'
import type { Definition, Folder } from '../features/definitions/types'
import { ProjectSidebarTree } from './ProjectSidebarTree'
import '../features/definitions/definitions.css'
import '../features/definitions/workbench-standard.css'

export function DesignWorkspace({ projectUuid, onNavigate, children, explorerOnly = false }: { projectUuid: string; onNavigate(path: string): void; children?: ReactNode; explorerOnly?: boolean }) {
  const location = useLocation()
  const [folders, setFolders] = useState<Folder[]>([])
  const [definitions, setDefinitions] = useState<Definition[]>([])
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [revision, setRevision] = useState(0)
  const retry = useCallback(() => setRevision((value) => value + 1), [])
  useEffect(() => {
    window.addEventListener('akis:definitions-changed', retry)
    return () => window.removeEventListener('akis:definitions-changed', retry)
  }, [retry])
  useEffect(() => {
    let active = true
    setLoading(true); setFailed(false)
    void Promise.all([definitionsApi.listFolders(projectUuid), definitionsApi.listDefinitions(projectUuid)])
      .then(([nextFolders, nextDefinitions]) => { if (active) { setFolders(nextFolders); setDefinitions(nextDefinitions) } })
      .catch(() => { if (active) setFailed(true) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [projectUuid, revision])
  const selectedUuid = location.pathname.match(/\/definitions\/([^/]+)/)?.[1] ?? null
  if (explorerOnly) return <section className="shell-project-explorer"><ProjectSidebarTree projectUuid={projectUuid} folders={folders} definitions={definitions} selectedUuid={selectedUuid} loading={loading} failed={failed} onNavigate={onNavigate} onRetry={retry} /></section>
  return <div className="design-workspace definitions-workspace"><aside className="design-explorer definition-object-explorer"><ProjectSidebarTree projectUuid={projectUuid} folders={folders} definitions={definitions} selectedUuid={selectedUuid} loading={loading} failed={failed} onNavigate={onNavigate} onRetry={retry} /></aside><div className="design-content">{children}</div></div>
}
