import { describe, expect, it } from 'vitest'
import type { ConnectionVersion, PhysicalSchema, SchemaBinding } from '../topology/api'
import { bindingForContext, compatibleVersions } from './bindingModel'

const physical: PhysicalSchema[] = [{ uuid: 'physical-sky', connectionUuid: 'sky', code: 'APP', schemaReference: 'APP', status: 'AKTIF', name: 'APP', version: 1 }]
const version = (uuid: string, mode: ConnectionVersion['mode'], capability: ConnectionVersion['runtimeCapability']): ConnectionVersion => ({ uuid, versionNumber: 1, mode, policyVersion: 2, policy: {}, createdAt: '2026-01-01T00:00:00Z', lifecycleStatus: 'ACTIVE', lifecycleVersion: 1, runtimeCapability: capability })

describe('schema binding compatibility', () => {
  it('offers only executable JDBC revisions from the physical schema connection', () => {
    const result = compatibleVersions('physical-sky', physical, { sky: [version('sky-jdbc', 'JDBC', 'EXECUTABLE'), version('sky-jndi', 'JNDI', 'TEST_DISCOVERY_ONLY')], gpu: [version('gpu-jdbc', 'JDBC', 'EXECUTABLE')] })
    expect(result.map((item) => item.uuid)).toEqual(['sky-jdbc'])
  })

  it('resolves one exact binding per logical schema and environment', () => {
    const binding = { uuid: 'binding', logicalSchemaUuid: 'orders', environmentUuid: 'test', physicalSchemaUuid: 'physical-sky', connectionVersionUuid: 'sky-r3', status: 'AKTIF', version: 1 } satisfies SchemaBinding
    expect(bindingForContext([binding], 'orders', 'test')?.connectionVersionUuid).toBe('sky-r3')
    expect(bindingForContext([binding], 'orders', 'prod')).toBeUndefined()
  })
})
