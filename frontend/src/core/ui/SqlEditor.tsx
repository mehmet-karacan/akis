import { PLSQL, sql } from '@codemirror/lang-sql'
import CodeMirror from '@uiw/react-codemirror'
import { AlertTriangle, CheckCircle2, CircleEllipsis, WandSparkles } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'

export interface SqlEditorError { line: number; column?: number; message: string }

export function SqlEditor({ value, onChange, label, readOnly = false, errors = [], validLabel = 'Valid SQL', invalidLabel = 'SQL needs attention', emptyLabel = 'Enter SQL to validate', formatLabel = 'Format SQL' }: {
  value: string
  onChange: (value: string) => void
  label: string
  readOnly?: boolean
  errors?: SqlEditorError[]
  validLabel?: string
  invalidLabel?: string
  emptyLabel?: string
  formatLabel?: string
}) {
  const extensions = useMemo(() => [sql({ dialect: PLSQL, upperCaseKeywords: true })], [])
  const empty = !value.trim()
  const invalid = errors.length > 0
  const statusLabel = empty ? emptyLabel : invalid ? invalidLabel : validLabel
  const [showErrors, setShowErrors] = useState(invalid)
  useEffect(() => { setShowErrors(invalid) }, [invalid])
  return <div className="ui-sql-editor">
    <div className="ui-sql-validation-bar">
      {!readOnly && !empty ? <button className="ui-sql-format" type="button" onClick={async () => {
        const { format } = await import('sql-formatter')
        onChange(format(value, { language: 'plsql', keywordCase: 'upper', tabWidth: 2 }))
      }}>
        <WandSparkles size={15} aria-hidden="true" /><span>{formatLabel}</span>
      </button> : null}
      <button className={`ui-sql-validation ${empty ? 'is-empty' : invalid ? 'is-invalid' : 'is-valid'}`} type="button" onClick={() => { if (invalid) setShowErrors((current) => !current) }} aria-expanded={invalid ? showErrors : undefined}>
        {empty ? <CircleEllipsis size={15} aria-hidden="true" /> : invalid ? <AlertTriangle size={15} aria-hidden="true" /> : <CheckCircle2 size={15} aria-hidden="true" />}
        <span>{statusLabel}</span>
      </button>
    </div>
    <CodeMirror value={value} onChange={onChange} extensions={extensions} basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: true, highlightSelectionMatches: true, searchKeymap: true }} readOnly={readOnly} minHeight="240px" aria-label={label} />
    {invalid && showErrors ? <ul className="ui-sql-errors" aria-label={`${label} errors`}>{errors.map((error, index) => <li key={`${error.line}:${error.column ?? 0}:${index}`}><span>{error.line}:{error.column ?? 1}</span><span>{error.message}</span></li>)}</ul> : null}
  </div>
}
