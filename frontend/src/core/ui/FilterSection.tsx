import { useId, useState, type ReactNode } from 'react'
import { ChevronDown, ChevronUp, ListFilter } from 'lucide-react'
import { useTranslation } from 'react-i18next'

export function FilterSection({ children }: { children: ReactNode }) {
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const id = useId()
  const [open, setOpen] = useState(() => typeof window === 'undefined' || !window.matchMedia || window.matchMedia('(min-width: 760px)').matches)
  return <section className="workspace-filter-section"><header><strong><ListFilter size={18} />{tr ? 'Arama Filtreleri' : 'Search Filters'}</strong><button type="button" aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}>{open ? (tr ? 'Filtreleri Gizle' : 'Hide Filters') : (tr ? 'Filtreleri Göster' : 'Show Filters')}{open ? <ChevronUp size={16} /> : <ChevronDown size={16} />}</button></header><div id={id} hidden={!open}>{children}</div></section>
}
