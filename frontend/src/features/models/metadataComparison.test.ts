import { describe, expect, it } from 'vitest'
import { compareMetadata } from './metadataComparison'

describe('metadata diff', () => {
  it('keeps removed columns explicit instead of rebinding them', () => {
    const changes = compareMetadata(
      [{ reference: 'OLD_ID', producerType: 'NUMBER', canonicalType: 'INTEGER', ordinal: 1, nullable: false }],
      [{ name: 'NEW_ID', jdbcType: 2, producerType: 'NUMBER', canonicalType: 'INTEGER', executionCapability: 'TRANSFER_SUPPORTED', ordinal: 1, nullable: false }],
    )
    expect(changes.map(({ kind, name }) => ({ kind, name }))).toEqual([{ kind: 'ADDED', name: 'NEW_ID' }, { kind: 'REMOVED', name: 'OLD_ID' }])
  })
  it('reports type changes', () => {
    const changes = compareMetadata(
      [{ reference: 'AMOUNT', producerType: 'NUMBER', canonicalType: 'DECIMAL', ordinal: 1, precision: 10, scale: 2, nullable: true }],
      [{ name: 'AMOUNT', jdbcType: 2, producerType: 'NUMBER', canonicalType: 'DECIMAL', executionCapability: 'TRANSFER_SUPPORTED', ordinal: 1, precision: 18, scale: 2, nullable: true }],
    )
    expect(changes[0]?.kind).toBe('CHANGED')
  })
})
