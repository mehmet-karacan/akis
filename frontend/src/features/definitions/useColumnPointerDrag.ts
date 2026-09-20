import { useEffect, useRef, type PointerEvent } from 'react'

export interface ColumnDropTarget { object: string; column: string; canonicalType?: string }
interface DragSession { pointerId: number; x: number; y: number; column: string; canonicalType?: string; active: boolean; element: HTMLDivElement }

// In-canvas column mapping uses pointer capture, not the operating system's
// HTML5 drag loop. Model and palette drops can still use their HTML5 payloads.
export function useColumnPointerDrag({ enabled, onStart, onHover, onEnd, onDrop }: {
  enabled: boolean
  onStart(type?: string): void
  onHover(target: ColumnDropTarget | null): void
  onEnd(): void
  onDrop(column: string, target: ColumnDropTarget): void
}) {
  const session = useRef<DragSession | null>(null)
  const suppressClick = useRef(false)
  const callbacks = useRef({ onStart, onHover, onEnd, onDrop })
  callbacks.current = { onStart, onHover, onEnd, onDrop }

  const clear = () => {
    const current = session.current
    session.current = null
    if (current?.element.hasPointerCapture(current.pointerId)) current.element.releasePointerCapture(current.pointerId)
    if (current?.active) callbacks.current.onEnd()
  }
  useEffect(() => {
    const cancel = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || !session.current) return
      event.preventDefault()
      const current = session.current
      session.current = null
      if (current.element.hasPointerCapture(current.pointerId)) current.element.releasePointerCapture(current.pointerId)
      if (current.active) callbacks.current.onEnd()
    }
    window.addEventListener('keydown', cancel)
    return () => {
      window.removeEventListener('keydown', cancel)
      const current = session.current
      session.current = null
      if (current?.element.hasPointerCapture(current.pointerId)) current.element.releasePointerCapture(current.pointerId)
      if (current?.active) callbacks.current.onEnd()
    }
  }, [])

  const targetAt = (event: PointerEvent<HTMLDivElement>): ColumnDropTarget | null => {
    const element = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>('[data-mapping-target="true"]')
    if (!element || element.closest('.mapping-diagram') !== event.currentTarget.closest('.mapping-diagram')) return null
    const { mappingObject: object, mappingColumn: column, mappingType: canonicalType } = element.dataset
    return object && column ? { object, column, canonicalType } : null
  }
  return {
    consumeClick: () => { const value = suppressClick.current; suppressClick.current = false; return value },
    onPointerDown: (event: PointerEvent<HTMLDivElement>, column: string, canonicalType?: string) => {
      if (!enabled || event.button !== 0 || !event.isPrimary) return
      event.stopPropagation()
      suppressClick.current = false
      session.current = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, column, canonicalType, active: false, element: event.currentTarget }
      event.currentTarget.setPointerCapture(event.pointerId)
    },
    onPointerMove: (event: PointerEvent<HTMLDivElement>) => {
      const current = session.current
      if (!current || current.pointerId !== event.pointerId) return
      if (!current.active && Math.hypot(event.clientX - current.x, event.clientY - current.y) < 5) return
      event.preventDefault(); event.stopPropagation()
      if (!current.active) {
        current.active = true
        suppressClick.current = true
        callbacks.current.onStart(current.canonicalType)
      }
      callbacks.current.onHover(targetAt(event))
    },
    onPointerUp: (event: PointerEvent<HTMLDivElement>) => {
      const current = session.current
      if (!current || current.pointerId !== event.pointerId) return
      if (current.active) {
        event.preventDefault(); event.stopPropagation()
        const target = targetAt(event)
        if (target) callbacks.current.onDrop(current.column, target)
      }
      clear()
    },
    onPointerCancel: clear,
    onLostPointerCapture: clear,
  }
}
