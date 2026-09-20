import { expect, it } from 'vitest'
import { DEFAULT_MAPPING } from './defaults'
import { mappingFlow } from './mappingFlow'
import type { MappingContent, MappingFilter } from './types'

const filter = (id: string, scope: MappingFilter['scope'], object: string): MappingFilter => ({ id, scope, object, predicate: { kind: 'UNARY', operator: 'IS NOT NULL', argument: { kind: 'COLUMN', dataset: object, column: 'ID' } } })
it('uses one data-flow edge independent of the number of column mappings', () => {
  const graph = mappingFlow(DEFAULT_MAPPING, {})
  expect(graph.edges).toHaveLength(1)
  expect(mappingFlow({ ...DEFAULT_MAPPING, columnMappings: [] }, {}).edges).toEqual(graph.edges)
  expect(graph.edges[0]).not.toHaveProperty('sourceHandle')
})
it('places source filters before the join and global filters after the join', () => {
  const value: MappingContent = { ...DEFAULT_MAPPING, sources: [{ id: 'S1', alias: 'ORDERS' }, { id: 'S2', alias: 'CUSTOMERS' }],
    joins: [{ id: 'J', type: 'LEFT', left: { object: 'S1', column: 'CUSTOMER_ID' }, right: { object: 'S2', column: 'ID' } }],
    filters: [filter('GLOBAL', 'GLOBAL', 'S1'), filter('CUSTOMER_ACTIVE', 'SOURCE', 'S2'), filter('TENANT', 'SOURCE', 'S1')] }
  const graph = mappingFlow(value, { S1: 500, S2: 300 })
  expect(graph.edges.map(({ source, target }) => [source, target])).toEqual([
    ['S1', 'flow:filter:TENANT'], ['S2', 'flow:filter:CUSTOMER_ACTIVE'],
    ['flow:filter:TENANT', 'flow:join'], ['flow:filter:CUSTOMER_ACTIVE', 'flow:join'],
    ['flow:join', 'flow:filter:GLOBAL'], ['flow:filter:GLOBAL', value.target.id],
  ])
  expect(graph.operations.find(node => node.kind === 'join')?.incomplete).toBe(false)
  expect(graph.positions.S2!.y).toBeGreaterThan(500)
  expect(mappingFlow({ ...value, joins: [] }, {}).operations.find(node => node.kind === 'join')?.incomplete).toBe(true)
})
it('connects source directly to target when there is a single source and no filters', () => {
  const value: MappingContent = { ...DEFAULT_MAPPING, sources: [{ id: 'S1', alias: 'SRC' }] }
  const graph = mappingFlow(value, {})
  expect(graph.edges).toEqual([{ id: 'flow:S1:TARGET', source: 'S1', target: 'TARGET' }])
  expect(graph.operations).toHaveLength(0)
})
it('orders multiple source filters by definition order within each source branch', () => {
  const value: MappingContent = { ...DEFAULT_MAPPING, sources: [{ id: 'S1', alias: 'SRC' }],
    filters: [filter('SECOND', 'SOURCE', 'S1'), filter('FIRST', 'SOURCE', 'S1')] }
  const graph = mappingFlow(value, {})
  expect(graph.edges.map(({ source, target }) => [source, target])).toEqual([
    ['S1', 'flow:filter:SECOND'], ['flow:filter:SECOND', 'flow:filter:FIRST'], ['flow:filter:FIRST', 'TARGET'],
  ])
})
it('orders global filters by definition order after any join', () => {
  const value: MappingContent = { ...DEFAULT_MAPPING, sources: [{ id: 'S1', alias: 'SRC' }, { id: 'S2', alias: 'OTHER' }],
    joins: [{ id: 'J', type: 'INNER', left: { object: 'S1', column: 'ID' }, right: { object: 'S2', column: 'ID' } }],
    filters: [filter('G2', 'GLOBAL', 'S1'), filter('G1', 'GLOBAL', 'S1')] }
  const graph = mappingFlow(value, {})
  expect(graph.edges.map(({ source, target }) => [source, target])).toEqual([
    ['S1', 'flow:join'], ['S2', 'flow:join'],
    ['flow:join', 'flow:filter:G2'], ['flow:filter:G2', 'flow:filter:G1'], ['flow:filter:G1', 'TARGET'],
  ])
})
it('marks the join incomplete when a source is unreachable through defined joins', () => {
  const value: MappingContent = { ...DEFAULT_MAPPING, sources: [{ id: 'S1', alias: 'A' }, { id: 'S2', alias: 'B' }, { id: 'S3', alias: 'C' }],
    joins: [{ id: 'J', type: 'INNER', left: { object: 'S1', column: 'ID' }, right: { object: 'S2', column: 'ID' } }],
    filters: [] }
  const graph = mappingFlow(value, {})
  expect(graph.operations.find(node => node.kind === 'join')?.incomplete).toBe(true)
})
