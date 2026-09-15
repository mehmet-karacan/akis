import { expect, it } from 'vitest'
import { compileProjectSql, displayProjectSql } from '../projectSqlVariables'
it('compiles project placeholders while preserving row binds', () => {
  expect(compileProjectSql('select :ID from t where d >= ${RUN_DATE}', ['RUN_DATE'])).toEqual({ command: 'select :ID from t where d >= :RUN_DATE', names: ['RUN_DATE'] })
  expect(displayProjectSql('select :ID, :RUN_DATE from dual', ['RUN_DATE'])).toBe('select :ID, @RUN_DATE from dual')
})
it('does not substitute literal, identifier or comment text', () => {
  const sql = `select '\${RUN_DATE}', q'[it's \${RUN_DATE}]', "\${RUN_DATE}" from t -- \${RUN_DATE}\n/* \${RUN_DATE} */ where d = \${RUN_DATE}`
  expect(compileProjectSql(sql, ['RUN_DATE']).command).toBe(sql.replace('where d = ${RUN_DATE}', 'where d = :RUN_DATE'))
})
it('rejects unrecognized project references', () => {
  expect(() => compileProjectSql('select ${MISSING} from dual', [])).toThrow('Unknown project variable')
})
it('rejects ambiguous row and project variable names', () => {
  expect(() => compileProjectSql('select :ID, ${ID} from dual', ['ID'])).toThrow('same name')
})
it('rejects an unfinished variable placeholder', () => {
  expect(() => compileProjectSql('select ${RUN_DATE', ['RUN_DATE'])).toThrow('Incomplete')
})
it('supports simple at-variables without changing Oracle database links', () => {
  expect(compileProjectSql('select :ID from t@REMOTE where d = @RUN_DATE', ['RUN_DATE'])).toEqual({ command: 'select :ID from t@REMOTE where d = :RUN_DATE', names: ['RUN_DATE'] })
})
