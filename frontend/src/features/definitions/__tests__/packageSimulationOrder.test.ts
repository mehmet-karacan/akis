import { afterEach, describe, expect, it, vi } from 'vitest'
import { definitionsApi } from '../api'
import { simulatePackage } from '../SimulationReports'
import type { PackageContent } from '../packageGraph'

afterEach(() => vi.restoreAllMocks())

describe('package simulation', () => {
  it('resolves child reports concurrently but emits the original step order', async () => {
    let active = 0
    let maximum = 0
    const versions = vi.spyOn(definitionsApi, 'listVersions').mockImplementation(async () => {
      active++
      maximum = Math.max(maximum, active)
      await new Promise((resolve) => setTimeout(resolve, 2))
      active--
      return [{ uuid: 'version', content: {} }] as unknown as Awaited<ReturnType<typeof definitionsApi.listVersions>>
    })
    const steps = Array.from({ length: 6 }, (_, index) => ({
      id: `step-${index}`, type: 'PACKAGE' as const, definitionUuid: `definition-${index}`,
    }))
    const content: PackageContent = {
      firstStepId: steps[0]!.id,
      steps,
      transitions: steps.slice(0, -1).map((step, index) => ({
        fromStepId: step.id, toStepId: steps[index + 1]!.id, outcome: 'SUCCESS' as const,
      })),
    }
    const definitions = steps.map((step) => ({
      uuid: step.definitionUuid, type: 'PACKAGE', name: step.id, code: step.id,
    })) as unknown as Parameters<typeof simulatePackage>[3]
    const environment = { uuid: 'environment', code: 'TEST', name: 'Test' } as Parameters<typeof simulatePackage>[2]
    const topology = { environments: [environment], connections: [], bindings: [], physical: [], logical: [] } as Parameters<typeof simulatePackage>[4]

    const report = await simulatePackage('project', content, environment, definitions, topology, true)

    expect(report.path.map((item) => item.step.id)).toEqual(steps.map((step) => step.id))
    expect(report.path.every((item) => item.child?.kind === 'PACKAGE')).toBe(true)
    expect(versions).toHaveBeenCalledTimes(6)
    expect(maximum).toBe(4)
  })

  it('fetches a repeated definition only once for one report', async () => {
    const versions = vi.spyOn(definitionsApi, 'listVersions').mockResolvedValue(
      [{ uuid: 'version', content: {} }] as unknown as Awaited<ReturnType<typeof definitionsApi.listVersions>>,
    )
    const content: PackageContent = {
      firstStepId: 'first',
      steps: [
        { id: 'first', type: 'PACKAGE', definitionUuid: 'same' },
        { id: 'second', type: 'PACKAGE', definitionUuid: 'same' },
      ],
      transitions: [{ fromStepId: 'first', toStepId: 'second', outcome: 'SUCCESS' }],
    }
    const definitions = [{ uuid: 'same', type: 'PACKAGE', name: 'Shared', code: 'SHARED' }] as unknown as Parameters<typeof simulatePackage>[3]
    const environment = { uuid: 'environment', code: 'TEST', name: 'Test' } as Parameters<typeof simulatePackage>[2]
    const topology = { environments: [environment], connections: [], bindings: [], physical: [], logical: [] } as Parameters<typeof simulatePackage>[4]

    const report = await simulatePackage('project', content, environment, definitions, topology, true)

    expect(report.path).toHaveLength(2)
    expect(versions).toHaveBeenCalledTimes(1)
  })
})
