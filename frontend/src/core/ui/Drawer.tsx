import { X } from 'lucide-react'
import { useEffect, useId, useRef, type ReactNode } from 'react'

interface DrawerProps {
  open: boolean
  title: string
  closeLabel: string
  children: ReactNode
  onClose: () => void
  className?: string
  closeButtonClassName?: string
  busy?: boolean
}

const focusableSelector = [
  'a[href]', 'button:not([disabled])', 'input:not([disabled])',
  'select:not([disabled])', 'textarea:not([disabled])', '[tabindex]:not([tabindex="-1"])',
].join(',')

export function Drawer({ open, title, closeLabel, children, onClose, className = 'drawer', closeButtonClassName = 'icon-button', busy = false }: DrawerProps) {
  const titleId = useId()
  const panelRef = useRef<HTMLElement>(null)
  const returnFocusRef = useRef<HTMLElement | null>(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose

  useEffect(() => {
    if (!open) return
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const panel = panelRef.current
    ;(panel?.querySelector<HTMLElement>(focusableSelector) ?? panel)?.focus()

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !busy) {
        event.preventDefault()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab' || !panel) return
      const items = [...panel.querySelectorAll<HTMLElement>(focusableSelector)]
      if (items.length === 0) {
        event.preventDefault()
        panel.focus()
        return
      }
      const first = items[0]!
      const last = items[items.length - 1]!
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      returnFocusRef.current?.focus()
    }
  }, [busy, open])

  if (!open) return null

  return (
    <div className={className} role="presentation">
      <div className={`${className}-backdrop`} aria-hidden="true" onClick={() => { if (!busy) onClose() }} />
      <section ref={panelRef} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <header><h2 id={titleId}>{title}</h2><button type="button" className={closeButtonClassName} onClick={onClose} disabled={busy} aria-label={closeLabel}><X aria-hidden="true" /></button></header>
        {children}
      </section>
    </div>
  )
}
