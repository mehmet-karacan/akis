import type { DefinitionType, MappingContent, ProcedureContent } from './types'

export const DEFAULT_MAPPING: MappingContent = {
  datasets: [
    { id: 'SOURCE_1', role: 'SOURCE', name: 'Source' },
    { id: 'TARGET_1', role: 'TARGET', name: 'Target' },
  ],
  columnMappings: [
    {
      source: { dataset: 'SOURCE_1', column: 'ID' },
      target: { dataset: 'TARGET_1', column: 'ID' },
    },
  ],
  writeStrategy: { kind: 'APPEND' },
}

export const DEFAULT_PROCEDURE: ProcedureContent = {
  tasks: [
    {
      id: 'READ_SOURCE',
      name: 'Read source rows',
      type: 'SQL',
      connectionRole: 'SOURCE',
      riskClass: 'READ_ONLY',
      command: 'SELECT ID FROM SOURCE_TABLE',
      onError: 'STOP',
      timeoutSeconds: 300,
      output: { kind: 'ROWSET', maxRows: 10000 },
    },
    {
      id: 'WRITE_TARGET',
      name: 'Insert target rows',
      type: 'SQL',
      connectionRole: 'TARGET',
      riskClass: 'DML',
      command: 'INSERT INTO TARGET_TABLE (ID) VALUES (:ID)',
      onError: 'STOP',
      timeoutSeconds: 300,
      input: { fromTask: 'READ_SOURCE', mode: 'BATCH', batchSize: 250 },
    },
  ],
}

const defaults: Record<DefinitionType, unknown> = {
  MAPPING: DEFAULT_MAPPING,
  REUSABLE_MAPPING: { inputs: [], outputs: [], nodes: [] },
  PACKAGE: {
    firstStepId: 'STEP_1',
    steps: [{ id: 'STEP_1', type: 'MAPPING' }],
    transitions: [],
  },
  PROCEDURE: DEFAULT_PROCEDURE,
  VARIABLE: {
    dataType: 'STRING',
    scope: 'PROJECT',
    historyMode: 'LATEST',
    valueSource: 'INPUT',
  },
  SEQUENCE: { implementation: 'REPOSITORY', start: 1, increment: 1, cycle: false },
  USER_FUNCTION: { returnType: 'STRING', parameters: [], implementations: [] },
  KNOWLEDGE_MODULE: { kmType: 'IKM', tasks: [], options: [] },
  LOAD_PLAN: {
    restartPolicy: 'FAILED_STEP',
    steps: [
      {
        id: 'SERIAL_1',
        type: 'SERIAL',
        steps: [{ id: 'SCENARIO_1', type: 'SCENARIO', scenarioVersionUuid: '' }],
      },
    ],
  },
}

export function createDefaultContent(type: DefinitionType): unknown {
  return structuredClone(defaults[type])
}

export function isMappingContent(value: unknown): value is MappingContent {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const candidate = value as Partial<MappingContent>
  return (
    Array.isArray(candidate.datasets) &&
    Array.isArray(candidate.columnMappings) &&
    !!candidate.writeStrategy &&
    typeof candidate.writeStrategy === 'object'
  )
}

export function isProcedureContent(value: unknown): value is ProcedureContent {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  return Array.isArray((value as Partial<ProcedureContent>).tasks)
}
