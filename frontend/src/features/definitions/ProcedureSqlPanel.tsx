import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Modal } from 'antd'
import { Copy, Expand, Shrink, SquarePen } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from '../../core/ui/Button'
import { SqlEditor } from '../../core/ui/SqlEditor'
import { apiRequest, jsonBody } from '../../core/api/client'
import { definitionsApi } from './api'
import { compileProjectSql, displayProjectSql } from './projectSqlVariables'
import type { Definition, ProcedureTask } from './types'

type Props = { projectUuid: string; role: string; task?: ProcedureTask; variables: Definition[]; onApply(command: string, parameters: ProcedureTask['parameters']): void }
export function ProcedureSqlPanel({ projectUuid, role, task, variables, onApply }: Props) {
  const [open, setOpen] = useState(false)
  const [panelSnapshot, setPanelSnapshot] = useState('')
  const close = () => setOpen(false)
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const existing = Object.entries(task?.parameters ?? {}).filter(([, value]) => value.definitionUuid).map(([name]) => name)
  const [value, setValue] = useState(() => displayProjectSql(task?.command ?? '', existing))
  const [expanded, setExpanded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState(false)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const options = variables.filter(v => /^[A-Z_][A-Z0-9_]*$/i.test(v.code))
  const names = [...new Set([...existing, ...options.map(v => v.code.toUpperCase())])]
  const validate = useCallback(async (text: string) => {
    const compiled = compileProjectSql(text, [...existing, ...variables.map(v => v.code.toUpperCase())])
    await apiRequest(`/api/v1/projects/${encodeURIComponent(projectUuid)}/sql/validate`, { method: 'POST', ...jsonBody({ command: compiled.command, connectionRole: role }) })
    return []
  }, [projectUuid, role, task, variables])
  async function apply() {
    setBusy(true); setError('')
    try {
      const compiled = compileProjectSql(value, names)
      const parameters = { ...task?.parameters }
      for (const name of compiled.names) {
        const variable = options.find(v => v.code.toUpperCase() === name)
        if (!variable) throw new Error(tr ? `Değişken bulunamadı: ${name}` : `Variable not found: ${name}`)
        const draft = await definitionsApi.getDraft(projectUuid, variable.uuid)
        const content = draft?.content as Record<string, unknown> | undefined
        const type = String(content?.dataType ?? '')
        if (content?.valueSource !== 'REFRESH_QUERY' || typeof content.query !== 'string' || !content.query.trim() || !content.logicalSchemaUuid || !['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP'].includes(type)) throw new Error(tr ? `${name}: sorgu, veri tipi ve mantıksal şema tanımı gerekli.` : `${name}: query, data type and logical schema are required.`)
        parameters[name] = { type: type as NonNullable<ProcedureTask['parameters']>[string]['type'], valueSource: 'REFRESH_QUERY', query: content.query, definitionUuid: variable.uuid, logicalSchemaUuid: String(content.logicalSchemaUuid), historyMode: (content.historyMode ?? 'LATEST') as 'NONE' | 'LATEST' | 'ALL' }
      }
      for (const name of existing) if (!compiled.names.includes(name)) delete parameters[name]
      if (mounted.current) { onApply(compiled.command, parameters); close() }
    } catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : String(e)) }
    finally { if (mounted.current) setBusy(false) }
  }
  const content = <>
    <p className="sql-variable-hint">{tr ? 'Proje değişkeni için @ yazın; öneriden seçin. :ID gibi alanlar kaynak satırından gelir.' : 'Type @ to select a project variable. Fields such as :ID come from the source row.'}</p>
    {error && <Alert type="error" showIcon title={error} />}
    <SqlEditor showToolbar={open} label={tr ? 'SQL Düzenleyici' : 'SQL Editor'} value={value} onChange={next => { setValue(next); setCopied(false); setError('') }} projectVariables={names} validateSql={validate} checkLabel={tr ? 'SQL’i Kontrol Et' : 'Check SQL'} checkedLabel={tr ? 'SQL politikası kontrol edildi' : 'SQL policy checked'} formatLabel={tr ? 'Biçimlendir' : 'Format'} formatSql={async text => { const compiled = compileProjectSql(text, names); const { format } = await import('sql-formatter'); return displayProjectSql(format(compiled.command, { language: 'plsql', keywordCase: 'upper', tabWidth: 2 }), compiled.names) }} toolbar={<><Button onClick={async () => { try { await navigator.clipboard.writeText(value); setCopied(true) } catch { setError(tr ? 'Panoya kopyalanamadı.' : 'Could not copy to clipboard.') } }} icon={<Copy size={16} />}>{copied ? (tr ? 'Kopyalandı' : 'Copied') : (tr ? 'Kopyala' : 'Copy')}</Button><Button onClick={() => { if (!open) { setPanelSnapshot(value); setOpen(true); setExpanded(true) } else setExpanded(!expanded) }} icon={expanded ? <Shrink size={16} /> : <Expand size={16} />}>{expanded ? (tr ? 'Küçült' : 'Restore') : (tr ? 'Büyüt' : 'Expand')}</Button></>} />
  </>
  const footer = <><span className="sql-apply-hint">{tr ? 'Uygula adıma aktarır. Kalıcı kayıt için prosedürü kaydedin.' : 'Apply updates the step. Save the procedure to persist it.'}</span><Button disabled={busy} onClick={() => { setValue(open ? panelSnapshot : displayProjectSql(task?.command ?? '', existing)); setError(''); close() }}>{tr ? 'İptal' : 'Cancel'}</Button><Button tone="primary" disabled={busy} onClick={() => void apply()}>{busy ? (tr ? 'Uygulanıyor…' : 'Applying…') : (tr ? 'Uygula' : 'Apply')}</Button></>
  return <div className="procedure-sql-inline">
    <header><strong>{role === 'SOURCE' ? (tr ? 'Kaynak SQL' : 'Source SQL') : (tr ? 'Hedef SQL' : 'Target SQL')}</strong><Button icon={<SquarePen size={16} />} onClick={() => { setPanelSnapshot(value); setOpen(true) }}>{tr ? 'SQL Düzenle' : 'Edit SQL'}</Button></header>
    {!open && <>{content}<footer>{footer}</footer></>}
    {open && <Modal open centered width={expanded ? 'calc(100vw - 32px)' : 'min(1200px, calc(100vw - 32px))'} className={`procedure-sql-modal ${expanded ? 'is-expanded' : ''}`} title={`${task?.name ?? ''} · ${role === 'SOURCE' ? (tr ? 'Kaynak SQL' : 'Source SQL') : (tr ? 'Hedef SQL' : 'Target SQL')}`} onCancel={() => { if (!busy) { setValue(panelSnapshot); setError(''); close() } }} mask={{ closable: false }} keyboard={!busy} footer={footer}>{open && content}</Modal>}
  </div>
}
