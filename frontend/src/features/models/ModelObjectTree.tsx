import { Tree, type TreeDataNode } from 'antd'
import { Folder, Table2, Eye } from 'lucide-react'
import type { DataObject, Submodel } from '../topology/api'

export function ModelObjectTree({ folders, objects, selectedUuid, onSelect }: { folders: Submodel[]; objects: DataObject[]; selectedUuid?: string | null; onSelect(uuid: string): void }) {
  const objectItem = (object: DataObject): TreeDataNode => ({ key: object.uuid, title: object.name, icon: object.type === 'VIEW' ? <Eye size={16} /> : <Table2 size={16} />, isLeaf: true })
  const branch = (folder: Submodel, ancestors: Set<string>): TreeDataNode => {
    const visited = new Set([...ancestors, folder.uuid])
    return { key: folder.uuid, title: folder.name, icon: <Folder size={16} />, selectable: false, children: [...folders.filter(item => item.parentUuid === folder.uuid && !visited.has(item.uuid)).map(item => branch(item, visited)), ...objects.filter(item => item.submodelUuid === folder.uuid).map(objectItem)] }
  }
  return <Tree className="model-catalog-tree" blockNode showIcon defaultExpandAll virtual={false} selectedKeys={selectedUuid ? [selectedUuid] : []} onSelect={keys => { if (keys[0]) onSelect(String(keys[0])) }} treeData={[...folders.filter(folder => !folder.parentUuid || !folders.some(item => item.uuid === folder.parentUuid)).map(folder => branch(folder, new Set())), ...objects.filter(object => !object.submodelUuid || !folders.some(folder => folder.uuid === object.submodelUuid)).map(objectItem)]} />
}
