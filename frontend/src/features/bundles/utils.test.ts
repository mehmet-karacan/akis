import { describe, expect, it } from 'vitest'
import { BUNDLE_FORMAT, isBundleDocument, safeBundleFileName } from './utils'

function bundle() {
  return {
    format: BUNDLE_FORMAT,
    formatVersion: 1,
    schemaVersion: 1,
    checksum: 'a'.repeat(64),
    exportedAt: '2026-09-11T00:00:00Z',
    project: { code: 'DEMO', status: 'AKTIF', name: 'Demo', description: null },
    folders: [],
    definitions: [],
    topology: { sanitized: true },
  }
}

describe('bundle file safety', () => {
  it('creates a bounded filename without path separators or control characters', () => {
    const name = safeBundleFileName('../Müşteri / \u0000 Projesi')
    expect(name).toBe('Musteri-Projesi-bundle-v1.json')
    expect(name).not.toMatch(/[\\/\u0000-\u001f]/)
  })

  it('accepts only the supported AKIS bundle format and schema version', () => {
    expect(isBundleDocument(bundle())).toBe(true)
    expect(isBundleDocument({ ...bundle(), formatVersion: 2 })).toBe(false)
    expect(isBundleDocument({ ...bundle(), definitions: null })).toBe(false)
  })
})
