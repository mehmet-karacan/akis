import type { ProcedureConnectionRole } from './types'

export type ProcedureSqlIssueCode =
  | 'sqlUnclosedQuote'
  | 'sqlUnclosedComment'
  | 'sqlUnbalancedParenthesis'
  | 'sqlInvalidComma'
  | 'sqlMultipleStatements'
  | 'sqlInvalidSourceCommand'
  | 'sqlInvalidTargetCommand'

export interface ProcedureSqlIssue { line: number; column: number; code: ProcedureSqlIssueCode }

function location(source: string, offset: number) {
  const rows = source.slice(0, Math.max(0, offset)).split('\n')
  return { line: rows.length, column: (rows.at(-1)?.length ?? 0) + 1 }
}

/** Fast guard for common editor mistakes; the backend repeats these checks. */
export function validateProcedureSql(command: string, role: ProcedureConnectionRole): ProcedureSqlIssue[] {
  if (!command.trim()) return []
  const sanitized = [...command]
  let quote: "'" | '"' | null = null
  let blockComment = false
  let lineComment = false
  const parentheses: number[] = []
  const issues: ProcedureSqlIssue[] = []
  for (let index = 0; index < command.length; index += 1) {
    const current = command[index]!
    const next = command[index + 1]
    if (lineComment) {
      if (current === '\n') lineComment = false
      else sanitized[index] = ' '
      continue
    }
    if (blockComment) {
      sanitized[index] = current === '\n' ? '\n' : ' '
      if (current === '*' && next === '/') { sanitized[index + 1] = ' '; blockComment = false; index += 1 }
      continue
    }
    if (quote) {
      sanitized[index] = current === '\n' ? '\n' : ' '
      if (current === quote && next === quote) { sanitized[index + 1] = ' '; index += 1 }
      else if (current === quote) quote = null
      continue
    }
    if (current === '-' && next === '-') { sanitized[index] = sanitized[index + 1] = ' '; lineComment = true; index += 1 }
    else if (current === '/' && next === '*') { sanitized[index] = sanitized[index + 1] = ' '; blockComment = true; index += 1 }
    else if (current === "'" || current === '"') { sanitized[index] = 'x'; quote = current }
    else if (current === '(') parentheses.push(index)
    else if (current === ')') {
      if (parentheses.length) parentheses.pop()
      else issues.push({ ...location(command, index), code: 'sqlUnbalancedParenthesis' })
    }
  }
  if (quote) issues.push({ ...location(command, command.length), code: 'sqlUnclosedQuote' })
  if (blockComment) issues.push({ ...location(command, command.length), code: 'sqlUnclosedComment' })
  if (parentheses.length) issues.push({ ...location(command, parentheses.at(-1)!), code: 'sqlUnbalancedParenthesis' })
  const normalized = sanitized.join('')
  const comma = /,\s*[,)]|\(\s*,/.exec(normalized)
  if (comma) issues.push({ ...location(command, comma.index), code: 'sqlInvalidComma' })
  const keyword = normalized.trimStart().match(/^([A-Za-z]+)/)?.[1]?.toUpperCase()
  const sourceKeywords = new Set(['SELECT'])
  const targetKeywords = new Set(['INSERT', 'UPDATE', 'DELETE', 'MERGE', 'TRUNCATE', 'BEGIN', 'DECLARE', 'CALL', 'CREATE', 'ALTER', 'DROP', 'GRANT', 'REVOKE'])
  if (role === 'SOURCE' && (!keyword || !sourceKeywords.has(keyword))) issues.push({ line: 1, column: 1, code: 'sqlInvalidSourceCommand' })
  if (role === 'TARGET' && (!keyword || !targetKeywords.has(keyword))) issues.push({ line: 1, column: 1, code: 'sqlInvalidTargetCommand' })
  if (!['BEGIN', 'DECLARE'].includes(keyword ?? '')) {
    const terminators = [...normalized.matchAll(/;/g)]
    if (terminators.some((match) => normalized.slice((match.index ?? 0) + 1).trim())) {
      issues.push({ ...location(command, terminators[0]?.index ?? 0), code: 'sqlMultipleStatements' })
    }
  }
  return issues
}
