import type { DefinitionType, MappingContent } from './types'

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

const defaults: Record<DefinitionType, unknown> = {
  MAPPING: DEFAULT_MAPPING,
  REUSABLE_MAPPING: { inputs: [], outputs: [], nodes: [] },
  PACKAGE: {
    firstStepId: 'STEP_1',
    steps: [{ id: 'STEP_1', type: 'MAPPING' }],
    transitions: [],
  },
  PROCEDURE: {
    tasks: [
      {
        id: 'TASK_1',
        type: 'SQL',
        connectionRole: 'TARGET',
        riskClass: 'READ_ONLY',
        command: 'SELECT 1 FROM DUAL',
      },
    ],
  },
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

