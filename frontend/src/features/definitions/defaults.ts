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
      logCounter: 'ANALYSIS',
      onError: 'STOP',
      timeoutSeconds: 300,
      output: { kind: 'ROWSET', maxRows: 1000 },
    },
    {
      id: 'WRITE_TARGET',
      name: 'Insert target rows',
      type: 'SQL',
      connectionRole: 'TARGET',
      riskClass: 'DML',
      command: 'INSERT INTO TARGET_TABLE (ID) VALUES (:ID)',
      logCounter: 'INSERT',
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
    firstStepId: '',
    steps: [],
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

export function supportsVisualEditor(type: DefinitionType, schemaVersion: number): boolean {
  return ['MAPPING', 'VARIABLE', 'SEQUENCE', 'PACKAGE'].includes(type) || (type === 'PROCEDURE' && schemaVersion === 2)
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
  const tasks = (value as Partial<ProcedureContent>).tasks
  if (!Array.isArray(tasks)) return false
  return tasks.every((task) => {
    if (!task || typeof task !== 'object' || Array.isArray(task)) return false
    const candidate = task as unknown as Record<string, unknown>
    if (
      typeof candidate.id !== 'string' ||
      typeof candidate.command !== 'string' ||
      !['SQL', 'PLSQL', 'STORED_PROCEDURE'].includes(String(candidate.type)) ||
      !['SOURCE', 'TARGET'].includes(String(candidate.connectionRole)) ||
      !['READ_ONLY', 'DML', 'DDL', 'DESTRUCTIVE'].includes(String(candidate.riskClass)) ||
      (candidate.logCounter !== undefined &&
        !['NONE', 'INSERT', 'UPDATE', 'DELETE', 'STATISTICS', 'ANALYSIS'].includes(String(candidate.logCounter)))
    ) return false
    if (candidate.output !== undefined) {
      if (!candidate.output || typeof candidate.output !== 'object' || Array.isArray(candidate.output)) return false
      const output = candidate.output as Record<string, unknown>
      if (output.kind !== 'ROWSET' || typeof output.maxRows !== 'number') return false
    }
    if (candidate.input !== undefined) {
      if (!candidate.input || typeof candidate.input !== 'object' || Array.isArray(candidate.input)) return false
      const input = candidate.input as Record<string, unknown>
      if (typeof input.fromTask !== 'string' || input.mode !== 'BATCH' || typeof input.batchSize !== 'number') return false
    }
    return true
  })
}
