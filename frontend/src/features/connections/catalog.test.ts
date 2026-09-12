import { describe, expect, it } from 'vitest'
import type { Connection, ConnectionVersion } from '../topology/api'
import { buildConnectionCatalog } from './catalog'

const connection = (index: number): Connection => ({ uuid: `connection-${index}`, code: `C_${index}`, databaseType: 'ORACLE', status: 'AKTIF', name: `Connection ${index}`, version: 1 })
const version = (connectionIndex: number, number: number, lifecycleStatus: ConnectionVersion['lifecycleStatus']): ConnectionVersion => ({ uuid: `version-${connectionIndex}-${number}`, versionNumber: number, mode: 'JDBC', host: 'db', port: 1521, serviceName: 'ORCL', policyVersion: 2, policy: {}, createdAt: '2026-01-01T00:00:00Z', lifecycleStatus, lifecycleVersion: 1, runtimeCapability: 'EXECUTABLE' })

describe('connection catalog projection', () => {
  it('shows the active revision while retaining the newer draft indicator', () => {
    const items = buildConnectionCatalog([connection(1)], { 'connection-1': [version(1, 4, 'DRAFT'), version(1, 3, 'ACTIVE')] }, [], [], [])
    expect(items[0]?.displayedVersion?.versionNumber).toBe(3)
    expect(items[0]?.latestVersionNumber).toBe(4)
  })

  it('builds a 500-row client projection without losing rows', () => {
    const connections = Array.from({ length: 500 }, (_, index) => connection(index))
    const versions = Object.fromEntries(connections.map((item, index) => [item.uuid, [version(index, 1, 'ACTIVE')]]))
    expect(buildConnectionCatalog(connections, versions, [], [], [])).toHaveLength(500)
  })
})
