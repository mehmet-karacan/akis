import type { ReactNode } from 'react'
import { Dialog } from './Dialog'

export function RecordDetailDialog({ open, title, children, onClose, busy = false, readOnly = false, className }: { open: boolean; title: ReactNode; children: ReactNode; onClose(): void; busy?: boolean; readOnly?: boolean; className?: string }) {
  return <Dialog open={open} title={title} closeLabel="Kapat" busy={busy} onClose={onClose} className={className}><div className="ui-record-detail" data-read-only={readOnly || undefined}>{children}</div></Dialog>
}
