import { Grid2X2, Table2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Segmented, Tooltip } from 'antd'

export type CollectionView = 'card' | 'table'
export function useCollectionView(key: string) {
  const [view, setView] = useState<CollectionView>(() => {
    try { const saved = localStorage.getItem(key); return saved === 'table' ? 'table' : 'card' } catch { return 'card' }
  })
  useEffect(() => { try { localStorage.setItem(key, view) } catch { /* Storage is optional. */ } }, [key, view])
  return [view, (next: CollectionView) => { setView(next); try { localStorage.setItem(key, next) } catch { /* Storage is optional. */ } }] as const
}
export function ViewToggle({ value, onChange }: { value: CollectionView; onChange(value: CollectionView): void }) {
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  return <Segmented className="ui-view-toggle" aria-label={tr ? 'Görünüm' : 'View'} value={value} onChange={onChange} options={([{ value: 'card', icon: Grid2X2, label: tr ? 'Kart' : 'Cards' }, { value: 'table', icon: Table2, label: tr ? 'Tablo' : 'Table' }] as const).map(({ value: mode, icon: Icon, label }) => ({ value: mode, label: <Tooltip title={label}><span className="view-mode-icon"><Icon size={16} aria-hidden="true" /><span className="sr-only">{label}</span></span></Tooltip> }))} />
}
