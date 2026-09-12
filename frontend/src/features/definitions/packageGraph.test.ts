import { describe, expect, it } from 'vitest'
import { addPackageTransition, duplicatePackageStep, packageValidation, removePackageStep, withoutPackageLayout, type PackageContent } from './packageGraph'

const content: PackageContent = { firstStepId: 'A', steps: [{ id: 'A', type: 'PROCEDURE' }, { id: 'B', type: 'MAPPING' }], transitions: [{ fromStepId: 'A', toStepId: 'B', outcome: 'SUCCESS' }] }
describe('package graph domain adapter', () => {
  it('duplicates identity without hidden transitions', () => { const next = duplicatePackageStep(content, 'A'); expect(next.steps[2]?.id).not.toBe('A'); expect(next.transitions).toEqual(content.transitions) })
  it('removes node, transitions and explicit start as one change', () => { const next = removePackageStep(content, 'A'); expect(next.firstStepId).toBe(''); expect(next.transitions).toHaveLength(0); expect(packageValidation(next)).toContain('START_REQUIRED') })
  it('rejects cycles and duplicate outcome ports', () => { expect(addPackageTransition(content, { fromStepId: 'B', toStepId: 'A', outcome: 'SUCCESS' })).toBeNull(); expect(addPackageTransition(content, { fromStepId: 'A', toStepId: 'B', outcome: 'SUCCESS' })).toBeNull() })
  it('detects unreachable steps and duplicate persisted ports', () => { const invalid = { ...content, steps: [...content.steps, { id: 'C', type: 'PROCEDURE' as const }, { id: 'D', type: 'MAPPING' as const }], transitions: [...content.transitions, { fromStepId: 'A', toStepId: 'C', outcome: 'SUCCESS' as const }] }; expect(packageValidation(invalid)).toEqual(expect.arrayContaining(['DUPLICATE_OUTCOME', 'UNREACHABLE_STEP'])) })
  it('keeps canvas coordinates outside the semantic package model', () => { expect(withoutPackageLayout({ ...content, steps: [{ ...content.steps[0]!, x: 40, y: 80 }, content.steps[1]!] })).toEqual(content) })
  it('validates a 500 step graph without recursive overflow', () => { const steps = Array.from({ length: 500 }, (_, index) => ({ id: `S${index}`, type: 'PROCEDURE' as const })); const transitions = steps.slice(1).map((step, index) => ({ fromStepId: `S${index}`, toStepId: step.id, outcome: 'SUCCESS' as const })); expect(packageValidation({ firstStepId: 'S0', steps, transitions })).toEqual([]) })
})
