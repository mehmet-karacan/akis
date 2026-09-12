import { lazy, Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom'
import { useAuth } from '../core/auth/AuthContext'
import { useProjectAccess } from '../core/auth/ProjectAccessContext'
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
  const { projectUuid = '', definitionUuid } = useParams()
  return <DefinitionsWorkspace projectUuid={projectUuid} routeDefinitionUuid={definitionUuid} />
}

export function LegacyTopologyRedirect() {
  const { projectUuid = '' } = useParams()
  return <Navigate to={`/projects/${projectUuid}/connections`} replace />
}

export function LegacyDefinitionsRedirect() {
  const { projectUuid = '' } = useParams(); const location = useLocation(); const params = new URLSearchParams(location.search); const definitionUuid = params.get('definition'); params.delete('definition')
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return <Navigate to={definitionUuid ? `/projects/${projectUuid}/development/definitions/${encodeURIComponent(definitionUuid)}${suffix}` : `/projects/${projectUuid}/development${suffix}`} replace />
}

export function LegacyRunsRedirect({ detail = false }: { detail?: boolean }) {
  const { projectUuid = '', runUuid = '' } = useParams(); const location = useLocation()
  return <Navigate to={`/projects/${projectUuid}/operations${detail ? `/runs/${runUuid}` : ''}${location.search}`} replace />
}

function RouteLoading() {
  const { t } = useTranslation()
  return <div className="route-loading" role="status"><span />{t('common.loading')}</div>
}

function ProjectPermissionRoute({ permission, fallback, children }: { permission: string; fallback: string; children: React.ReactNode }) {
  const { access, can } = useProjectAccess()
  const { projectUuid = '' } = useParams()
  if (!access) return <RouteLoading />
  return can(permission) ? children : <Navigate to={fallback.replace(':projectUuid', encodeURIComponent(projectUuid))} replace />
}

export function App() {
  const { username } = useAuth()
  return (
    <Suspense fallback={<RouteLoading />}><Routes>
      <Route path="/login" element={username ? <Navigate to="/projects" replace /> : <LoginPage />} />
      <Route element={<ProtectedShell />}>
        <Route path="/projects" element={<ProjectsPage />} />
        <Route path="/project-bundles/import" element={<BundleImportPage />} />
        <Route path="/projects/import" element={<BundleImportPage />} />
        <Route path="/projects/:projectUuid" element={<ProjectOverviewPage />} />
        <Route path="/projects/:projectUuid/topology" element={<LegacyTopologyRedirect />} />
        <Route path="/projects/:projectUuid/models" element={<ModelsPage />} />
        <Route path="/projects/:projectUuid/models/:modelUuid/import" element={<ProjectPermissionRoute permission="KATALOG_KESFET" fallback="/projects/:projectUuid/models"><MetadataImportPage /></ProjectPermissionRoute>} />
        <Route path="/projects/:projectUuid/models/:modelUuid" element={<ModelDetailPage />} />
        <Route path="/projects/:projectUuid/definitions" element={<LegacyDefinitionsRedirect />} />
        <Route path="/projects/:projectUuid/development" element={<DefinitionsRoute />} />
        <Route path="/projects/:projectUuid/development/definitions/:definitionUuid" element={<DefinitionsRoute />} />
        <Route path="/projects/:projectUuid/publications" element={<PublicationsPage />} />
        <Route path="/projects/:projectUuid/publications/:publicationUuid" element={<PublicationDetailPage />} />
        <Route path="/projects/:projectUuid/runs" element={<LegacyRunsRedirect />} />
        <Route path="/projects/:projectUuid/operations" element={<RunsPage />} />
        <Route path="/projects/:projectUuid/runs/:runUuid" element={<LegacyRunsRedirect detail />} />
        <Route path="/projects/:projectUuid/operations/runs/:runUuid" element={<RunDetailPage />} />
        <Route path="/projects/:projectUuid/team" element={<MembershipsPage />} />
        <Route path="/projects/:projectUuid/connections" element={<ConnectionsPage />} />
        <Route path="/projects/:projectUuid/connections/new" element={<ProjectPermissionRoute permission="BAGLANTI_YONET" fallback="/projects/:projectUuid/connections"><ConnectionCreatePage /></ProjectPermissionRoute>} />
        <Route path="/projects/:projectUuid/connections/:connectionUuid" element={<ConnectionDetailPage />} />
        <Route path="/projects/:projectUuid/connections/:connectionUuid/physical-schemas" element={<PhysicalSchemasPage />} />
        <Route path="/projects/:projectUuid/logical-schemas" element={<LogicalSchemasPage />} />
        <Route path="/projects/:projectUuid/logical-schemas/:logicalSchemaUuid" element={<LogicalSchemaDetailPage />} />
        <Route path="/projects/:projectUuid/environments" element={<EnvironmentsPage />} />
        <Route path="/projects/:projectUuid/environments/:environmentUuid" element={<EnvironmentDetailPage />} />
        <Route path="/projects/:projectUuid/schema-bindings" element={<SchemaBindingsPage />} />
        <Route path="/identity/users" element={<IdentityUsersPage />} />
      </Route>
      <Route path="*" element={<Navigate to={username ? '/projects' : '/login'} replace />} />
    </Routes></Suspense>
  )
}
