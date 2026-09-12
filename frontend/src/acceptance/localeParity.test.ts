import { describe, expect, it } from 'vitest'
import en from '../core/i18n/locales/en.json'
import tr from '../core/i18n/locales/tr.json'
import { definitionCodeLabel } from '../features/definitions/i18n'
import { executionCodeLabel } from '../features/execution/i18n'

describe('English and Turkish locale coverage', () => {
  it('has exactly the same core translation keys in both languages', () => {
    expect(Object.keys(tr).sort()).toEqual(Object.keys(en).sort())
  })
  it('turns backend codes into readable labels without changing their values', () => {
    expect(definitionCodeLabel('ATOMIC_DELETE_INSERT', 'tr')).toBe('Atomik sil ve ekle')
    expect(definitionCodeLabel('STORED_PROCEDURE', 'en')).toBe('Stored procedure')
    expect(definitionCodeLabel('FUTURE_STEP', 'tr')).toBe('Future Step')
    expect(executionCodeLabel('RUN_STARTED', 'tr-TR')).toBe('Run Started')
    expect(executionCodeLabel('URETIM', 'tr-TR')).toBe('Üretim')
  })
})
