import { createContext, useContext, type PropsWithChildren } from 'react'

export interface ProjectAccess { roles: string[]; permissions: string[] }
const ProjectAccessContext = createContext<ProjectAccess | null>(null)

export function ProjectAccessProvider({ value, children }: PropsWithChildren<{ value: ProjectAccess | null }>) {
  return <ProjectAccessContext.Provider value={value}>{children}</ProjectAccessContext.Provider>
}
export function useProjectAccess() {
  const access = useContext(ProjectAccessContext)
  return {
    access,
    can: (permission: string) => access?.permissions.includes(permission) ?? false,
    operatorOnly: access?.roles.includes('OPERASYON') === true && !access.permissions.includes('TANIM_DUZENLE'),
  }
}
