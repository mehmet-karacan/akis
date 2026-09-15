import { Select as FormSelect } from '../../core/ui/Select'
import { Button as AntActionButton } from '../../core/ui/Button'
import { Input as AntInput } from 'antd'
import { useMemo, useState } from 'react'
import { useDefinitionsI18n } from './i18n'

type Expression = Record<string, unknown>
type ColumnOption = { dataset: string; column: string; label: string }
const FUNCTIONS = ['TRIM', 'UPPER', 'LOWER'] as const

/** The simple editor must never flatten an AST it cannot represent losslessly. */
export function canEditExpression(expression?: Expression): boolean {
  if (!expression) return true
  const exactKeys = (keys: string[]) => Object.keys(expression).every((key) => keys.includes(key))
  if (expression.kind === 'COLUMN') return exactKeys(['kind', 'dataset', 'column']) && typeof expression.dataset === 'string' && typeof expression.column === 'string'
  if (expression.kind === 'LITERAL') return exactKeys(['kind', 'value']) && typeof expression.value === 'string'
  if (expression.kind === 'CALL') return exactKeys(['kind', 'function', 'args']) && FUNCTIONS.some((fn) => fn === expression.function) && Array.isArray(expression.args) && expression.args.length === 1 && expression.args[0]?.kind === 'COLUMN' && canEditExpression(expression.args[0])
  return false
}

export function expressionSummary(expression?: Expression): string | null {
  if (!expression) return ''
  let remaining = 1000
  const summarize = (node: unknown, depth: number): string | null => {
    if (--remaining < 0 || depth > 32 || !node || typeof node !== 'object' || Array.isArray(node)) return null
    const item = node as Expression
    if (item.kind === 'COLUMN') return `${String(item.dataset ?? '')}.${String(item.column ?? '')}`
    if (item.kind === 'LITERAL') return JSON.stringify(item.value) ?? null
    if (item.kind === 'CALL' && Array.isArray(item.args)) {
      const args = item.args.map((arg) => summarize(arg, depth + 1))
      return args.some((arg) => arg === null) ? null : `${String(item.function ?? '')}(${args.join(', ')})`
    }
    return null
  }
  return summarize(expression, 0)
}

function initial(expression: Expression | undefined) {
  const summary = expressionSummary(expression)
  if (summary === null || !canEditExpression(expression)) return { supported: false, kind: 'COLUMN', fn: 'TRIM', dataset: '', column: '', literal: '' }
  const root = expression?.kind === 'CALL' && Array.isArray(expression.args) ? expression.args[0] as Expression : expression
  return { supported: true, kind: String(expression?.kind ?? 'COLUMN'), fn: String(expression?.function ?? 'TRIM'), dataset: String(root?.dataset ?? ''), column: String(root?.column ?? ''), literal: String(expression?.value ?? '') }
}

export function ExpressionBuilder({ value, columns, onApply, onCancel }: { value?: Expression; columns: ColumnOption[]; onApply: (value: Expression) => void; onCancel: () => void }) {
  const { t } = useDefinitionsI18n(); const base = useMemo(() => initial(value), [value]); const [kind, setKind] = useState(base.kind); const [fn, setFn] = useState(base.fn); const [dataset, setDataset] = useState(base.dataset); const [column, setColumn] = useState(base.column); const [literal, setLiteral] = useState(base.literal); const valid = base.supported && (kind === 'LITERAL' || Boolean(dataset && column))
  const apply = () => { const argument = { kind: 'COLUMN', dataset, column }; onApply(kind === 'CALL' ? { kind: 'CALL', function: fn, args: [argument] } : kind === 'LITERAL' ? { kind: 'LITERAL', value: literal } : argument) }
  return <div className="expression-builder"><header><strong>{t('expressionBuilder')}</strong>{!base.supported ? <span role="alert">{t('unsupportedExpression')}</span> : null}</header><div><label><span>{t('expressionKind')}</span><FormSelect value={kind} onChange={(event) => setKind(event.target.value)}><option value="COLUMN">{t('columnReference')}</option><option value="CALL">{t('functionCall')}</option><option value="LITERAL">{t('literalValue')}</option></FormSelect></label>{kind === 'CALL' ? <label><span>{t('function')}</span><FormSelect value={fn} onChange={(event) => setFn(event.target.value)}>{FUNCTIONS.map((item) => <option key={item}>{item}</option>)}</FormSelect></label> : null}{kind !== 'LITERAL' ? <label><span>{t('sourceColumn')}</span><FormSelect value={`${dataset}\0${column}`} onChange={(event) => { const [nextDataset, nextColumn] = event.target.value.split('\0'); setDataset(nextDataset ?? ''); setColumn(nextColumn ?? '') }}><option value="">—</option>{columns.map((item) => <option key={`${item.dataset}:${item.column}`} value={`${item.dataset}\0${item.column}`}>{item.label}</option>)}</FormSelect></label> : <label><span>{t('literalValue')}</span><AntInput value={literal} onChange={(event) => setLiteral(event.target.value)} /></label>}</div><footer><AntActionButton tone="secondary" type="button" onClick={onCancel}>{t('cancel')}</AntActionButton><AntActionButton tone="primary" type="button" disabled={!valid} onClick={apply}>{t('applyExpression')}</AntActionButton></footer></div>
}
