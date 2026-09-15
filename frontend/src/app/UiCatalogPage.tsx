import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Database, Folder, Table2, Eye, History, Plus } from 'lucide-react'
import { Button, Dialog, PageHeader, WorkspaceSection } from '../core/ui'
import { RecordFields } from '../core/ui/RecordFields'
import { ViewToggle, useCollectionView } from '../core/ui/ViewToggle'

export function UiCatalogPage() {
  const { i18n, t } = useTranslation()
  const tr = i18n.language === 'tr'
  const [open, setOpen] = useState(false)
  const [view, setView] = useCollectionView('akis:ui-catalog:view')
  return <div className="page-stack"><PageHeader title={tr ? 'Arayüz Bileşenleri' : 'UI Components'} description={tr ? 'Uygulamada kullanılan ortak bileşenlerin kontrol ekranı. Örnekler veri kaydetmez.' : 'Reference screen for shared application components. Examples do not save data.'} /><WorkspaceSection title={tr ? 'İkon ve Metin' : 'Icons and Text'} icon={<Eye size={18} />}><div className="ui-catalog-icons">{[[Folder, tr ? 'Klasör' : 'Folder'], [Database, tr ? 'Model' : 'Model'], [Table2, tr ? 'Tablo' : 'Table'], [Eye, tr ? 'Görünüm' : 'View'], [History, tr ? 'Çalıştırma Geçmişi' : 'Run History']].map(([Icon, label]) => { const Component = Icon as typeof Folder; return <span key={String(label)}><Component size={18} />{String(label)}</span> })}</div></WorkspaceSection><WorkspaceSection title={tr ? 'Görünüm Seçimi' : 'View Selection'}><ViewToggle value={view} onChange={setView} /></WorkspaceSection><WorkspaceSection title={tr ? 'İşlemler' : 'Actions'}><div className="ui-page-actions"><Button tone="primary" icon={<Plus size={16} />} onClick={() => setOpen(true)}>{tr ? 'Diyalog Aç' : 'Open Dialog'}</Button><Button disabled>{tr ? 'Devre Dışı' : 'Disabled'}</Button></div></WorkspaceSection><WorkspaceSection title={tr ? 'Kayıt Alanları' : 'Record Fields'}><RecordFields fields={[{ icon: <Database />, label: tr ? 'Sunucu' : 'Host', value: 'database.example' }, { icon: <History />, label: tr ? 'Test Durumu' : 'Test Status', value: tr ? 'Son Test Başarılı' : 'Last Test Passed', tone: 'success' }]} /></WorkspaceSection><Dialog open={open} title={tr ? 'Merkezî Diyalog' : 'Centered Dialog'} closeLabel={t('common.close')} onClose={() => setOpen(false)}><p>{tr ? 'Klavye odağı, Escape ile kapatma ve dar ekran sınırları ortak bileşenden gelir.' : 'Keyboard focus, Escape handling and responsive bounds come from the shared component.'}</p></Dialog></div>
}
