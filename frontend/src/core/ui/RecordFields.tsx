import type { ReactNode } from 'react'
import './records.css'

export interface RecordField {
  label: string
  value: ReactNode
  icon: ReactNode
  tone?: 'neutral' | 'success' | 'warning' | 'info'
}

/** Audit-style labelled information: meaning is conveyed by text as well as color. */
export function RecordFields({ fields }: { fields: RecordField[] }) {
  return <dl className="ui-record-fields">{fields.map(({ label, value, icon, tone = 'neutral' }) => <div key={label} className={`ui-record-field ui-record-field--${tone}`}><span className="ui-record-field-icon" aria-hidden="true">{icon}</span><div><dt>{label}</dt><dd>{value}</dd></div></div>)}</dl>
}
