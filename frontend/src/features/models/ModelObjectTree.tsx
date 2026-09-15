import { Folder, Table2, Eye } from 'lucide-react'
import type { DataObject, Submodel } from '../topology/api'

export function ModelObjectTree({ folders, objects, selectedUuid, onSelect }: { folders: Submodel[]; objects: DataObject[]; selectedUuid?: string | null; onSelect(uuid: string): void }) {
  const objectItem = (object: DataObject) => <li key={object.uuid}><button type="button" className="sidebar-folder-row" aria-current={selectedUuid === object.uuid ? 'page' : undefined} onClick={() => onSelect(object.uuid)}>{object.type === 'VIEW' ? <Eye /> : <Table2 />}<span>{object.name}</span></button></li>
  const branch = (folder: Submodel, ancestors: Set<string>): React.ReactNode => {
    if (ancestors.has(folder.uuid)) return null
    const visited = new Set([...ancestors, folder.uuid])
    return <li key={folder.uuid}><details open><summary><Folder size={16} /><span>{folder.name}</span></summary><ul>{folders.filter((item) => item.parentUuid === folder.uuid).map((item) => branch(item, visited))}{objects.filter((item) => item.submodelUuid === folder.uuid).map(objectItem)}</ul></details></li>
  }
  return <ul className="model-catalog-tree">{folders.filter((folder) => !folder.parentUuid || !folders.some((item) => item.uuid === folder.parentUuid)).map((folder) => branch(folder, new Set()))}{objects.filter((object) => !object.submodelUuid || !folders.some((folder) => folder.uuid === object.submodelUuid)).map(objectItem)}</ul>
}
