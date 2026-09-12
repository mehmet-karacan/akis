import { createContext, useContext, type PropsWithChildren } from 'react'
import { useParams } from 'react-router-dom'
import { getRememberedProject } from './projectPreference'

const CurrentProjectContext = createContext<string | null>(null)

export function CurrentProjectProvider({ projectUuid, children }: PropsWithChildren<{ projectUuid: string }>) {
  return <CurrentProjectContext.Provider value={projectUuid}>{children}</CurrentProjectContext.Provider>
}

export function useCurrentProjectUuid() {
  const contextProjectUuid = useContext(CurrentProjectContext)
  const { projectUuid: routeProjectUuid } = useParams()
  const projectUuid = contextProjectUuid ?? routeProjectUuid ?? getRememberedProject()
  if (!projectUuid) throw new Error('A project must be selected before opening the workspace')
  return projectUuid
}

export function projectRoute(path = '') {
  return `/project${path}`
}
