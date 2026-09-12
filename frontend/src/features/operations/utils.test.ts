import { describe, expect, it } from 'vitest'
import { apiErrorMessage, isUuid, redactSensitiveText, redactSensitiveValues, toOffsetDateTime } from './utils'

describe('operations utilities', () => {
  it('redacts sensitive values at every manifest depth without mutating safe context', () => {
    const manifest = {
      environment: { code: 'TEST', clientSecret: 'must-not-render' },
      bindings: [{ nodeCode: 'CUSTOMERS', credentials: { token: 'hidden' } }],
    }

    expect(redactSensitiveValues(manifest)).toEqual({
      environment: { code: 'TEST', clientSecret: '[REDACTED]' },
      bindings: [{ nodeCode: 'CUSTOMERS', credentials: '[REDACTED]' }],
    })
    expect(manifest.environment.clientSecret).toBe('must-not-render')
  })

  it('accepts only canonical UUID values used by controller paths', () => {
    expect(isUuid('6ba7b810-9dad-41d1-80b4-00c04fd430c8')).toBe(true)
    expect(isUuid('not-a-uuid')).toBe(false)
  })

  it('converts local form values to offset-compatible ISO timestamps', () => {
    expect(toOffsetDateTime('')).toBeNull()
    expect(toOffsetDateTime('2026-09-11T10:30')).toMatch(/^2026-09-11T/)
  })

  it('redacts credentials embedded in plain log and error strings', () => {
    expect(redactSensitiveText('password=hunter2 Bearer abc.def.ghi')).toBe('password=[REDACTED] Bearer [REDACTED]')
    expect(redactSensitiveText('jdbc:oracle:thin:user:pass@host')).toBe('jdbc:oracle:thin:user:pass@host')
    expect(redactSensitiveText('https://user:pass@host/path')).toBe('https://[REDACTED]@host/path')
    expect(apiErrorMessage(new Error('token:top-secret'), 'fallback')).toBe('token:[REDACTED]')
  })
})
