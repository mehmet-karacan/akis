import type { DataObject, DiscoveryTable } from '../topology/api'

export interface MetadataImportPorts {
  listObjects(): Promise<DataObject[]>
  createObject(table: DiscoveryTable): Promise<DataObject>
  captureSnapshot(object: DataObject): Promise<unknown>
  isActive(): boolean
  onRegistered(object: DataObject): void
  onImported(table: DiscoveryTable, completed: number): void
}

export const discoveryTableKey = (table: DiscoveryTable) => `${table.owner}.${table.name}`

/** Read the authoritative catalog on every attempt. A failed/uncertain create
 * response must never cause a blind create retry from an obsolete UI snapshot.
 * Snapshot capture is the completion boundary; registration alone is not success.
 */
export async function importModelMetadata(tables: DiscoveryTable[], ports: MetadataImportPorts): Promise<number> {
  if (!ports.isActive() || tables.length === 0) return 0
  const objects = [...await ports.listObjects()]
  let completed = 0
  for (const table of tables) {
    if (!ports.isActive()) break
    const matches = objects.filter(object => object.objectReference.toUpperCase() === table.name.toUpperCase())
    if (matches.length > 1) throw new Error('METADATA_OBJECT_AMBIGUOUS')
    let object = matches[0]
    if (!object) {
      object = await ports.createObject(table)
      objects.push(object)
    }
    ports.onRegistered(object)
    // Leaving the screen cannot cancel an already submitted create, but must
    // prevent the next request (and any remaining tables) from starting.
    if (!ports.isActive()) break
    await ports.captureSnapshot(object)
    completed += 1
    ports.onImported(table, completed)
  }
  return completed
}
