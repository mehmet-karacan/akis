import CodeMirror from '@uiw/react-codemirror'
import { autocompletion } from '@codemirror/autocomplete'
import { PLSQL, sql } from '@codemirror/lang-sql'
import { useMemo } from 'react'
import { useResolvedTheme } from '../../core/theme/ThemeContext'
import type { SchemaSnapshotColumn } from '../topology/api'
import type { MappingContent } from './types'
import { mappingColumnCompletions } from './mappingSqlText'

export function MappingExpressionInput({ text, onChange, label, value, columns }: {
  text: string; onChange(text: string): void; label: string; value: MappingContent; columns: Record<string, SchemaSnapshotColumn[]>
}) {
  const theme = useResolvedTheme()
  const extensions = useMemo(() => [sql({ dialect: PLSQL }), autocompletion({ override: [context =>
    mappingColumnCompletions(context.state.doc.toString(), context.pos, value, columns)], activateOnTyping: true })], [value, columns])
  return <div className="mapping-expression-input"><CodeMirror value={text} onChange={onChange} theme={theme} extensions={extensions}
    minHeight="76px" maxHeight="180px" aria-label={label} basicSetup={{ lineNumbers: false, foldGutter: false, highlightActiveLine: false }} /></div>
}
