import { describe, expect, it } from 'vitest'
import { buildRunStepTree, firstFailedPath } from './runTree'
import type { RunStep } from './types'

const step = (uuid: string, parentUuid: string | null, ordinal: number, status = 'BASARILI'): RunStep => ({ uuid, parentUuid, ordinal, status, code: uuid, name: uuid, type: 'PROCEDURE', connectionRole: null, risk: null, startedAt: null, finishedAt: null, rowCount: null, byteCount: null, errorCode: null })

describe('run step tree', () => {
  it('uses server parent identities and order without guessing from names', () => { const tree = buildRunStepTree([step('child', 'root', 2), step('root', null, 1)]); expect(tree[0]?.children[0]?.uuid).toBe('child') })
  it('returns the exact first failing path for initial expansion', () => { const tree = buildRunStepTree([step('root', null, 1), step('ok', 'root', 1), step('failed', 'root', 2, 'BASARISIZ')]); expect(firstFailedPath(tree)).toEqual(['root', 'failed']) })
  it('keeps a missing-parent step visible as a root instead of inventing a parent', () => { expect(buildRunStepTree([step('orphan', 'missing', 1)])[0]?.uuid).toBe('orphan') })
})
