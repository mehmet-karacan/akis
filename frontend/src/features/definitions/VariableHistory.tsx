import { DataGrid } from '../../core/ui/DataGrid'
import { Button as AntActionButton } from '../../core/ui/Button'
import { useEffect, useState } from 'react'
import { apiRequest } from '../../core/api/client'
import { useDefinitionsI18n } from './i18n'

interface Entry { id: number; runUuid: string; environment: string; logicalSchema: string; dataType: string; value: string; createdAt: string }
export function VariableHistory({ projectUuid, definitionUuid }: { projectUuid: string; definitionUuid: string }) {
  const { language } = useDefinitionsI18n()
  const [entries, setEntries] = useState<Entry[]>([])
  const [error, setError] = useState(false)
  const [more, setMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    apiRequest<Entry[]>(`/api/v1/projects/${projectUuid}/definitions/${definitionUuid}/value-history`)
      .then(rows => { if (active) { setEntries(rows); setMore(rows.length === 50); setError(false) } })
      .catch(() => { if (active) setError(true) })
    return () => { active = false }
  }, [projectUuid, definitionUuid, revision])
  const loadMore = async () => {
    setLoading(true)
    try {
      const rows = await apiRequest<Entry[]>(`/api/v1/projects/${projectUuid}/definitions/${definitionUuid}/value-history?before=${entries.at(-1)?.id}`)
      setEntries(previous => [...previous, ...rows]); setMore(rows.length === 50)
    } catch { setError(true) } finally { setLoading(false) }
  }
  return <section className="structured-draft-wide variable-value-history">
    <header><h3>{language === 'tr' ? 'Değer Geçmişi' : 'Value History'}</h3><AntActionButton type="button" tone="secondary" onClick={() => setRevision(value => value + 1)}>{language === 'tr' ? 'Yenile' : 'Refresh'}</AntActionButton></header>
    {error ? <p role="alert">{language === 'tr' ? 'Değer geçmişi yüklenemedi.' : 'Value history could not be loaded.'}</p> : entries.length === 0 ? <p>{language === 'tr' ? 'Henüz kaydedilmiş değer yok. Sorgu çalıştırıldığında geçmiş seçimine göre burada gösterilir.' : 'No recorded values yet. Values appear after execution according to the history setting.'}</p> : <div style={{ overflowX: 'auto' }}><DataGrid><thead><tr>{(language === 'tr' ? ['Değer', 'Ortam', 'Mantıksal Şema', 'Zaman', 'Çalıştırma'] : ['Value', 'Environment', 'Logical Schema', 'Time', 'Run']).map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{entries.map(entry => <tr key={entry.id}><td>{entry.value}</td><td>{entry.environment}</td><td>{entry.logicalSchema}</td><td>{new Date(entry.createdAt).toLocaleString(language)}</td><td><a href={`/project/operations/runs/${entry.runUuid}`}>{entry.runUuid}</a></td></tr>)}</tbody></DataGrid></div>}
    {more && <AntActionButton tone="ghost" type="button" disabled={loading} onClick={() => void loadMore()}>{language === 'tr' ? 'Daha Fazla' : 'Load More'}</AntActionButton>}
  </section>
}
