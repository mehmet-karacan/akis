import type { DataObject, Model, SchemaSnapshot } from '../topology/api'
import type { MappingObjectReference } from './types'

export interface MappingCatalogEntry {
  model: Model
  object: DataObject
  /** Latest capture, used only when explicitly assigning a catalog object. */
  snapshot?: SchemaSnapshot
  snapshots?: SchemaSnapshot[]
  folderPath?: string
}

/** A reverse-engineering refresh must not silently change the metadata shown
 * for an existing mapping while publication still uses its pinned capture.
 */
export function mappingCatalogEntry(reference: MappingObjectReference | undefined, catalog: MappingCatalogEntry[]): MappingCatalogEntry | undefined {
  const entry = catalog.find(item => item.object.uuid === reference?.dataObjectUuid)
  if (!entry || !reference?.schemaSnapshotUuid) return entry
  const snapshots = entry.snapshots ?? (entry.snapshot ? [entry.snapshot] : [])
  return { ...entry, snapshot: snapshots.find(snapshot => snapshot.uuid === reference.schemaSnapshotUuid) }
}

export function mappingMetadataNotice(reference: MappingObjectReference | undefined, catalog: MappingCatalogEntry[], tr: boolean): string | undefined {
  if (!reference?.dataObjectUuid) return undefined
  const entry = catalog.find(item => item.object.uuid === reference.dataObjectUuid)
  if (!entry) return tr ? 'Veri nesnesi katalogda bulunamadı. Eşlemeler korunuyor; model erişimini kontrol edin.' : 'The data object is unavailable in the catalog. Mappings are preserved; check model access.'
  const selected = mappingCatalogEntry(reference, catalog)?.snapshot
  if (reference.schemaSnapshotUuid && !selected) return tr
    ? 'Bu arayüzün kullandığı metadata sürümü bulunamadı. Eşlemeler korunuyor; güncel sürümü kullanmak için veri nesnesini model ağacından yeniden bırakın.'
    : 'The metadata version used by this interface is unavailable. Mappings are preserved; drop the data object from the model tree again to adopt the current version.'
  if (!entry.snapshot) return tr ? 'Kolon metadatası henüz alınmamış. Model veya veri nesnesinden Reverse Engineer işlemini kullanın.' : 'Column metadata has not been captured. Use Reverse Engineer on the model or data object.'
  if (!reference.schemaSnapshotUuid) return tr
    ? 'Metadata sürümü henüz seçilmemiş. Görünen kolonlar en son yakalamadır; sürümü arayüze bağlamak için veri nesnesini model ağacından yeniden bırakın.'
    : 'No metadata version is assigned. The columns shown are the latest capture; drop the data object from the model tree again to bind that version to the interface.'
  if (entry.snapshot.uuid !== reference.schemaSnapshotUuid) return tr
    ? 'Daha yeni metadata mevcut. Bu arayüz seçili sürümü kullanmaya devam eder. Güncellemek için veri nesnesini model ağacından yeniden bırakın; artık bulunmayan kolonlara ait eşlemeler kaldırılır ve Geri Al ile geri getirilebilir.'
    : 'Newer metadata is available. This interface keeps using its selected version. Drop the data object from the model tree again to update; mappings to removed columns are cleared and can be restored with Undo.'
  return undefined
}
