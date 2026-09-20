import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../../src/core/i18n'
import '../../src/styles.css'
import '../../src/features/connections/connections.css'
import '../../src/core/theme/ant-design.css'
import { ThemeProvider } from '../../src/core/theme/ThemeContext'
import { AntDesignProvider } from '../../src/core/theme/AntDesignProvider'
import { ProjectAccessProvider } from '../../src/core/auth/ProjectAccessContext'
import { CurrentProjectProvider } from '../../src/features/projects/CurrentProjectContext'
import { ConnectionCards } from '../../src/features/connections/ConnectionCards'
import { ConnectionsTable } from '../../src/features/connections/ConnectionsTable'
import type { ConnectionCatalogItem } from '../../src/features/connections/catalog'
import { ViewToggle, type CollectionView } from '../../src/core/ui/ViewToggle'
import type { RecordAudit } from '../../src/core/ui/useRecordAudit'

// Deliberately uneven synthetic values expose cross-card track misalignment.
const items: ConnectionCatalogItem[] = ['LONG', 'SHORT'].map((code, index) => ({
  connection: { uuid: code, code, name: code, databaseType: 'ORACLE', status: 'ACTIVE', version: 1,
    description: index === 0 ? 'A long connection description that must wrap naturally without changing the alignment of actions or record information.' : 'Short description' },
  physicalSchemaCount: index + 2, logicalSchemaCount: index + 1,
  displayedVersion: { uuid: `version-${code}`, versionNumber: 1, mode: 'JDBC', host: 'database.example.invalid', port: 1521,
    serviceName: index === 0 ? 'LONG_SERVICE_NAME_THAT_WRAPS_ACROSS_MULTIPLE_LINES' : 'DB', username: 'FIXTURE', policyVersion: 2,
    lifecycleStatus: 'ACTIVE', lifecycleVersion: 1, runtimeCapability: 'EXECUTABLE',
    createdAt: '2026-09-12T10:00:00Z', testedAt: index === 0 ? '2026-09-14T10:15:00Z' : '2026-09-16T12:49:00Z' },
}))
const records: Record<string, RecordAudit> = { LONG: { uuid: 'LONG', createdBy: 'Fixture Creator With A Long Display Name', createdAt: '2026-09-12T10:00:00Z', updatedBy: null, updatedAt: null } }
const labels = { provider: 'Provider', connection: 'Connection', endpoint: 'Endpoint', physical: 'Physical schemas', logical: 'Logical schemas', status: 'Status', lastTest: 'Last Test', actions: 'Actions', open: 'Open', ready: 'Ready', testRequired: 'Test Required', host: 'Host', port: 'Port', service: 'Service', username: 'Username' }
function Harness() {
  const [view, setView] = useState<CollectionView>('card')
  const [opened, setOpened] = useState('')
  return <main className="connections-page" style={{ padding: 16 }}>
    <h1>Connections</h1><ViewToggle value={view} onChange={setView} />
    <output aria-label="Opened record">{opened}</output>
    <section className="connections-records">
      {view === 'table' ? <ConnectionsTable projectUuid="fixture" items={items} labels={labels} audit={{ state: 'ready', records }} onOpen={setOpened} />
        : <ConnectionCards items={items} view={view} audit={{ state: 'ready', records }} onOpen={setOpened} />}
    </section>
  </main>
}
void i18n.changeLanguage('en').then(() => createRoot(document.getElementById('root')!).render(
  <MemoryRouter><CurrentProjectProvider projectUuid="fixture"><ProjectAccessProvider value={{ roles: [], permissions: ['BAGLANTI_YONET'] }}>
    <ThemeProvider><AntDesignProvider><Harness /></AntDesignProvider></ThemeProvider>
  </ProjectAccessProvider></CurrentProjectProvider></MemoryRouter>,
))
