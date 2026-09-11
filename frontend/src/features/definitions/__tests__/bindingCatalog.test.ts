import { describe, expect, it } from 'vitest'
import { bindingNodes, candidateLabel, unboundNodes } from '../bindingCatalog'
import type { BindingCandidate, DataBinding } from '../types'

describe('definition binding catalog', () => {
  it('derives governed procedure nodes and roles from immutable content', () => {
    expect(bindingNodes('PROCEDURE', { tasks: [
      { id: 'READ_SOURCE', name: 'Read source', connectionRole: 'SOURCE' },
      { id: 'WRITE_TARGET', connectionRole: 'TARGET' },
      { id: '', connectionRole: 'SOURCE' },
    ] })).toEqual([
      { code: 'READ_SOURCE', name: 'Read source', role: 'KAYNAK' },
      { code: 'WRITE_TARGET', name: 'WRITE_TARGET', role: 'HEDEF' },
    ])
  })

  it('removes already-bound nodes and renders a human catalog label', () => {
    const binding = { nodeCode: 'READ_SOURCE' } as DataBinding
    const nodes = bindingNodes('MAPPING', { datasets: [
      { id: 'READ_SOURCE', role: 'SOURCE' },
      { id: 'WRITE_TARGET', role: 'TARGET' },
    ] })
    expect(unboundNodes(nodes, [binding]).map((node) => node.code)).toEqual(['WRITE_TARGET'])

    const candidate = {
      connectionCode: 'SKY', physicalSchemaReference: 'TTBP',
      objectReference: 'HAKEDIS_TIPI', connectionVersionNumber: 1,
      snapshotFingerprint: '1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef',
    } as BindingCandidate
    expect(candidateLabel(candidate)).toBe('SKY · TTBP.HAKEDIS_TIPI · v1 · 1234567890…abcdef')
  })
})
