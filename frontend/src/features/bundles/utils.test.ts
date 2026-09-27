import { describe, expect, it } from 'vitest'
import { BUNDLE_FORMAT, isBundleDocument, isV3Bundle, safeBundleFileName } from './utils'

function bundle() {
  return {
    format: BUNDLE_FORMAT,
    formatVersion: 2,
    schemaVersion: 2,
    checksum: 'a'.repeat(64),
    exportedAt: '2026-09-11T00:00:00Z',
    project: { code: 'DEMO', status: 'AKTIF', name: 'Demo', description: null },
    folders: [],
    definitions: [],
    topology: { sanitized: true, definitions: {} },
  }
}

describe('bundle file safety', () => {
  it('creates a bounded filename without path separators or control characters', () => {
    const name = safeBundleFileName('../Müşteri / \u0000 Projesi')
    expect(name).toBe('Musteri-Projesi-bundle-v3.json')
    expect(name).not.toMatch(/[\\/\u0000-\u001f]/)
  })

  it('accepts only the supported AKIS bundle format and schema version', () => {
    expect(isBundleDocument(bundle())).toBe(true)
    expect(isBundleDocument({ ...bundle(), formatVersion: 1 })).toBe(false)
    expect(isBundleDocument({ ...bundle(), definitions: null })).toBe(false)
  })

  it('detects v3 bundles by formatVersion', () => {
    expect(isV3Bundle(bundle())).toBe(false)
    expect(isV3Bundle({ ...bundle(), formatVersion: 3, schemaVersion: 3 })).toBe(true)
  })
})
