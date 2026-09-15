import { expect, it } from 'vitest'
import { projectVariableRanges } from './sqlVariableHighlight'
it('marks project variables but not literals, comments or database links', () => {
  const sql = `select @RUN_DATE, '@LITERAL', q'[it's @QUOTED]', "@COLUMN" from t@REMOTE -- @COMMENT\n/* @BLOCK */ where x = @VALUE`
  expect(projectVariableRanges(sql).map(r => sql.slice(r.from, r.to))).toEqual(['@RUN_DATE', '@VALUE'])
})
