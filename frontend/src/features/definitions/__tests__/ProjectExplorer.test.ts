import { describe, expect, it } from 'vitest'
import { buildFolderTree } from '../ProjectExplorer'
import type { Folder } from '../types'

const folder = (uuid: string, parentUuid: string | null, name = uuid): Folder => ({
  uuid,
  parentUuid,
  code: uuid.toUpperCase(),
  type: 'GELISTIRME',
  status: 'AKTIF',
  name,
  description: null,
  version: 1,
})

describe('project explorer hierarchy', () => {
  it('builds nested folders and recovers orphaned nodes at the root', () => {
    const tree = buildFolderTree([
      folder('child', 'root'),
      folder('orphan', 'missing'),
      folder('root', null),
    ])

    expect(tree.map((item) => item.uuid)).toEqual(['orphan', 'root'])
    expect(tree[1]?.children.map((item) => item.uuid)).toEqual(['child'])
  })

  it('does not recurse forever when stored parents form a cycle', () => {
    const tree = buildFolderTree([folder('alpha', 'beta'), folder('beta', 'alpha')])
    expect(tree.flatMap((item) => [item.uuid, ...item.children.map((child) => child.uuid)]).sort()).toEqual(['alpha', 'beta'])
  })
})
