import { expect, it } from 'vitest'
import { recordAuditPresentation } from './recordAuditPresentation'

it('keeps empty, loading and failed audit states distinct and identical across views', () => {
  for (const [state, en, tr] of [['ready', 'Not Recorded', 'Kaydedilmemiş'], ['loading', 'Loading…', 'Yükleniyor…'], ['error', 'Unavailable', 'Bilgi alınamadı']] as const) {
    expect(Object.values(recordAuditPresentation(undefined, state, 'en'))).toEqual(Array(4).fill(en))
    expect(Object.values(recordAuditPresentation(undefined, state, 'tr-TR'))).toEqual(Array(4).fill(tr))
  }
})

it('retains available authors and formats dates consistently without inventing missing updates', () => {
  const result = recordAuditPresentation({ uuid: 'record', createdBy: 'Fixture', createdAt: '2026-09-12T10:00:00Z', updatedBy: null, updatedAt: null }, 'ready', 'en')
  expect(result.createdBy).toBe('Fixture')
  expect(result.createdAt).toContain('2026')
  expect(result.updatedBy).toBe('Not Recorded')
  expect(result.updatedAt).toBe('Not Recorded')
})
