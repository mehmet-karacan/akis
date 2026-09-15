import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import './records.css'

/** Incremental rendering for an already loaded collection; it does not imply API pagination. */
export function ProgressiveRecords<T>({ items, children, batchSize = 25 }: { items: T[]; children: (visible: T[]) => ReactNode; batchSize?: number }) {
  const [count, setCount] = useState(batchSize)
  const sentinel = useRef<HTMLDivElement>(null)
  const { i18n } = useTranslation()
  const more = count < items.length
  useEffect(() => {
    if (!more || !sentinel.current || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(([entry]) => {
      if (entry?.isIntersecting) setCount(current => Math.min(current + batchSize, items.length))
    }, { rootMargin: '160px' })
    observer.observe(sentinel.current)
    return () => observer.disconnect()
  }, [more, count, batchSize, items.length])
  return <>{children(items.slice(0, count))}{more && <div ref={sentinel} className="ui-records-sentinel"><button type="button" className="button ghost" onClick={() => setCount(current => current + batchSize)}>{i18n.language === 'tr' ? 'Daha Fazla Yükle' : 'Load More'}</button></div>}</>
}
