import { PLSQL, sql, keywordCompletionSource } from '@codemirror/lang-sql'
import { autocompletion } from '@codemirror/autocomplete'
import CodeMirror from '@uiw/react-codemirror'
import { EditorView } from '@codemirror/view'
import { AlertTriangle, CheckCircle2, CircleEllipsis, WandSparkles } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useResolvedTheme } from '../theme/ThemeContext'
import './SqlEditor.css'
import { projectVariableHighlight } from './sqlVariableHighlight'

import { Button } from './Button'
export interface SqlEditorError { line: number; column?: number; message: string }
const NO_PROJECT_VARIABLES: string[] = []

export function SqlEditor({ value, onChange, label, readOnly = false, wrapLines = false, errors = [], validLabel = 'No basic SQL errors detected', invalidLabel = 'SQL needs attention', emptyLabel = 'Enter SQL to validate', formatLabel = 'Format SQL', validateSql, checkLabel = 'Check SQL', checkedLabel = 'SQL policy checked', toolbar, formatSql, projectVariables = NO_PROJECT_VARIABLES, showToolbar = true }: {
  value: string
  onChange: (value: string) => void
  label: string
  readOnly?: boolean
  wrapLines?: boolean
  errors?: SqlEditorError[]
  validLabel?: string
  invalidLabel?: string
  emptyLabel?: string
  formatLabel?: string
  validateSql?: (sql: string) => Promise<SqlEditorError[]>
  checkLabel?: string
  checkedLabel?: string
  toolbar?: ReactNode
  formatSql?: (value: string) => Promise<string>
  projectVariables?: string[]
  showToolbar?: boolean
}) {
  const extensions = useMemo(() => {
    const language = sql({ dialect: PLSQL, upperCaseKeywords: true })
    return [language, projectVariableHighlight, ...(wrapLines ? [EditorView.lineWrapping] : []), autocompletion({ override: [context => {
      const match = context.matchBefore(/(?<![\w"$#])@[A-Za-z0-9_]*|\$\{[A-Za-z0-9_]*/)
      return match ? { from: match.from, options: projectVariables.map(name => ({ label: '@' + name, type: 'variable' })) } : keywordCompletionSource(PLSQL, true)(context)
    }] })]
  }, [projectVariables, wrapLines])
  const theme = useResolvedTheme()
  const revision = useRef(0)
  const [checking, setChecking] = useState(false)
  const [checked, setChecked] = useState(false)
  const [remoteErrors, setRemoteErrors] = useState<SqlEditorError[]>([])
  useEffect(() => { revision.current++; setChecked(false); setChecking(false); setRemoteErrors([]); return () => { revision.current++ } }, [value, validateSql])
  const allErrors = [...errors, ...remoteErrors]
  const empty = !value.trim()
  const invalid = allErrors.length > 0
  const statusLabel = empty ? emptyLabel : invalid ? invalidLabel : validateSql ? checked ? checkedLabel : checkLabel : validLabel
  const [showErrors, setShowErrors] = useState(invalid)
  useEffect(() => { setShowErrors(invalid) }, [invalid])
  return <div className="ui-sql-editor">
    {showToolbar && <div className="ui-sql-validation-bar">
      {!readOnly && !empty ? <Button className="ui-sql-format" type="button" onClick={async () => {
        const request = revision.current
        try {
          const { format } = await import('sql-formatter')
          const formatted = formatSql ? await formatSql(value) : format(value, { language: 'plsql', keywordCase: 'upper', tabWidth: 2 })
          if (request === revision.current) onChange(formatted)
        } catch (error) {
          if (request !== revision.current) return
          setRemoteErrors([{ line: 1, message: error instanceof Error ? error.message : invalidLabel }]); setShowErrors(true)
        }
      }}>
        <WandSparkles size={15} aria-hidden="true" /><span>{formatLabel}</span>
      </Button> : null}
      <Button className={`ui-sql-validation ${invalid ? 'is-invalid' : empty || (validateSql && !checked) ? 'is-empty' : 'is-valid'}`} type="button" disabled={checking || empty} onClick={async () => {
        if (!validateSql) { if (invalid) setShowErrors((current) => !current); return }
        const request = ++revision.current
        setChecking(true)
        try {
          const diagnostics = await validateSql(value)
          if (request !== revision.current) return
          setRemoteErrors(diagnostics); setChecked(true); setShowErrors(diagnostics.length > 0)
        } catch (error) {
          if (request !== revision.current) return
          setRemoteErrors([{ line: 1, message: error instanceof Error ? error.message : invalidLabel }]); setShowErrors(true)
        } finally { if (request === revision.current) setChecking(false) }
      }} aria-busy={checking} aria-expanded={invalid ? showErrors : undefined}>
        {invalid ? <AlertTriangle size={15} aria-hidden="true" /> : empty || (validateSql && !checked) ? <CircleEllipsis size={15} aria-hidden="true" /> : <CheckCircle2 size={15} aria-hidden="true" />}
        <span>{statusLabel}</span>
      </Button>
      {toolbar}
    </div>}
    <CodeMirror theme={theme} value={value} onChange={onChange} extensions={extensions} basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: !readOnly, highlightActiveLineGutter: !readOnly, highlightSelectionMatches: true, searchKeymap: true }} readOnly={readOnly} width="100%" minHeight="240px" aria-label={label} />
    {invalid && showErrors ? <ul className="ui-sql-errors" aria-label={`${label} errors`}>{allErrors.map((error, index) => <li key={`${error.line}:${error.column ?? 0}:${index}`}><span>{error.line}:{error.column ?? 1}</span><span>{error.message}</span></li>)}</ul> : null}
  </div>
}
