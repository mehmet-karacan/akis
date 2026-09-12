export const PROCEDURE_LOG_COUNTERS = [
  'NONE',
  'INSERT',
  'UPDATE',
  'DELETE',
  'STATISTICS',
  'ANALYSIS',
] as const

export type ProcedureLogCounter = (typeof PROCEDURE_LOG_COUNTERS)[number]

export function inferProcedureLogCounter(command: string): ProcedureLogCounter {
  const sql = command.trimStart().toLocaleUpperCase('en-US')
  if (/^INSERT\b/.test(sql)) return 'INSERT'
  if (/^UPDATE\b/.test(sql)) return 'UPDATE'
  if (/^(DELETE|TRUNCATE)\b/.test(sql)) return 'DELETE'
  if (sql.includes('DBMS_STATS.GATHER_')) return 'STATISTICS'
  if (/^(SELECT|WITH|ANALYZE)\b/.test(sql)) return 'ANALYSIS'
  return 'NONE'
}
