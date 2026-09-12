import { X } from 'lucide-react'
import { useEffect, useId, useRef, type MouseEvent, type ReactNode } from 'react'

interface DialogProps {
  open: boolean
  title: string
  eyebrow?: string
  children: ReactNode
  onClose: () => void
  closeLabel: string
  className?: string
  backdropClassName?: string
  busy?: boolean
}

const focusableSelector = [
  'a[href]', 'button:not([disabled])', 'input:not([disabled])',
  'select:not([disabled])', 'textarea:not([disabled])', '[tabindex]:not([tabindex="-1"])',
].join(',')

export function Dialog({ open, title, eyebrow, children, onClose, closeLabel, className = '', backdropClassName = '', busy = false }: DialogProps) {
  const titleId = useId()
  const dialogRef = useRef<HTMLElement>(null)
  const backdropRef = useRef<HTMLDivElement>(null)
  const returnFocusRef = useRef<HTMLElement | null>(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose

  useEffect(() => {
    if (!open) return
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dialog = dialogRef.current
    const focusables = dialog?.querySelectorAll<HTMLElement>(focusableSelector)
    ;(focusables?.[0] ?? dialog)?.focus()

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !busy) {
        event.preventDefault()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab' || !dialog) return
      const items = [...dialog.querySelectorAll<HTMLElement>(focusableSelector)]
      if (items.length === 0) {
        event.preventDefault()
        dialog.focus()
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
    const backdrop = backdropRef.current
    const background = backdrop?.parentElement
      ? [...backdrop.parentElement.children].filter((item) => item !== backdrop && item instanceof HTMLElement) as HTMLElement[]
      : []
    const backgroundState = background.map((item) => ({ item, inert: item.inert, ariaHidden: item.getAttribute('aria-hidden') }))
    background.forEach((item) => { item.inert = true; item.setAttribute('aria-hidden', 'true') })
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      backgroundState.forEach(({ item, inert, ariaHidden }) => {
        item.inert = inert
        if (ariaHidden === null) item.removeAttribute('aria-hidden'); else item.setAttribute('aria-hidden', ariaHidden)
      })
      returnFocusRef.current?.focus()
    }
  }, [busy, open])

  if (!open) return null

  function closeFromBackdrop(event: MouseEvent<HTMLDivElement>) {
    if (!busy && event.target === event.currentTarget) onClose()
  }

  return (
    <div ref={backdropRef} className={`dialog-backdrop ${backdropClassName}`.trim()} role="presentation" onMouseDown={closeFromBackdrop}>
      <section ref={dialogRef} className={`dialog ${className}`.trim()} role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <header>
          <div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h2 id={titleId}>{title}</h2></div>
          <button className="icon-button" type="button" onClick={onClose} disabled={busy} aria-label={closeLabel}><X size={19} /></button>
        </header>
        {children}
      </section>
    </div>
  )
}
