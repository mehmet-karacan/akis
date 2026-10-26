import { describe, expect, it } from 'vitest'
import type { SiraTanimi } from './api'
import { findSequenceForColumn, findSequencesForTable } from './sequenceAssociation'

const sequence = (ad: string): SiraTanimi => ({
  uuid: ad,
  semaTanimiUuid: 'schema-1',
  ad,
  baslangicDegeri: 1,
  artisMiktari: 1,
  minDeger: 1,
  maxDeger: null,
  donguselMi: false,
})

describe('findSequenceForColumn', () => {
  it('uses the exact table and column name instead of the first sequence containing id', () => {
    const sequences = [sequence('iliski_kolon_tanimlari_id_seq'), sequence('kisit_kolon_tanimlari_id_seq')]
    expect(findSequenceForColumn('kisit_kolon_tanimlari', 'id', sequences)?.ad).toBe('kisit_kolon_tanimlari_id_seq')
  })

  it('does not associate an unrelated sequence', () => {
    expect(findSequenceForColumn('kisit_kolon_tanimlari', 'kaynak_id', [sequence('kisit_kolon_tanimlari_id_seq')])).toBeUndefined()
  })
})

describe('findSequencesForTable', () => {
  it('does not pull sequences from similarly named tables into the table card', () => {
    const sequences = [
      sequence('calistirma_id_seq'),
      sequence('calistirma_adimi_id_seq'),
      sequence('calistirma_durumu_id_seq'),
      sequence('calistirma_olayi_id_seq'),
    ]

    expect(findSequencesForTable('calistirma', sequences).map((item) => item.ad)).toEqual(['calistirma_id_seq'])
  })
})
