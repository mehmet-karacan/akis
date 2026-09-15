import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import './records.css'
import { Button } from './Button'

/** Incremental rendering for an already loaded collection; it does not imply API pagination. */
export function ProgressiveRecords<T>({ items, children, batchSize = 25 }: { items: T[]; children: (visible: T[]) => ReactNode; batchSize?: number }) {
  const [count, setCount] = useState(batchSize)
  const sentinel = useRef<HTMLDivElement>(null)
  const { i18n } = useTranslation()
  const more = count < items.length
  const visible = useMemo(() => items.slice(0, count), [items, count])
  useEffect(() => {
    if (!more || !sentinel.current || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(([entry]) => {
      if (entry?.isIntersecting) setCount(current => Math.min(current + batchSize, items.length))
    }, { rootMargin: '160px' })
    observer.observe(sentinel.current)
    return () => observer.disconnect()
  }, [more, count, batchSize, items.length])
  return <>{children(visible)}{more && <div ref={sentinel} className="ui-records-sentinel"><Button type="button" tone="ghost" onClick={() => setCount(current => current + batchSize)}>{i18n.language === 'tr' ? 'Daha Fazla Yükle' : 'Load More'}</Button></div>}</>
}
