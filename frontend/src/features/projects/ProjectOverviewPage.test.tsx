import { describe, expect, it } from 'vitest'
import type { RunRecord } from '../execution/types'
import { summarizeRuns } from './ProjectOverviewPage'

const run = (status: string, suffix: string): RunRecord => ({
  jobRequestUuid: `job-${suffix}`,
  runUuid: `run-${suffix}`,
  publicationUuid: `publication-${suffix}`,
  attemptNumber: 1,
  startType: 'MANUEL',
  status,
  releaseHash: `release-${suffix}`,
  planHash: `plan-${suffix}`,
  createdAt: '2026-09-11T10:00:00Z',
  startedAt: null,
  finishedAt: null,
  cancellationRequestedAt: null,
})

describe('project operational summary', () => {
  it('groups active, successful and attention-required runs', () => {
    const result = summarizeRuns([
      run('CALISIYOR', '1'),
      run('BEKLIYOR', '2'),
      run('BASARILI', '3'),
      run('BASARISIZ', '4'),
      run('MUDAHALE_GEREKLI', '5'),
      run('IPTAL', '6'),
    ])

    expect(result).toEqual({ running: 2, succeeded: 1, attention: 2, total: 6 })
  })
})
