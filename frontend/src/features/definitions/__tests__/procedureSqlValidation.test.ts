import { describe, expect, it } from 'vitest'
import { validateProcedureSql } from '../procedureSqlValidation'

describe('validateProcedureSql', () => {
  it('accepts the supported Oracle procedure commands', () => {
    expect(validateProcedureSql('SELECT ID, ACIKLAMA FROM TTBP.HAKEDIS_TIPI', 'SOURCE')).toEqual([])
    expect(validateProcedureSql("BEGIN DBMS_STATS.GATHER_TABLE_STATS('INNOVA_ODI', 'STG_HAKEDIS_TIPI'); END;", 'TARGET')).toEqual([])
  })

  it('finds invalid commas and unbalanced delimiters before save', () => {
    expect(validateProcedureSql('INSERT INTO T (ID, ) VALUES (:ID)', 'TARGET').map((item) => item.code)).toContain('sqlInvalidComma')
    expect(validateProcedureSql('SELECT (ID FROM T', 'SOURCE').map((item) => item.code)).toContain('sqlUnbalancedParenthesis')
    expect(validateProcedureSql("SELECT 'broken FROM T", 'SOURCE').map((item) => item.code)).toContain('sqlUnclosedQuote')
  })

  it('rejects unsupported roles and multiple SQL statements', () => {
    expect(validateProcedureSql('DELETE FROM T', 'SOURCE').map((item) => item.code)).toContain('sqlInvalidSourceCommand')
    expect(validateProcedureSql('DELETE FROM T; DROP TABLE T', 'TARGET').map((item) => item.code)).toContain('sqlMultipleStatements')
  })
})
