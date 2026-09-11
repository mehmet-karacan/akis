import { lazy, Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, Route, Routes, useParams } from 'react-router-dom'
import { useAuth } from '../core/auth/AuthContext'
import { LoginPage } from '../features/auth/LoginPage'
import { AppShell } from './AppShell'

const BundleImportPage = lazy(() => import('../features/bundles').then((module) => ({ default: module.BundleImportPage })))
const DefinitionsWorkspace = lazy(() => import('../features/definitions').then((module) => ({ default: module.DefinitionsWorkspace })))
const RunDetailPage = lazy(() => import('../features/execution').then((module) => ({ default: module.RunDetailPage })))
const RunsPage = lazy(() => import('../features/execution').then((module) => ({ default: module.RunsPage })))
const IdentityUsersPage = lazy(() => import('../features/operations').then((module) => ({ default: module.IdentityUsersPage })))
const MembershipsPage = lazy(() => import('../features/operations').then((module) => ({ default: module.MembershipsPage })))
const PublicationDetailPage = lazy(() => import('../features/operations').then((module) => ({ default: module.PublicationDetailPage })))
const PublicationsPage = lazy(() => import('../features/operations').then((module) => ({ default: module.PublicationsPage })))
const ProjectsPage = lazy(() => import('../features/projects/ProjectsPage').then((module) => ({ default: module.ProjectsPage })))
const ProjectOverviewPage = lazy(() => import('../features/projects/ProjectOverviewPage').then((module) => ({ default: module.ProjectOverviewPage })))
const TopologyPage = lazy(() => import('../features/topology').then((module) => ({ default: module.TopologyPage })))

function ProtectedShell() {
  const { username } = useAuth()
  return username ? <AppShell /> : <Navigate to="/login" replace />
}

function DefinitionsRoute() {
  const { projectUuid = '' } = useParams()
  return <DefinitionsWorkspace projectUuid={projectUuid} />
}

function RouteLoading() {
  const { t } = useTranslation()
  return <div className="route-loading" role="status"><span />{t('common.loading')}</div>
}

export function App() {
  const { username } = useAuth()
  return (
    <Suspense fallback={<RouteLoading />}><Routes>
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
        <Route path="/projects/:projectUuid/runs" element={<RunsPage />} />
        <Route path="/projects/:projectUuid/runs/:runUuid" element={<RunDetailPage />} />
        <Route path="/projects/:projectUuid/team" element={<MembershipsPage />} />
        <Route path="/identity/users" element={<IdentityUsersPage />} />
      </Route>
      <Route path="*" element={<Navigate to={username ? '/projects' : '/login'} replace />} />
    </Routes></Suspense>
  )
}
