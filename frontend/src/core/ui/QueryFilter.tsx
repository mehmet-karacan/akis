import { useState } from 'react'
import { Search, RotateCcw } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from './Button'
import { FilterSection } from './FilterSection'
import './records.css'

export function QueryFilter({ onApply, placeholder }: { onApply: (query: string) => void; placeholder: string }) {
  const { i18n } = useTranslation()
  const tr = i18n.language.startsWith('tr')
  const [draft, setDraft] = useState('')
  return <FilterSection><form className="ui-query-form" onSubmit={(event) => { event.preventDefault(); onApply(draft.trim()) }}>
    <label><span>{tr ? 'Ad veya Kod' : 'Name or Code'}</span><input type="search" value={draft} onChange={(event) => setDraft(event.target.value)} placeholder={placeholder} /></label>
    <div className="ui-query-actions"><Button type="button" icon={<RotateCcw size={16} />} onClick={() => { setDraft(''); onApply('') }}>{tr ? 'Temizle' : 'Clear'}</Button><Button type="submit" tone="primary" icon={<Search size={16} />}>{tr ? 'Sorgula' : 'Search'}</Button></div>
  </form></FilterSection>
}
