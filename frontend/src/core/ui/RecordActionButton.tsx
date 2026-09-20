import { Eye, Pencil } from 'lucide-react'
import { Button } from './Button'
import { useTranslation } from 'react-i18next'

export function RecordActionButton({ name, editable = true, onClick }: { name: string; editable?: boolean; onClick(): void }) {
  const { i18n } = useTranslation()
  const tr = i18n.language === 'tr'
  const action = editable ? (tr ? 'Düzenle' : 'Edit') : (tr ? 'Görüntüle' : 'View')
  return <Button type="button" className="ui-record-action" icon={editable ? <Pencil size={16} /> : <Eye size={16} />} aria-label={`${action}: ${name}`} title={action} onClick={onClick} />
}
