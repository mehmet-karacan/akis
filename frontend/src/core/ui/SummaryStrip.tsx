import type { ReactNode } from 'react'
import './records.css'

export interface SummaryMetric {
  label: string
  value: ReactNode
  hint?: string
  icon: ReactNode
  tone?: 'info' | 'success' | 'warning' | 'danger' | 'neutral'
}

export function SummaryStrip({ items, ariaLabel }: { items: SummaryMetric[]; ariaLabel: string }) {
  return <section className="ui-summary-strip" aria-label={ariaLabel}>
    {items.map((item) => <article key={item.label} className={`ui-summary-card tone-${item.tone ?? 'neutral'}`}>
      <span className="ui-summary-icon" aria-hidden="true">{item.icon}</span>
      <div><small>{item.label}</small><strong>{item.value}</strong>{item.hint && <span>{item.hint}</span>}</div>
    </article>)}
  </section>
}
