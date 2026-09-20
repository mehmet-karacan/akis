import { expect, it } from 'vitest'
import { columnSize } from './columnPresentation'
import type { SchemaSnapshotColumn } from './api'

const column: SchemaSnapshotColumn = { reference: 'VALUE', producerType: 'NUMBER', canonicalType: 'DECIMAL', ordinal: 1, nullable: true }
it('shows database precision rather than a driver storage length', () => {
  expect(columnSize({ ...column, precision: 19, scale: 0, length: 22 })).toBe('19, 0')
  expect(columnSize({ ...column, precision: 10, scale: -2 })).toBe('10, -2')
  expect(columnSize({ ...column, precision: 5 })).toBe('5')
})
it('preserves temporal zero precision and character lengths without technical prefixes', () => {
  expect(columnSize({ ...column, timePrecision: 0, length: 11 })).toBe('0')
  expect(columnSize({ ...column, timePrecision: 6 })).toBe('6')
  expect(columnSize({ ...column, length: 255 })).toBe('255')
  expect(columnSize(column)).toBe('')
})
