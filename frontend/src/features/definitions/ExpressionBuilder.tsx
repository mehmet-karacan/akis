import { useMemo, useState } from 'react'
import { useDefinitionsI18n } from './i18n'

type Expression = Record<string, unknown>
type ColumnOption = { dataset: string; column: string; label: string }
const FUNCTIONS = ['TRIM', 'UPPER', 'LOWER', 'COALESCE'] as const

export function expressionSummary(expression?: Expression): string | null {
  if (!expression) return ''
  if (expression.kind === 'COLUMN') return `${String(expression.dataset ?? '')}.${String(expression.column ?? '')}`
  if (expression.kind === 'LITERAL') return JSON.stringify(expression.value ?? '')
  if (expression.kind === 'CALL' && Array.isArray(expression.args)) return `${String(expression.function ?? '')}(${expression.args.map((arg) => expressionSummary(arg as Expression)).join(', ')})`
  return null
}

function initial(expression: Expression | undefined) {
  const summary = expressionSummary(expression)
  if (summary === null) return { supported: false, kind: 'COLUMN', fn: 'TRIM', dataset: '', column: '', literal: '' }
  const root = expression?.kind === 'CALL' && Array.isArray(expression.args) ? expression.args[0] as Expression : expression
  return { supported: true, kind: String(expression?.kind ?? 'COLUMN'), fn: String(expression?.function ?? 'TRIM'), dataset: String(root?.dataset ?? ''), column: String(root?.column ?? ''), literal: String(expression?.value ?? '') }
}

export function ExpressionBuilder({ value, columns, onApply, onCancel }: { value?: Expression; columns: ColumnOption[]; onApply: (value: Expression) => void; onCancel: () => void }) {
  const { t } = useDefinitionsI18n(); const base = useMemo(() => initial(value), [value]); const [kind, setKind] = useState(base.kind); const [fn, setFn] = useState(base.fn); const [dataset, setDataset] = useState(base.dataset); const [column, setColumn] = useState(base.column); const [literal, setLiteral] = useState(base.literal); const valid = kind === 'LITERAL' || Boolean(dataset && column)
  const apply = () => { const argument = { kind: 'COLUMN', dataset, column }; onApply(kind === 'CALL' ? { kind: 'CALL', function: fn, args: [argument] } : kind === 'LITERAL' ? { kind: 'LITERAL', value: literal } : argument) }
  return <div className="expression-builder"><header><strong>{t('expressionBuilder')}</strong>{!base.supported ? <span role="alert">{t('unsupportedExpression')}</span> : null}</header><div><label><span>{t('expressionKind')}</span><select value={kind} onChange={(event) => setKind(event.target.value)}><option value="COLUMN">{t('columnReference')}</option><option value="CALL">{t('functionCall')}</option><option value="LITERAL">{t('literalValue')}</option></select></label>{kind === 'CALL' ? <label><span>{t('function')}</span><select value={fn} onChange={(event) => setFn(event.target.value)}>{FUNCTIONS.map((item) => <option key={item}>{item}</option>)}</select></label> : null}{kind !== 'LITERAL' ? <label><span>{t('sourceColumn')}</span><select value={`${dataset}\0${column}`} onChange={(event) => { const [nextDataset, nextColumn] = event.target.value.split('\0'); setDataset(nextDataset ?? ''); setColumn(nextColumn ?? '') }}><option value="">—</option>{columns.map((item) => <option key={`${item.dataset}:${item.column}`} value={`${item.dataset}\0${item.column}`}>{item.label}</option>)}</select></label> : <label><span>{t('literalValue')}</span><input value={literal} onChange={(event) => setLiteral(event.target.value)} /></label>}</div><footer><button className="definition-button definition-button--quiet" type="button" onClick={onCancel}>{t('cancel')}</button><button className="definition-button definition-button--primary" type="button" disabled={!valid} onClick={apply}>{t('applyExpression')}</button></footer></div>
}
