import { useState, type ReactNode } from 'react'
import { Input } from 'antd'
import { Search, RotateCcw } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from './Button'
import { FilterSection } from './FilterSection'
import './records.css'

export function QueryFilter({ onApply, placeholder, children, onReset }: { onApply: (query: string) => void; placeholder: string; children?: ReactNode; onReset?: () => void }) {
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [draft, setDraft] = useState('')
  return <FilterSection><form className="ui-query-form" onSubmit={(event) => { event.preventDefault(); onApply(draft.trim()) }}>
    <div className="ui-query-fields"><label><span className="ui-query-label"><Search size={15} aria-hidden="true" />{tr ? 'Ad veya Kod' : 'Name or Code'}</span><Input type="search" value={draft} onChange={(event) => setDraft(event.target.value)} placeholder={placeholder} /></label>{children}</div>
    <div className="ui-query-actions"><Button type="button" icon={<RotateCcw size={16} />} onClick={() => { setDraft(''); onApply(''); onReset?.() }}>{tr ? 'Temizle' : 'Clear'}</Button><Button type="submit" tone="primary" icon={<Search size={16} />}>{tr ? 'Sorgula' : 'Search'}</Button></div>
  </form></FilterSection>
}
