import { describe, expect, it } from 'vitest'
import { createDefaultContent, isMappingContent } from '../defaults'
import { filterMappingRows, pageCount, safePage } from '../mappingUtils'
import type { ColumnMapping, MappingContent } from '../types'

function rows(count: number): ColumnMapping[] {
  return Array.from({ length: count }, (_, index) => ({
    source: { dataset: 'SOURCE_1', column: `SRC_${index}` },
    target: { dataset: 'TARGET_1', column: `TARGET_${index}` },
  }))
}

describe('mapping grid helpers', () => {
  it('filters a 500-row mapping without changing domain row indexes', () => {
    const result = filterMappingRows(rows(500), 'target_499')
    expect(result).toHaveLength(1)
    expect(result[0]?.index).toBe(499)
  })

  it('keeps pagination within available pages', () => {
    expect(pageCount(500)).toBe(10)
    expect(safePage(99, 500)).toBe(9)
    expect(safePage(-3, 0)).toBe(0)
  })

  it('returns independent starter documents', () => {
    const first = createDefaultContent('MAPPING') as MappingContent
    const second = createDefaultContent('MAPPING') as MappingContent
    first.datasets[0]!.id = 'CHANGED'
    expect(second.datasets[0]!.id).toBe('SOURCE_1')
    expect(isMappingContent(second)).toBe(true)
  })
})

