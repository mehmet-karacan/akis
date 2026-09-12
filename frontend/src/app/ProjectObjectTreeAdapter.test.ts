import { describe, expect, it } from 'vitest'
import type { Folder } from '../features/definitions/types'
import { buildFolderTree, matchesObjectSearch } from './ProjectObjectTreeAdapter'

const folder = (uuid: string, parentUuid: string | null, name = uuid): Folder => ({ uuid, parentUuid, code: uuid.toUpperCase(), status: 'AKTIF', name, description: null, version: 1 })

describe('project object tree adapter', () => {
  it('keeps same-named folders distinct and recovers orphans', () => {
    const tree = buildFolderTree([folder('a', null, 'Finance'), folder('b', null, 'Finance'), folder('orphan', 'missing')])
    expect(tree.map((item) => item.uuid).sort()).toEqual(['a', 'b', 'orphan'])
  })
  it('does not recurse forever for cyclic parents', () => {
    const tree = buildFolderTree([folder('alpha', 'beta'), folder('beta', 'alpha')])
    expect(tree.flatMap((item) => [item.uuid, ...item.children.map((child) => child.uuid)]).sort()).toEqual(['alpha', 'beta'])
  })
  it('uses Turkish casing for İ and ı', () => {
    expect(matchesObjectSearch('İş Akışı', 'IS_AKISI', 'Veri akışı', 'iş', 'tr')).toBe(true)
    expect(matchesObjectSearch('Işık', 'ISIK', 'Değişken', 'ışık', 'tr')).toBe(true)
  })
})
