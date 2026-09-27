import { describe, expect, it } from 'vitest'
import { mapPackageStepsBounded } from '../packageSimulationConcurrency'

describe('package report concurrency', () => {
  it('limits simultaneous lookups while retaining input order', async () => {
    let active = 0
    let maximum = 0
    const results = await mapPackageStepsBounded([1, 2, 3, 4, 5, 6], 3, async (value) => {
      active++
      maximum = Math.max(maximum, active)
      await new Promise((resolve) => setTimeout(resolve, 10 - value))
      active--
      return value * 2
    })

    expect(maximum).toBe(3)
    expect(results).toEqual([2, 4, 6, 8, 10, 12])
  })

  it('does not start work for an empty package', async () => {
    const results = await mapPackageStepsBounded([], 4, async () => { throw new Error('unexpected task') })
    expect(results).toEqual([])
  })
})
