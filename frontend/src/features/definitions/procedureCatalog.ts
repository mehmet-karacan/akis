export const PROCEDURE_LOG_COUNTERS = [
  'NONE',
  'INSERT',
  'UPDATE',
  'DELETE',
  'ERRORS',
] as const

export type ProcedureLogCounter = (typeof PROCEDURE_LOG_COUNTERS)[number]

export function inferProcedureLogCounter(command: string): ProcedureLogCounter {
  const sql = command.trimStart().toLocaleUpperCase('en-US')
  if (/^INSERT\b/.test(sql)) return 'INSERT'
  if (/^UPDATE\b/.test(sql)) return 'UPDATE'
  if (/^DELETE\b/.test(sql)) return 'DELETE'
  return 'NONE'
}

export function normalizeProcedureLogCounter(value: string | undefined): ProcedureLogCounter {
  if (value === 'ANALYSIS' || value === 'STATISTICS') return 'NONE'
  return PROCEDURE_LOG_COUNTERS.includes(value as ProcedureLogCounter)
    ? value as ProcedureLogCounter
    : 'NONE'
}
