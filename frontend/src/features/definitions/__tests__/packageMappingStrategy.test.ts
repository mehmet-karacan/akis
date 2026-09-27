import { describe, expect, it } from 'vitest'
import { packageMappingStrategy } from '../packageMappingStrategy'
import type { PreRunPlan } from '../PreRunReport'

describe('package mapping write behavior', () => {
  it('uses the compiled IKM pin and reports TRUNCATE risk even when the mode says APPEND', () => {
    const plan: PreRunPlan = {
      physicalPlanHash: 'plan-hash',
      steps: [],
      bindings: [{ nodeCode: 'TGT', role: 'HEDEF', owner: 'ttbp', objectName: 'tarife' }],
      staging: { nonReversibleDdl: true },
      modules: { integration: {
        kind: 'IKM', versionUuid: 'pinned-ikm', contentHash: 'content-hash',
        options: { WRITE_MODE: 'APPEND', TRUNCATE_TARGET: true },
      } },
    }

    expect(packageMappingStrategy(plan)).toEqual({
      ikmVersionUuid: 'pinned-ikm', ikmContentHash: 'content-hash',
      writeMode: 'APPEND', target: 'ttbp.tarife', truncateTarget: true,
      nonReversibleDdl: true, planHash: 'plan-hash',
    })
  })

  it('does not invent an IKM pin or a target when the plan omits them', () => {
    expect(packageMappingStrategy({ physicalPlanHash: 'x', steps: [] })).toEqual({
      ikmVersionUuid: '', ikmContentHash: '', writeMode: '',
      target: '', truncateTarget: false, nonReversibleDdl: false, planHash: 'x',
    })
  })
})
