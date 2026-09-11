import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConnectionVersionLifecyclePanel, abbreviateFingerprint } from './ConnectionVersionLifecyclePanel'
import { topologyApi, type ConnectionTestAttempt, type ConnectionVersion } from './api'
import { getTopologyCopy } from './copy'

const testedVersion: ConnectionVersion = {
  uuid: 'version-uuid',
  versionNumber: 2,
  mode: 'JDBC',
  host: 'db.example',
  port: 1521,
  serviceName: 'ORCLPDB',
  tlsMode: 'DISABLED',
  policyVersion: 2,
  createdAt: '2026-09-11T12:00:00Z',
  lifecycleStatus: 'TESTED',
  lifecycleVersion: 3,
  targetIdentityVersion: 1,
  targetFingerprint: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
  latestSuccessfulTestUuid: 'test-uuid',
  testedAt: '2026-09-11T12:02:00Z',
  activatedAt: null,
  runtimeCapability: 'EXECUTABLE',
}

const passedAttempt: ConnectionTestAttempt = {
  uuid: 'test-uuid',
  connectionVersionUuid: testedVersion.uuid,
  attemptNumber: 1,
  outcome: 'PASSED',
  probe: {
    databaseProduct: 'Oracle',
    databaseVersion: '19c',
    databaseMajorVersion: 19,
    databaseMinorVersion: 0,
    driverName: 'Oracle JDBC',
    driverVersion: '19.3',
  },
  targetIdentityVersion: 1,
  targetFingerprint: testedVersion.targetFingerprint,
  startedAt: '2026-09-11T12:01:59Z',
  completedAt: '2026-09-11T12:02:00Z',
  durationMs: 1000,
}

afterEach(() => vi.restoreAllMocks())

describe('connection version lifecycle panel', () => {
  it('activates a tested executable version with the pinned test and lifecycle version', async () => {
    vi.spyOn(topologyApi, 'listConnectionVersionTests').mockResolvedValue([passedAttempt])
    const activate = vi.spyOn(topologyApi, 'activateConnectionVersion').mockResolvedValue({
      connectionVersionUuid: testedVersion.uuid,
      status: 'ACTIVE',
      stateVersion: 4,
    })
    const changed = vi.fn().mockResolvedValue(undefined)

    render(<ConnectionVersionLifecyclePanel projectUuid="project" connectionUuid="connection" version={testedVersion} copy={getTopologyCopy('en')} locale="en-GB" onVersionChanged={changed} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Activate Version' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm Activation' }))

    await waitFor(() => expect(activate).toHaveBeenCalledWith('project', 'connection', 'version-uuid', {
      testUuid: 'test-uuid',
      expectedStateVersion: 3,
    }))
    expect(changed).toHaveBeenCalledOnce()
  })

  it('keeps activation unavailable for a test-only JNDI version', async () => {
    vi.spyOn(topologyApi, 'listConnectionVersionTests').mockResolvedValue([passedAttempt])

    render(<ConnectionVersionLifecyclePanel projectUuid="project" connectionUuid="connection" version={{ ...testedVersion, mode: 'JNDI', runtimeCapability: 'TEST_DISCOVERY_ONLY' }} copy={getTopologyCopy('en')} locale="en-GB" onVersionChanged={vi.fn()} />)

    expect(await screen.findByText('Runtime limited')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Activate Version' })).not.toBeInTheDocument()
    expect(screen.getByText(/metadata\/test only/)).toBeInTheDocument()
  })

  it('abbreviates long fingerprints without changing short ones', () => {
    expect(abbreviateFingerprint('123456789012345678901234567890')).toBe('123456789012…34567890')
    expect(abbreviateFingerprint('short-fingerprint')).toBe('short-fingerprint')
  })
})
