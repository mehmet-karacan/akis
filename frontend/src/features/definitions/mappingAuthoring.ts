import type { DefinitionType, MappingContent } from './types'

export function initialSchemaVersion(type: DefinitionType, content?: unknown): number {
  if (type === 'KNOWLEDGE_MODULE' && content && typeof content === 'object' && 'language' in content && content.language === 'AKIS_KM/1') return 2
  return type === 'MAPPING' || type === 'PROCEDURE' ? 2 : 1
}

const editorFields = ['name', 'dataObjectUuid', 'schemaSnapshotUuid'] as const

/** Keep catalog choices as editor hints, never as physical runtime bindings.
 * Unknown semantic fields are preserved so the backend can reject them.
 * Only explicitly saved drafts are converted; immutable versions are untouched.
 */
export function storeMapping(content: MappingContent): MappingContent {
  return {
    ...content,
    datasets: content.datasets.map((dataset) => {
      const result = { ...dataset }
      const originalUi = dataset.ui
      if (originalUi != null && (typeof originalUi !== 'object' || Array.isArray(originalUi))) {
        throw new Error('Mapping dataset ui must be an object.')
      }
      const ui = { ...(originalUi as Record<string, unknown> | undefined) }
      for (const field of editorFields) {
        if (Object.hasOwn(dataset, field)) {
          if (dataset[field] === undefined) delete ui[field]
          else ui[field] = dataset[field]
          delete result[field]
        }
      }
      if (Object.keys(ui).length || originalUi != null) result.ui = ui
      return result
    }),
  }
}

export function editMapping(content: MappingContent): MappingContent {
  return {
    ...content,
    datasets: content.datasets.map((dataset) => {
      const result = { ...dataset }
      const ui = dataset.ui
      if (ui && typeof ui === 'object' && !Array.isArray(ui)) {
        for (const field of editorFields) {
          const hint = (ui as Record<string, unknown>)[field]
          if (!Object.hasOwn(dataset, field) && typeof hint === 'string') result[field] = hint
        }
      }
      return result
    }),
  }
}
