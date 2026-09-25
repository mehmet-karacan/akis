import { useRef } from 'react'
import { useTranslation } from 'react-i18next'

export const clampExplorerWidth = (width: number, viewport: number) => Math.max(216, Math.min(Math.min(360, viewport * .38), Number.isFinite(width) ? width : 240))

export function ExplorerResizeHandle({ width, onChange }: { width: number; onChange(width: number): void }) {
  const { i18n } = useTranslation()
  const start = useRef<{ x: number; width: number } | null>(null)
  const set = (next: number) => onChange(clampExplorerWidth(next, window.innerWidth))
  return <div className="explorer-resize-handle" role="separator" tabIndex={0} aria-orientation="vertical"
    aria-label={i18n.language.startsWith('tr') ? 'Gezgin genişliği' : 'Explorer width'} aria-valuemin={216} aria-valuemax={Math.max(216, Math.min(360, window.innerWidth * .38))} aria-valuenow={Math.round(width)}
    onDoubleClick={() => set(240)}
    onPointerDown={event => { if (event.button !== 0) return; event.preventDefault(); start.current = { x: event.clientX, width }; event.currentTarget.setPointerCapture(event.pointerId) }}
    onPointerMove={event => { if (start.current) set(start.current.width + event.clientX - start.current.x) }}
    onPointerUp={event => { start.current = null; if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId) }}
    onPointerCancel={() => { start.current = null }} onLostPointerCapture={() => { start.current = null }}
    onKeyDown={event => {
      const next = event.key === 'ArrowLeft' ? width - 16 : event.key === 'ArrowRight' ? width + 16 : event.key === 'Home' ? 216 : event.key === 'End' ? 360 : undefined
      if (next != null) { event.preventDefault(); set(next) }
    }}><span /></div>
}
