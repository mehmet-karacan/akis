import { describe, expect, it } from 'vitest'
import en from './locales/en.json'
import tr from './locales/tr.json'

describe('application translations', () => {
  it('keeps English and Turkish key sets identical', () => {
    expect(Object.keys(tr).sort()).toEqual(Object.keys(en).sort())
  })
})
