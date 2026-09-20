import { Card } from 'antd'
import type { HTMLAttributes, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

/** A single section order for all catalog record cards. */
export function RecordCard({ header, children, audit, description, footer, className = '', ...attributes }: {
  header: ReactNode; children: ReactNode; audit?: ReactNode; description?: ReactNode; footer?: ReactNode
  className?: string; 'data-connection-uuid'?: string; role?: string
  onClick?: HTMLAttributes<HTMLDivElement>['onClick']; onDoubleClick?: HTMLAttributes<HTMLDivElement>['onDoubleClick']; onKeyDown?: HTMLAttributes<HTMLDivElement>['onKeyDown']; tabIndex?: number
}) {
  const { i18n } = useTranslation()
  const title = i18n.language.startsWith('tr') ? 'Kayıt Bilgileri' : 'Record Information'
  return <Card {...attributes} className={`ui-record-card ${className}`}>
    <header>{header}</header>
    <div className="ui-record-card-fields">{children}</div>
    <section className="ui-record-card-audit" aria-label={title}>{audit && <><h3>{title}</h3>{audit}</>}</section>
    <div className="ui-record-card-description">{description}</div>
    <footer>{footer}</footer>
  </Card>
}
