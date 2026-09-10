import { Route, Routes } from 'react-router-dom'
import { IdentityUsersPage } from './IdentityUsersPage'
import { MembershipsPage } from './MembershipsPage'
import { PublicationDetailPage } from './PublicationDetailPage'
import { PublicationsPage } from './PublicationsPage'

/** Mount once inside the application's BrowserRouter. */
export function OperationsRoutes() {
  return (
    <Routes>
      <Route path="/projects/:projectUuid/publications" element={<PublicationsPage />} />
      <Route path="/projects/:projectUuid/publications/:publicationUuid" element={<PublicationDetailPage />} />
      <Route path="/projects/:projectUuid/team" element={<MembershipsPage />} />
      <Route path="/identity/users" element={<IdentityUsersPage />} />
    </Routes>
  )
}

