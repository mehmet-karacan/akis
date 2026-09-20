import type { Folder } from '../features/definitions/types'

export interface FolderTreeNode extends Folder { children: FolderTreeNode[] }

/** Follow the rendered hierarchy, including orphan/cycle recovery, rather than raw parent links. */
export function folderPath(tree: FolderTreeNode[], uuid: string): string[] {
  for (const folder of tree) {
    if (folder.uuid === uuid) return [folder.uuid]
    const descendants = folderPath(folder.children, uuid)
    if (descendants.length) return [folder.uuid, ...descendants]
  }
  return []
}

export function buildFolderTree(folders: Folder[], locale = 'en'): FolderTreeNode[] {
  const sorted = [...folders].sort((left, right) => left.name.localeCompare(right.name, locale))
  const byParent = new Map<string | null, Folder[]>()
  const known = new Set(sorted.map((folder) => folder.uuid))
  for (const folder of sorted) {
    const parent = folder.parentUuid && known.has(folder.parentUuid) ? folder.parentUuid : null
    const siblings = byParent.get(parent)
    if (siblings) siblings.push(folder)
    else byParent.set(parent, [folder])
  }
  const visited = new Set<string>()
  const visit = (folder: Folder, ancestors: Set<string>): FolderTreeNode | null => {
    if (visited.has(folder.uuid) || ancestors.has(folder.uuid)) return null
    visited.add(folder.uuid)
    const lineage = new Set(ancestors).add(folder.uuid)
    return { ...folder, children: (byParent.get(folder.uuid) ?? []).map((child) => visit(child, lineage)).filter((child): child is FolderTreeNode => child !== null) }
  }
  const roots = (byParent.get(null) ?? []).map((folder) => visit(folder, new Set())).filter((folder): folder is FolderTreeNode => folder !== null)
  for (const folder of sorted) if (!visited.has(folder.uuid)) { const recovered = visit(folder, new Set()); if (recovered) roots.push(recovered) }
  return roots
}

export function matchesObjectSearch(name: string, code: string, type: string, query: string, locale: string) {
  const normalized = query.trim().toLocaleLowerCase(locale)
  return normalized.length === 0 || `${name} ${code} ${type}`.toLocaleLowerCase(locale).includes(normalized)
}
