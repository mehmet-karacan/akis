import { Grid2X2, List, Table2 } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'

export type CollectionView = 'card' | 'list' | 'table'
export function useCollectionView(key: string) {
  const [view, setView] = useState<CollectionView>(() => {
    try { const saved = localStorage.getItem(key); return saved === 'list' || saved === 'table' ? saved : 'card' } catch { return 'card' }
  })
  return [view, (next: CollectionView) => { setView(next); try { localStorage.setItem(key, next) } catch { /* Storage is optional. */ } }] as const
}
export function ViewToggle({ value, onChange }: { value: CollectionView; onChange(value: CollectionView): void }) {
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  return <div className="ui-view-toggle" role="group" aria-label={tr ? 'Görünüm' : 'View'}>{([{ value: 'card', icon: Grid2X2, label: tr ? 'Kart' : 'Cards' }, { value: 'list', icon: List, label: tr ? 'Liste' : 'List' }, { value: 'table', icon: Table2, label: tr ? 'Tablo' : 'Table' }] as const).map(({ value: mode, icon: Icon, label }) => <button type="button" key={mode} aria-label={label} title={label} aria-pressed={value === mode} onClick={() => onChange(mode)}><Icon size={16} aria-hidden="true" /></button>)}</div>
}
