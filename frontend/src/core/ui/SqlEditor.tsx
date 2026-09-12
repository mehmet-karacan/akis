import { PLSQL, sql } from '@codemirror/lang-sql'
import CodeMirror from '@uiw/react-codemirror'
import { useMemo } from 'react'

export interface SqlEditorError { line: number; column?: number; message: string }

export function SqlEditor({ value, onChange, label, readOnly = false, errors = [] }: {
  value: string
  onChange: (value: string) => void
  label: string
  readOnly?: boolean
  errors?: SqlEditorError[]
}) {
  const extensions = useMemo(() => [sql({ dialect: PLSQL, upperCaseKeywords: true })], [])
  return <div className="ui-sql-editor">
    <CodeMirror value={value} onChange={onChange} extensions={extensions} basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: true, highlightSelectionMatches: true, searchKeymap: true }} readOnly={readOnly} minHeight="240px" aria-label={label} />
    {errors.length > 0 ? <ul className="ui-sql-errors" aria-label={`${label} errors`}>{errors.map((error, index) => <li key={`${error.line}:${error.column ?? 0}:${index}`}><span>{error.line}:{error.column ?? 1}</span><span>{error.message}</span></li>)}</ul> : null}
  </div>
}
