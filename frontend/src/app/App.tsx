import { Navigate, Route, Routes, useParams } from 'react-router-dom'
import { useAuth } from '../core/auth/AuthContext'
import { LoginPage } from '../features/auth/LoginPage'
import { BundleImportPage } from '../features/bundles'
import { DefinitionsWorkspace } from '../features/definitions'
import { IdentityUsersPage, MembershipsPage, PublicationDetailPage, PublicationsPage } from '../features/operations'
import { ProjectsPage } from '../features/projects/ProjectsPage'
import { TopologyPage } from '../features/topology'
import { AppShell } from './AppShell'
import { ProjectOverviewPage } from '../features/projects/ProjectOverviewPage'

function ProtectedShell() {
  const { username } = useAuth()
  return username ? <AppShell /> : <Navigate to="/login" replace />
}

function DefinitionsRoute() {
  const { projectUuid = '' } = useParams()
  return <DefinitionsWorkspace projectUuid={projectUuid} />
}

export function App() {
  const { username } = useAuth()
  return (
    <Routes>
      <Route path="/login" element={username ? <Navigate to="/projects" replace /> : <LoginPage />} />
      <Route element={<ProtectedShell />}>
        <Route path="/projects" element={<ProjectsPage />} />
        <Route path="/project-bundles/import" element={<BundleImportPage />} />
        <Route path="/projects/:projectUuid" element={<ProjectOverviewPage />} />
        <Route path="/projects/:projectUuid/topology" element={<TopologyPage />} />
        <Route path="/projects/:projectUuid/models" element={<TopologyPage initialTab="catalog" />} />
        <Route path="/projects/:projectUuid/definitions" element={<DefinitionsRoute />} />
        <Route path="/projects/:projectUuid/publications" element={<PublicationsPage />} />
        <Route path="/projects/:projectUuid/publications/:publicationUuid" element={<PublicationDetailPage />} />
        <Route path="/projects/:projectUuid/team" element={<MembershipsPage />} />
        <Route path="/identity/users" element={<IdentityUsersPage />} />
      </Route>
      <Route path="*" element={<Navigate to={username ? '/projects' : '/login'} replace />} />
    </Routes>
  )
}
