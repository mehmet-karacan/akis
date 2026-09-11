import { describe, expect, it } from 'vitest'
import type { Publication } from '../operations/types'
import { isRunnablePublication } from './types'

const publication = (status: string, runtimeCapability: string): Publication => ({
  uuid: 'publication-1',
  scenarioUuid: 'scenario-1',
  definitionUuid: 'definition-1',
  definitionVersionUuid: 'version-1',
  environmentUuid: 'environment-1',
  environmentCode: 'TEST',
  environmentRisk: 'DUSUK',
  publicationNumber: 1,
  status,
  releaseHash: 'release',
  dependencySummary: '{}',
  physicalManifest: { runtimeCapability },
  publishedAt: null,
  createdAt: '2026-09-11T10:00:00Z',
  version: 1,
})

describe('publication runtime capability', () => {
  it('allows only active capabilities supported by the worker', () => {
    expect(isRunnablePublication(publication('AKTIF', 'ORACLE_TABLE_COPY_V1'))).toBe(true)
    expect(isRunnablePublication(publication('AKTIF', 'ORACLE_PROCEDURE_V1'))).toBe(true)
    expect(isRunnablePublication(publication('AKTIF', 'DEFINITION_ONLY'))).toBe(false)
    expect(isRunnablePublication(publication('ONAY_BEKLIYOR', 'ORACLE_PROCEDURE_V1'))).toBe(false)
  })
})
