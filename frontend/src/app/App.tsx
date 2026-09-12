import { lazy, Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom'
import { useAuth } from '../core/auth/AuthContext'
import { useProjectAccess } from '../core/auth/ProjectAccessContext'
import { projectRoute, useCurrentProjectUuid } from '../features/projects/CurrentProjectContext'
import { rememberProject } from '../features/projects/projectPreference'
import { LoginPage } from '../features/auth/LoginPage'
import { AppShell } from './AppShell'

const BundleImportPage = lazy(() => import('../features/bundles').then((module) => ({ default: module.BundleImportPage })))
const DefinitionsWorkspace = lazy(() => import('../features/definitions').then((module) => ({ default: module.DefinitionsWorkspace })))
const ConnectionsPage = lazy(() => import('../features/connections').then((module) => ({ default: module.ConnectionsPage })))
const ConnectionCreatePage = lazy(() => import('../features/connections').then((module) => ({ default: module.ConnectionCreatePage })))
const ConnectionDetailPage = lazy(() => import('../features/connections').then((module) => ({ default: module.ConnectionDetailPage })))
const PhysicalSchemasPage = lazy(() => import('../features/connections').then((module) => ({ default: module.PhysicalSchemasPage })))
const LogicalSchemasPage = lazy(() => import('../features/schemas').then((module) => ({ default: module.LogicalSchemasPage })))
const SchemaBindingsPage = lazy(() => import('../features/schemas').then((module) => ({ default: module.SchemaBindingsPage })))
const LogicalSchemaDetailPage = lazy(() => import('../features/schemas').then((module) => ({ default: module.LogicalSchemaDetailPage })))
const EnvironmentDetailPage = lazy(() => import('../features/schemas').then((module) => ({ default: module.EnvironmentDetailPage })))
const EnvironmentsPage = lazy(() => import('../features/environments').then((module) => ({ default: module.EnvironmentsPage })))
const RunDetailPage = lazy(() => import('../features/execution').then((module) => ({ default: module.RunDetailPage })))
const RunsPage = lazy(() => import('../features/execution').then((module) => ({ default: module.RunsPage })))
const IdentityUsersPage = lazy(() => import('../features/operations').then((module) => ({ default: module.IdentityUsersPage })))
const MembershipsPage = lazy(() => import('../features/operations').then((module) => ({ default: module.MembershipsPage })))
const PublicationDetailPage = lazy(() => import('../features/operations').then((module) => ({ default: module.PublicationDetailPage })))
const PublicationsPage = lazy(() => import('../features/operations').then((module) => ({ default: module.PublicationsPage })))
const ProjectsPage = lazy(() => import('../features/projects/ProjectsPage').then((module) => ({ default: module.ProjectsPage })))
const ProjectOverviewPage = lazy(() => import('../features/projects/ProjectOverviewPage').then((module) => ({ default: module.ProjectOverviewPage })))
const ModelsPage = lazy(() => import('../features/models').then((module) => ({ default: module.ModelsPage })))
const ModelDetailPage = lazy(() => import('../features/models').then((module) => ({ default: module.ModelDetailPage })))
const MetadataImportPage = lazy(() => import('../features/models').then((module) => ({ default: module.MetadataImportPage })))

function ProtectedShell() {
  const { username } = useAuth()
  return username ? <AppShell /> : <Navigate to="/login" replace />
}

function DefinitionsRoute() {
  const { definitionUuid } = useParams(); const projectUuid = useCurrentProjectUuid()
  return <DefinitionsWorkspace projectUuid={projectUuid} routeDefinitionUuid={definitionUuid} />
}

export function LegacyTopologyRedirect() {
  return <Navigate to={projectRoute('/connections')} replace />
}

export function LegacyDefinitionsRedirect() {
  const location = useLocation(); const params = new URLSearchParams(location.search); const definitionUuid = params.get('definition'); params.delete('definition')
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return <Navigate to={definitionUuid ? `${projectRoute('/objects/definitions')}/${encodeURIComponent(definitionUuid)}${suffix}` : `${projectRoute('/objects')}${suffix}`} replace />
}

export function LegacyRunsRedirect({ detail = false }: { detail?: boolean }) {
  const { runUuid = '' } = useParams(); const location = useLocation()
  return <Navigate to={`${projectRoute('/operations')}${detail ? `/runs/${runUuid}` : ''}${location.search}`} replace />
}

function LegacyProjectRedirect() {
  const { projectUuid = '', '*': remainder = '' } = useParams()
  if (projectUuid) rememberProject(projectUuid)
  const normalized = remainder.replace(/^development(?=\/|$)/, 'objects')
  return <Navigate to={projectRoute(normalized ? `/${normalized}` : '')} replace />
}

function RouteLoading() {
  const { t } = useTranslation()
  return <div className="route-loading" role="status"><span />{t('common.loading')}</div>
}

function ProjectPermissionRoute({ permission, fallback, children }: { permission: string; fallback: string; children: React.ReactNode }) {
  const { access, can } = useProjectAccess()
  if (!access) return <RouteLoading />
  return can(permission) ? children : <Navigate to={fallback} replace />
}

export function App() {
  const { username } = useAuth()
  return (
    <Suspense fallback={<RouteLoading />}><Routes>
      <Route path="/login" element={username ? <Navigate to="/project/select" replace /> : <LoginPage />} />
      <Route path="/project/select" element={username ? <ProjectsPage /> : <Navigate to="/login" replace />} />
      <Route path="/projects" element={<Navigate to="/project/select" replace />} />
      <Route path="/projects/import" element={<Navigate to="/project/import" replace />} />
      <Route path="/projects/:projectUuid/*" element={<LegacyProjectRedirect />} />
      <Route element={<ProtectedShell />}>
        <Route path="/project-bundles/import" element={<BundleImportPage />} />
        <Route path="/project/import" element={<BundleImportPage />} />
        <Route path="/project" element={<ProjectOverviewPage />} />
        <Route path="/project/topology" element={<LegacyTopologyRedirect />} />
        <Route path="/project/models" element={<ModelsPage />} />
        <Route path="/project/models/:modelUuid/import" element={<ProjectPermissionRoute permission="KATALOG_KESFET" fallback="/project/models"><MetadataImportPage /></ProjectPermissionRoute>} />
        <Route path="/project/models/:modelUuid" element={<ModelDetailPage />} />
        <Route path="/project/definitions" element={<LegacyDefinitionsRedirect />} />
        <Route path="/project/objects" element={<DefinitionsRoute />} />
        <Route path="/project/objects/definitions/:definitionUuid" element={<DefinitionsRoute />} />
        <Route path="/project/publications" element={<PublicationsPage />} />
        <Route path="/project/publications/:publicationUuid" element={<PublicationDetailPage />} />
        <Route path="/project/runs" element={<LegacyRunsRedirect />} />
        <Route path="/project/operations" element={<RunsPage />} />
        <Route path="/project/runs/:runUuid" element={<LegacyRunsRedirect detail />} />
        <Route path="/project/operations/runs/:runUuid" element={<RunDetailPage />} />
        <Route path="/project/team" element={<MembershipsPage />} />
        <Route path="/project/connections" element={<ConnectionsPage />} />
        <Route path="/project/connections/new" element={<ProjectPermissionRoute permission="BAGLANTI_YONET" fallback="/project/connections"><ConnectionCreatePage /></ProjectPermissionRoute>} />
        <Route path="/project/connections/:connectionUuid" element={<ConnectionDetailPage />} />
        <Route path="/project/connections/:connectionUuid/physical-schemas" element={<PhysicalSchemasPage />} />
        <Route path="/project/logical-schemas" element={<LogicalSchemasPage />} />
        <Route path="/project/logical-schemas/:logicalSchemaUuid" element={<LogicalSchemaDetailPage />} />
        <Route path="/project/environments" element={<EnvironmentsPage />} />
        <Route path="/project/environments/:environmentUuid" element={<EnvironmentDetailPage />} />
        <Route path="/project/schema-bindings" element={<SchemaBindingsPage />} />
        <Route path="/identity/users" element={<IdentityUsersPage />} />
      </Route>
      <Route path="*" element={<Navigate to={username ? '/project/select' : '/login'} replace />} />
    </Routes></Suspense>
  )
}
