import type { DefinitionType, MappingContent } from './types'

export function initialSchemaVersion(type: DefinitionType, content?: unknown): number {
  if (type === 'KNOWLEDGE_MODULE' && content && typeof content === 'object' && 'language' in content && ['AKIS_KM/1', 'AKIS_KM/2', 'AKIS_KM/3'].includes(String(content.language))) return 2
  return type === 'MAPPING' ? 4 : type === 'PROCEDURE' ? 2 : 1
}

const directFields = ['id', 'alias', 'dataObjectUuid', 'schemaSnapshotUuid'] as const

type LegacyDataset = { id: string; role: 'SOURCE' | 'TARGET'; name?: string; dataObjectUuid?: string; schemaSnapshotUuid?: string; ui?: unknown }

/** Opens legacy dataset-based drafts without mutating immutable published versions. */
export function migrateLegacyMapping(value: unknown): MappingContent {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value as MappingContent
  const record = value as Record<string, unknown>
  if (Array.isArray(record.sources) && record.target && Array.isArray(record.columnMappings)) {
    return {
      ...record,
      sources: record.sources,
      target: record.target,
      joins: Array.isArray(record.joins) ? record.joins : [],
      filters: Array.isArray(record.filters) ? record.filters.filter(item => {
        if (!item || typeof item !== 'object' || Array.isArray(item)) return false
        const filter = item as Record<string, unknown>
        return typeof filter.object === 'string' && ((filter.predicate && typeof filter.predicate === 'object') || (typeof filter.column === 'string' && typeof filter.operator === 'string'))
      }) : [],
    } as MappingContent
  }
  if (!Array.isArray(record.datasets)) return value as MappingContent
  const legacy = record.datasets as LegacyDataset[]
  const preserved = { ...record }
  delete preserved.datasets
  delete preserved.writeStrategy
  const restore = (item: LegacyDataset) => {
    const ui = item.ui && typeof item.ui === 'object' && !Array.isArray(item.ui) ? item.ui as Record<string, unknown> : {}
    return {
      id: item.id,
      alias: String(item.name ?? ui.name ?? item.id),
      dataObjectUuid: String(item.dataObjectUuid ?? ui.dataObjectUuid ?? '') || undefined,
      schemaSnapshotUuid: String(item.schemaSnapshotUuid ?? ui.schemaSnapshotUuid ?? '') || undefined,
    }
  }
  const target = legacy.find(item => item.role === 'TARGET')
  return {
    ...preserved,
    sources: legacy.filter(item => item.role === 'SOURCE').map(restore),
    target: target ? restore(target) : { id: 'TARGET', alias: 'TGT' },
    joins: [],
    filters: [],
    columnMappings: Array.isArray(record.columnMappings) ? record.columnMappings.map(item => {
      const row = item as Record<string, unknown>
      const convert = (side: unknown) => {
        const ref = side as Record<string, unknown> | undefined
        return ref ? { object: String(ref.dataset ?? ref.object ?? ''), column: String(ref.column ?? '') } : undefined
      }
      return { ...row, source: convert(row.source), target: convert(row.target)! }
    }) : [],
  } as MappingContent
}

/** Keep catalog choices as editor hints, never as physical runtime bindings.
 * Unknown semantic fields are preserved so the backend can reject them.
 * Only explicitly saved drafts are converted; immutable versions are untouched.
 */
export function storeMapping(content: MappingContent): MappingContent {
  const sanitize = (reference: MappingContent['target']) => Object.fromEntries(directFields
    .flatMap(field => reference[field] === undefined ? [] : [[field, reference[field]]])) as MappingContent['target']
  return { ...content, sources: content.sources.map(sanitize), target: sanitize(content.target) }
}

export function editMapping(content: MappingContent): MappingContent {
  return migrateLegacyMapping(content)
}
